"""Opt-in production packaging and deployment matrix."""
from __future__ import annotations

import contextlib
import json
import os
import re
import shutil
import signal
import subprocess
import time
import unittest
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HELLO_BODY = "Hello World"
SCENARIOS = (
    "jvm-wheel", "native-wheel", "default-base-wheel", "custom-base-wheel",
    "jvm-docker", "native-docker", "default-base-docker", "custom-base-docker",
)


@dataclass
class ScenarioResult:
    fixture: str
    scenario: str
    status: str
    detail: str = ""
    command: str = ""
    log: str = ""
    artifact: str = ""
    response: str = ""
    elapsed_seconds: float = 0.0


class PackagingMatrixE2ETest(unittest.TestCase):
    def test_matrix_shape(self) -> None:
        self.assertEqual(40, len(PackagingMatrixRunner._fixtures()) * len(SCENARIOS))
        self.assertEqual("Hello World", HELLO_BODY)

    @unittest.skipUnless(
        os.environ.get("PYRONAUT_PACKAGING_E2E", "false").lower() == "true",
        "Set PYRONAUT_PACKAGING_E2E=true to run packaging matrix E2E tests",
    )
    def test_production_packaging_matrix(self) -> None:
        runner = PackagingMatrixRunner()
        results = runner.run()
        failures = [result for result in results if result.status != "passed"]
        self.assertFalse(failures, runner.report_path.read_text(encoding="utf-8"))


class BlockedScenario(RuntimeError):
    pass


class PackagingMatrixRunner:
    def __init__(self) -> None:
        self.cli = os.environ.get("PYRONAUT_PACKAGING_E2E_CLI", "pyronaut")
        self.workspace = Path(os.environ.get(
            "PYRONAUT_PACKAGING_E2E_WORKSPACE",
            str(Path.home() / "dev" / "pyronaut-testing"),
        )).expanduser().resolve()
        self.output = ROOT / "build" / "packaging-e2e"
        self.logs = self.output / "logs"
        self.results: list[ScenarioResult] = []
        self.port = 19080
        self.last_command = ""

    @property
    def report_path(self) -> Path:
        return self.output / "summary.md"

    def run(self) -> list[ScenarioResult]:
        self._preflight()
        self.output.mkdir(parents=True, exist_ok=True)
        self.logs.mkdir(parents=True, exist_ok=True)
        self.workspace.mkdir(parents=True, exist_ok=True)
        for fixture, source in self._fixtures().items():
            for scenario in SCENARIOS:
                self._run_scenario(fixture, source, scenario)
        self._write_reports()
        return self.results

    def _preflight(self) -> None:
        cli = subprocess.run([self.cli, "--version"], capture_output=True, text=True, check=False, timeout=30)
        if cli.returncode != 0:
            raise RuntimeError(f"Installed Pyronaut CLI unavailable: {cli.stderr.strip()}")
        if shutil.which("docker") is None:
            raise RuntimeError("Docker CLI is required")
        docker = subprocess.run(["docker", "info"], capture_output=True, text=True, check=False, timeout=30)
        if docker.returncode != 0:
            raise RuntimeError(f"Docker daemon unavailable: {docker.stderr.strip()}")

    @staticmethod
    def _fixtures() -> dict[str, Path | None]:
        return {
            "direct-java": None,
            "direct-python": None,
            "gradle-java": Path("/Users/graemerocher/dev/micronaut/apps/fresh5"),
            "maven-java": Path("/Users/graemerocher/dev/micronaut/apps/fresh-maven"),
            "configured-python": Path("/Users/graemerocher/dev/micronaut/demos/simple-python"),
        }

    def _run_scenario(self, fixture: str, source: Path | None, scenario: str) -> None:
        started = time.monotonic()
        project = self.workspace / fixture / scenario
        log = self.logs / f"{fixture}-{scenario}.log"
        result = ScenarioResult(fixture, scenario, "failed", log=str(log))
        try:
            if project.exists():
                shutil.rmtree(project)
            project.mkdir(parents=True)
            if source is None:
                self._write_direct_fixture(fixture, project)
            else:
                shutil.copytree(source, project, dirs_exist_ok=True)
                self._normalize_fixture(fixture, project)
            result.artifact, result.response = self._execute(fixture, project, scenario, log)
            result.command = self.last_command
            result.status = "passed"
        except BlockedScenario as exc:
            result.status, result.detail = "blocked", str(exc)
        except Exception as exc:  # noqa: BLE001 - every matrix case must be reported
            result.detail = f"{type(exc).__name__}: {exc}"
        result.elapsed_seconds = round(time.monotonic() - started, 2)
        self.results.append(result)

    def _execute(self, fixture: str, project: Path, scenario: str, log: Path) -> tuple[str, str]:
        direct = fixture.startswith("direct-")
        language = "python" if fixture.endswith("python") else "java"
        image = f"pyronaut-e2e-{_slug(fixture)}-{_slug(scenario)}"
        self._configure_docker(project, image)
        if scenario in {"custom-base-wheel", "custom-base-docker"}:
            base = project / "__pyronaut__" / "e2e-base" / language
            base_args = self._build_args(project, direct, language, "native", docker=scenario.endswith("docker"), base=True, output=base)
            self._build(base_args, project, log, 3600)
            if scenario.endswith("wheel"):
                self._configure_base(project, base)
                self._build(self._build_args(project, direct, language, "native"), project, log, 3600)
                return self._wheel(project, fixture, language, log, setup=direct)
            self._configure_docker_base(project, f"{image}:0.1.0-native-base")
            self._build(self._build_args(project, direct, language, "native", docker=True), project, log, 3600)
            return f"{image}:0.1.0-native", self._docker(f"{image}:0.1.0-native", project, log)
        if scenario == "default-base-wheel":
            self._build(self._build_args(project, direct, language, "native", default_base=True), project, log, 3600)
            return self._wheel(project, fixture, language, log, setup=direct)
        if scenario == "default-base-docker":
            self._build(self._build_args(project, direct, language, "native", docker=True, default_base=True), project, log, 3600)
            self._build(self._build_args(project, direct, language, "native", docker=True), project, log, 3600)
            return f"{image}:0.1.0-native", self._docker(f"{image}:0.1.0-native", project, log)
        if scenario == "jvm-docker":
            self._build(self._build_args(project, direct, language, "jvm", docker=True), project, log, 1800)
            return f"{image}:0.1.0", self._docker(f"{image}:0.1.0", project, log)
        if scenario == "native-docker":
            self._build(self._build_args(project, direct, language, "native", docker=True), project, log, 3600)
            return f"{image}:0.1.0-native", self._docker(f"{image}:0.1.0-native", project, log)
        mode = "native" if scenario == "native-wheel" else "jvm"
        self._build(self._build_args(project, direct, language, mode), project, log, 3600)
        return self._wheel(project, fixture, language, log, setup=False)

    def _build_args(self, project: Path, direct: bool, language: str, mode: str, *, docker: bool = False, base: bool = False, default_base: bool = False, output: Path | None = None) -> list[str]:
        args = ["build"]
        if direct:
            args += ["App.py" if language == "python" else "App.java", "--name", _project_name(project), "--version", "0.1.0"]
        args.append(f"--{mode}")
        if docker:
            args.append("--docker")
        if base:
            args.append("--base-image")
        if default_base:
            args.append("--base-image=default")
        if output is not None:
            args += ["--base-image-output", str(output)]
        args += ["--project-dir", str(project), "--no-validate"]
        setup = project / "pyproject.toml"
        if direct and setup.exists():
            args += ["--setup", str(setup)]
        return args

    def _build(self, args: list[str], project: Path, log: Path, timeout: int) -> None:
        self.last_command = f"{self.cli} {' '.join(args)}"
        completed = subprocess.run([self.cli, *args], cwd=project, capture_output=True, text=True, check=False, timeout=timeout)
        with log.open("a", encoding="utf-8") as stream:
            stream.write(f"$ {self.cli} {' '.join(args)}\n{completed.stdout}\n{completed.stderr}\n")
        if completed.returncode != 0:
            if "--base-image" in args and "--base-image=default" not in args:
                raise BlockedScenario(f"base prerequisite failed with exit code {completed.returncode}")
            raise RuntimeError(f"build failed with exit code {completed.returncode}")

    def _wheel(self, project: Path, fixture: str, language: str, log: Path, *, setup: bool) -> tuple[str, str]:
        wheels = sorted((project / "dist").glob("*.whl"))
        if not wheels:
            raise RuntimeError("No wheel was produced")
        wheel = wheels[-1]
        return str(wheel), self._install_and_probe(wheel, project, log)

    def _install_and_probe(self, wheel: Path, project: Path, log: Path) -> str:
        venv = project / "__pyronaut__" / "e2e-venv"
        python = os.environ.get("PYRONAUT_PACKAGING_E2E_PYTHON", "python3")
        subprocess.run([python, "-m", "venv", str(venv)], check=True, timeout=180)
        pip = venv / "bin" / "pip"
        subprocess.run([str(pip), "install", "--no-deps", str(wheel)], check=True, timeout=600)
        launcher = venv / "bin" / _project_name(project)
        if not launcher.exists():
            candidates = sorted(venv.glob("bin/*"))
            launcher = next((path for path in candidates if path.name.startswith(_slug(project.name))), launcher)
        return self._process([str(launcher)], project, log)

    def _process(self, command: list[str], project: Path, log: Path) -> str:
        port = self._allocate_port()
        process = subprocess.Popen(command, cwd=project, env=dict(os.environ, MICRONAUT_SERVER_PORT=str(port)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        try:
            body = self._wait_http(port)
            if HELLO_BODY not in body:
                raise RuntimeError(f"unexpected response: {body!r}")
            return body
        finally:
            self._stop_process(process, log)

    def _docker(self, image: str, project: Path, log: Path) -> str:
        port = self._allocate_port()
        name = f"pyronaut-e2e-{os.getpid()}-{port}"
        command = ["docker", "run", "--rm", "--name", name, "-e", "MICRONAUT_SERVER_PORT=8080", "-p", f"127.0.0.1:{port}:8080", image]
        process = subprocess.Popen(command, cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        try:
            body = self._wait_http(port, timeout=180)
            if HELLO_BODY not in body:
                raise RuntimeError(f"unexpected response: {body!r}")
            return body
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True, check=False, timeout=30)
            self._stop_process(process, log)

    def _wait_http(self, port: int, timeout: int = 120) -> str:
        deadline = time.time() + timeout
        last_error: Exception | None = None
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/hello", timeout=3) as response:
                    return response.read().decode("utf-8")
            except Exception as exc:  # noqa: BLE001 - retry until timeout
                last_error = exc
                time.sleep(1)
        raise RuntimeError(f"HTTP startup timeout: {last_error}")

    @staticmethod
    def _stop_process(process: subprocess.Popen[str], log: Path) -> None:
        if process.poll() is None:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=20)
        output = process.stdout.read() if process.stdout is not None else ""
        with log.open("a", encoding="utf-8") as stream:
            stream.write(f"\n[process output]\n{output}\n")

    @staticmethod
    def _write_direct_fixture(fixture: str, project: Path) -> None:
        if fixture == "direct-java":
            (project / "App.java").write_text(
                "package e2e;\n"
                "import io.micronaut.http.annotation.Controller;\n"
                "import io.micronaut.http.annotation.Get;\n"
                "@Controller public class App {\n"
                '  @Get("/hello") public String hello() { return "Hello World"; }\n}\n',
                encoding="utf-8",
            )
        else:
            (project / "App.py").write_text(
                "from pyronaut.build import Dependency\n"
                "from micronaut.http.annotation import Get\n\n"
                'Dependency(group="io.micronaut", module="micronaut-http-server-netty")\n\n'
                '@Get(value="/hello", produces="text/plain")\n'
                "def hello() -> str:\n    return \"Hello World\"\n",
                encoding="utf-8",
            )

    @staticmethod
    def _normalize_fixture(fixture: str, project: Path) -> None:
        if fixture == "maven-java":
            target = project / "src/main/java/fresh/maven/HelloController.java"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                'package fresh.maven;\nimport io.micronaut.http.annotation.Controller;\n'
                'import io.micronaut.http.annotation.Get;\n@Controller\npublic class HelloController {\n'
                '  @Get("/hello") public String hello() { return "Hello World"; }\n}\n',
                encoding="utf-8",
            )
        elif fixture == "configured-python":
            (project / "src/simple_python/controller.py").write_text(
                'from micronaut.http.annotation import Get\n\n'
                '@Get(value="/hello", produces="text/plain")\n'
                "def hello() -> str:\n    return \"Hello World\"\n",
                encoding="utf-8",
            )

    @staticmethod
    def _configure_docker(project: Path, image: str) -> None:
        pyproject = project / "pyproject.toml"
        text = pyproject.read_text(encoding="utf-8") if pyproject.exists() else ""
        if "[project]" not in text:
            text += f'\n[project]\nname = "{_project_name(project)}"\nversion = "0.1.0"\n'
        if "[tool.pyronaut]" not in text:
            text += "\n[tool.pyronaut]\nrepositories = [\"mavenCentral\"]\n"
        if "[tool.pyronaut.build.docker]" not in text:
            text += "\n[tool.pyronaut.build.docker]\n"
        if re.search(r"^image-name\s*=", text, re.MULTILINE):
            text = re.sub(r"^image-name\s*=.*$", f'image-name = "{image}"', text, flags=re.MULTILINE)
        else:
            text += f'image-name = "{image}"\n'
        pyproject.write_text(text, encoding="utf-8")

    @staticmethod
    def _configure_base(project: Path, base: Path) -> None:
        pyproject = project / "pyproject.toml"
        text = pyproject.read_text(encoding="utf-8") if pyproject.exists() else ""
        text = _set_toml_key(text, "[tool.pyronaut.build]", "base-image", str(base))
        pyproject.write_text(text, encoding="utf-8")
        (project / "setup.toml").write_text(
            f'[project]\nname = "{_project_name(project)}"\nversion = "0.1.0"\n\n'
            f'[tool.pyronaut.build]\nmode = "native"\nbase-image = "{base}"\n', encoding="utf-8"
        )

    @staticmethod
    def _configure_docker_base(project: Path, image: str) -> None:
        pyproject = project / "pyproject.toml"
        text = pyproject.read_text(encoding="utf-8")
        text = _set_toml_key(text, "[tool.pyronaut.build.docker]", "base-image", image)
        pyproject.write_text(text, encoding="utf-8")

    def _allocate_port(self) -> int:
        self.port += 1
        return self.port

    def _write_reports(self) -> None:
        (self.output / "results.json").write_text(
            json.dumps([asdict(result) for result in self.results], indent=2) + "\n",
            encoding="utf-8",
        )
        lines = ["# Production Packaging E2E", "", "| Fixture | Scenario | Status | Detail |", "|---|---|---|---|"]
        issues = ["# Follow-up Issues", ""]
        for result in self.results:
            lines.append(f"| {result.fixture} | {result.scenario} | {result.status} | {result.detail} |")
            if result.status in {"failed", "blocked"}:
                issues.append(f"- `{result.fixture}/{result.scenario}` **{result.status}**: {result.detail}; log `{result.log}`")
        (self.output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        (self.output / "follow-up-issues.md").write_text("\n".join(issues) + "\n", encoding="utf-8")


def _slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-") or "app"


def _project_name(project: Path) -> str:
    return _slug(project.name)


def _set_toml_key(text: str, section: str, key: str, value: str) -> str:
    """Set one simple string key in a generated test-only TOML overlay."""
    quoted = f'{key} = "{value}"'
    section_start = text.find(section)
    if section_start < 0:
        return text.rstrip() + f"\n\n{section}\n{quoted}\n"
    next_section = text.find("\n[", section_start + len(section))
    if next_section < 0:
        next_section = len(text)
    block = text[section_start:next_section]
    if re.search(rf"^{re.escape(key)}\s*=", block, re.MULTILINE):
        block = re.sub(rf"^{re.escape(key)}\s*=.*$", quoted, block, flags=re.MULTILINE)
    else:
        block = block.rstrip() + f"\n{quoted}\n"
    return text[:section_start] + block + text[next_section:]


if __name__ == "__main__":
    unittest.main()
