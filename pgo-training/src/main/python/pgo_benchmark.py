#!/usr/bin/env python3
"""Compares Pyronaut native images, for example a baseline build against a PGO build.

Each ``--candidate label=/path/to/executable`` is measured with the training application:

* runtime images: time from launch to the first response (median of ``--launches``), request
  throughput with p50/p99 latency at concurrency 8, throughput of the Python-heavy endpoint, and
  peak RSS;
* pyronaut-dev: incremental ``pyronaut process`` wall time and direct-source ``app.py`` time to
  the first response.

Runtime images are launched directly with the command line the CLI would use, so CLI start-up
does not dilute the comparison. The report is written as JSON and Markdown.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pgo_train  # noqa: E402
from pgo_train import Client, TrainingError, Workload, free_port, log  # noqa: E402

PLAIN_SHIM = """#!/bin/sh
exec "@EXECUTABLE@" "$@"
"""


class Benchmark(pgo_train.Trainer):

    def __init__(self, options: argparse.Namespace, label: str, executable: Path):
        options.jvm = False
        options.skip = []
        options.executable = str(executable)
        super().__init__(options)
        self.label = label
        self.work = Path(options.work_dir).resolve() / label
        self.pids = self.work / "pids"
        self.logs = self.work / "logs"
        self.shims = self.work / "shims"
        self.profiles = self.work / "profiles"
        self.results: dict = {"label": label, "executable": str(executable), "bytes": executable.stat().st_size}

    def prepare(self) -> None:
        for directory in (self.pids, self.logs, self.shims, self.profiles):
            directory.mkdir(parents=True, exist_ok=True)
        directory = self.shims / self.image
        directory.mkdir(exist_ok=True)
        shim = directory / self.image
        shim.write_text(PLAIN_SHIM.replace("@EXECUTABLE@", self.options.executable))
        shim.chmod(0o755)
        self._copy_manifests(directory, Path(self.options.manifests_dir))
        self.instrumented_shim = shim
        if self.image != "pyronaut-dev":
            self._write_jvm_dev_shim()
        self.venv = self._create_venv()

    def _record(self, scenario: str, **details) -> None:
        self.results.setdefault("scenarios", {})[scenario] = details

    # ---- runtime images --------------------------------------------------------------------------

    def run_runtime(self) -> None:
        app_name = "java" if self.image == "pyronaut-run" else "python"
        port = free_port()
        app = self.copy_app(app_name, port)
        env = self.env("prepare")
        for args in (("install",), ("process",)):
            self._check(self.cli(*args), "prepare", app, env=env)
        command = self._launcher_command(app, port)
        self.results["launcher-command"] = " ".join(shlex.quote(part) for part in command)
        startups = [self._time_to_first_response(command, app, port) for _ in range(self.options.launches)]
        self.results["startup-ms"] = {
            "median": round(statistics.median(startups)),
            "min": round(min(startups)),
            "max": round(max(startups)),
        }
        self.results.update(self._load(command, app, port))

    def _launcher_command(self, app: Path, port: int) -> list[str]:
        """Starts ``pyronaut run`` once to learn the exact launcher command line it delegates to."""
        log_file = self.logs / "discover.log"
        with log_file.open("w") as output:
            process = subprocess.Popen(self.cli("run", "--no-validate"), cwd=app, env=self.env("discover"),
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                self._wait_ready(port, process, log_file)
            finally:
                self._signal_group(process, 15)
        shim = str(self.instrumented_shim)
        for line in log_file.read_text(errors="replace").splitlines():
            if line.startswith(shim + " "):
                return [self.options.executable, *shlex.split(line)[1:]]
        raise TrainingError(f"Could not find the delegated {self.image} command line in {log_file}")

    def _launch(self, command: list[str], app: Path) -> subprocess.Popen:
        env = self.env("benchmark")
        return subprocess.Popen(command, cwd=app, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _time_to_first_response(self, command: list[str], app: Path, port: int) -> float:
        started = time.monotonic()
        process = self._launch(command, app)
        try:
            deadline = started + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise TrainingError(f"{self.label}: launcher exited with {process.returncode} during start-up")
                try:
                    status, _ = Client(port).request("GET", "/hello")
                    if status == 200:
                        return (time.monotonic() - started) * 1000
                except OSError:
                    pass
                time.sleep(0.005)
            raise TrainingError(f"{self.label}: no response within 120s")
        finally:
            process.terminate()
            process.wait()

    def _load(self, command: list[str], app: Path, port: int) -> dict:
        process = self._launch(command, app)
        peak_rss = [0]
        stop = threading.Event()

        def sample() -> None:
            while not stop.is_set():
                result = subprocess.run(["ps", "-o", "rss=", "-p", str(process.pid)], capture_output=True, text=True)
                if result.stdout.strip():
                    peak_rss[0] = max(peak_rss[0], int(result.stdout.strip()))
                stop.wait(0.25)

        sampler = threading.Thread(target=sample, daemon=True)
        sampler.start()
        try:
            self._wait_ready(port, process, self.logs / "discover.log")
            client = Client(port)
            Workload(self.options.warmup_scale, has_summary=True).run(client)
            ids = [client.expect("POST", "/pets", 201, {"name": f"Bench {i}", "species": "dog", "age": 3})["id"]
                   for i in range(200)]
            mixed = self._timed(client, self.options.duration, lambda i: (
                ("GET", "/hello", None, 200) if i % 4 == 0 else
                ("GET", f"/pets/{ids[i % len(ids)]}", None, 200) if i % 4 == 1 else
                ("GET", "/pets/species/dog", None, 200) if i % 4 == 2 else
                ("POST", "/pets", {"name": f"Load {i}", "species": "cat", "age": i % 9}, 201)))
            document = Workload(1, True)._summary_document(40, 400)
            python = self._timed(client, self.options.duration, lambda i: ("POST", "/pets/summary", document, 200),
                                 content_type="text/plain")
            return {"mixed": mixed, "summary": python, "peak-rss-mb": round(peak_rss[0] / 1024)}
        finally:
            stop.set()
            process.terminate()
            process.wait()

    @staticmethod
    def _timed(client: Client, seconds: float, request, concurrency: int = 8, content_type: str = "application/json") -> dict:
        latencies: list[float] = []
        lock = threading.Lock()
        deadline = time.monotonic() + seconds
        counter = [0]

        def worker() -> None:
            local = Client(client.port)
            mine = []
            while time.monotonic() < deadline:
                with lock:
                    i = counter[0]
                    counter[0] += 1
                method, path, body, status = request(i)
                started = time.perf_counter()
                local.expect(method, path, status, body, content_type)
                mine.append((time.perf_counter() - started) * 1000)
            with lock:
                latencies.extend(mine)

        threads = [threading.Thread(target=worker) for _ in range(concurrency)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        latencies.sort()
        return {
            "requests-per-second": round(len(latencies) / seconds),
            "p50-ms": round(latencies[len(latencies) // 2], 2),
            "p99-ms": round(latencies[int(len(latencies) * 0.99)], 2),
        }

    # ---- pyronaut-dev ----------------------------------------------------------------------------

    def run_dev(self) -> None:
        app = self.copy_app("python", free_port())
        self._check(self.cli("install"), "prepare", app)
        self._check(self.cli("process"), "prepare", app)
        controller = app / "src" / "training" / "controller.py"
        times = []
        for i in range(self.options.process_runs):
            controller.write_text(controller.read_text() + f"\n# benchmark change {i}\n")
            times.append(self._check(self.cli("process"), "process-incremental", app))
        self.results["process-incremental-s"] = {"median": statistics.median(times), "min": min(times), "max": max(times)}
        direct = self.copy_app("direct-python")
        firsts = []
        for i in range(self.options.process_runs):
            port = free_port()
            started = time.monotonic()
            process = subprocess.Popen(self.cli("run", "app.py", "--port", str(port)), cwd=direct, env=self.env("direct"),
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                self._wait_ready(port, process, self.logs / "direct.log")
                firsts.append(round(time.monotonic() - started, 2))
            finally:
                self._signal_group(process, 15)
        self.results["direct-app-py-first-response-s"] = {"median": statistics.median(firsts), "min": min(firsts), "max": max(firsts)}


def markdown(image: str, results: list[dict]) -> str:
    labels = [result["label"] for result in results]
    rows = [("Executable (MiB)", lambda r: f"{r['bytes'] / 1048576:.1f}")]
    if image == "pyronaut-dev":
        rows += [
            ("Incremental `process`, median (s)", lambda r: r["process-incremental-s"]["median"]),
            ("Direct `app.py` to first response, median (s)", lambda r: r["direct-app-py-first-response-s"]["median"]),
        ]
    else:
        rows += [
            ("Launch to first response, median (ms)", lambda r: r["startup-ms"]["median"]),
            ("Mixed requests/s (c=8)", lambda r: r["mixed"]["requests-per-second"]),
            ("Mixed p50 / p99 (ms)", lambda r: f"{r['mixed']['p50-ms']} / {r['mixed']['p99-ms']}"),
            ("Python summary requests/s (c=8)", lambda r: r["summary"]["requests-per-second"]),
            ("Python summary p50 / p99 (ms)", lambda r: f"{r['summary']['p50-ms']} / {r['summary']['p99-ms']}"),
            ("Peak RSS (MiB)", lambda r: r["peak-rss-mb"]),
        ]
    lines = [f"### {image}", "", "| Metric | " + " | ".join(labels) + " |", "| --- |" + " ---: |" * len(labels)]
    for name, value in rows:
        lines.append(f"| {name} | " + " | ".join(str(value(result)) for result in results) + " |")
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", choices=pgo_train.IMAGES, required=True)
    parser.add_argument("--candidate", action="append", required=True, help="label=/path/to/executable")
    parser.add_argument("--manifests-dir", required=True)
    parser.add_argument("--dev-install-dir", required=True)
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--apps-dir", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--cli-source", required=True)
    parser.add_argument("--graalpy", required=True)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--tool", action="append", default=[])
    parser.add_argument("--launches", type=int, default=20)
    parser.add_argument("--duration", type=float, default=30)
    parser.add_argument("--warmup-scale", type=float, default=0.2)
    parser.add_argument("--process-runs", type=int, default=5)
    parser.add_argument("--startup-timeout", type=int, default=300)
    parser.add_argument("--shutdown-timeout", type=int, default=60)
    parser.add_argument("--profiles-dir", default="unused")
    options = parser.parse_args(argv)

    results = []
    for candidate in options.candidate:
        label, executable = candidate.split("=", 1)
        log(f"benchmarking {options.image} {label}: {executable}")
        benchmark = Benchmark(argparse.Namespace(**vars(options)), label, Path(executable).resolve())
        try:
            benchmark.prepare()
            benchmark.run_dev() if options.image == "pyronaut-dev" else benchmark.run_runtime()
        except TrainingError as error:
            log(f"FAILED: {error}")
            return 1
        results.append(benchmark.results)
    work = Path(options.work_dir)
    (work / "benchmark.json").write_text(json.dumps(results, indent=2) + "\n")
    report = markdown(options.image, results)
    (work / "benchmark.md").write_text(report)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
