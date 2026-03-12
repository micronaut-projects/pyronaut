import os
import socket
import shutil
import subprocess
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path


def _e2e_enabled() -> bool:
    if os.environ.get("PYRONAUT_E2E", "false").lower() != "true":
        return False
    return bool(os.environ.get("PYRONAUT_E2E_FIXTURE_DIR"))


@unittest.skipUnless(_e2e_enabled(), "Set PYRONAUT_E2E=true to run e2e tests")
class E2EFlowTest(unittest.TestCase):
    def test_orchestrated_install_process_run_test_flow(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)
            port = self._allocate_port()
            self._write_project_sources(project_dir, port)

            install_result = self._run_cli("install", "--project-dir", str(project_dir))
            self.assertEqual(0, install_result.returncode, install_result.stderr)
            self.assertNotIn("No SLF4J providers were found", install_result.stderr)
            self.assertNotIn("Defaulting to no-operation", install_result.stderr)
            self.assertNotIn("tools/pyronaut-install/bin/pyronaut-install --project-dir", install_result.stderr)

            cache_dir = project_dir / "__pyronaut__"
            self.assertTrue((cache_dir / "resolved-build-dependencies").exists())
            self.assertTrue((cache_dir / "resolved-runtime-dependencies").exists())
            self.assertTrue((cache_dir / "resolved-test-dependencies").exists())

            process_result = self._run_cli(
                "process",
                "--project-dir",
                str(project_dir),
            )
            self.assertEqual(0, process_result.returncode, process_result.stderr)
            self.assertTrue((project_dir / "__pyronaut__" / "classes").is_dir())
            self.assertTrue((project_dir / "__pyronaut__" / "test-classes").is_dir())
            self.assertTrue(
                (project_dir / "__pyronaut__" / "classes" / "python" / "$MyController$Definition.class").exists()
            )

            run_process = self._start_cli(
                "run",
                "--project-dir",
                str(project_dir),
                extra_env={"MICRONAUT_SERVER_PORT": str(port)},
            )
            try:
                body = self._wait_for_http(f"http://localhost:{port}/")
                self.assertEqual("Hello from pyronaut e2e", body)
            finally:
                self._stop_process(run_process)

            test_result = self._run_cli(
                "test",
                "--project-dir",
                str(project_dir),
            )
            self.assertEqual(0, test_result.returncode, test_result.stdout + "\n" + test_result.stderr)
            combined_output = test_result.stdout + test_result.stderr
            self.assertIn("1 passed", combined_output)

    def test_install_dependencies_tree_mode_outputs_scoped_graph(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)
            cache_dir = project_dir / "__pyronaut__"
            manifests = [
                cache_dir / "resolved-build-dependencies",
                cache_dir / "resolved-runtime-dependencies",
                cache_dir / "resolved-test-dependencies",
            ]
            cache_snapshot = {
                manifest: (manifest.exists(), manifest.read_text(encoding="utf-8") if manifest.exists() else None)
                for manifest in manifests
            }

            result = self._run_cli(
                "install",
                "--dependencies",
                "--scope",
                "all",
                "--progress",
                "off",
                "--color",
                "never",
                "--project-dir",
                str(project_dir),
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Dependency tree (build):", result.stdout)
            self.assertIn("Dependency tree (runtime):", result.stdout)
            self.assertIn("Dependency tree (test):", result.stdout)
            self.assertNotIn("\u001b[", result.stdout)

            combined = result.stdout + result.stderr
            self.assertNotIn("No SLF4J providers were found", combined)
            self.assertNotIn("Defaulting to no-operation", combined)

            for manifest in manifests:
                exists_before, content_before = cache_snapshot[manifest]
                self.assertEqual(exists_before, manifest.exists())
                if exists_before:
                    self.assertEqual(content_before, manifest.read_text(encoding="utf-8"))

    def test_concurrent_install_produces_valid_cache_manifests(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            command = self._base_command("install", "--project-dir", str(project_dir))
            env = self._env()
            first = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
            second = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)

            first_stdout, first_stderr = first.communicate(timeout=240)
            second_stdout, second_stderr = second.communicate(timeout=240)
            self.assertEqual(0, first.returncode, first_stdout + first_stderr)
            self.assertEqual(0, second.returncode, second_stdout + second_stderr)

            cache_dir = project_dir / "__pyronaut__"
            manifests = [
                cache_dir / "resolved-build-dependencies",
                cache_dir / "resolved-runtime-dependencies",
                cache_dir / "resolved-test-dependencies",
            ]
            for manifest in manifests:
                self.assertTrue(manifest.exists(), str(manifest))
                for line in manifest.read_text(encoding="utf-8").splitlines():
                    text = line.strip()
                    if text:
                        self.assertTrue(Path(text).is_absolute(), text)

    def _run_cli(self, *args: str, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self._base_command(*args),
            check=False,
            capture_output=True,
            text=True,
            env=self._env(extra_env),
            timeout=240,
        )

    def _start_cli(self, *args: str, extra_env: dict[str, str] | None = None) -> subprocess.Popen[str]:
        return subprocess.Popen(
            self._base_command(*args),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=self._env(extra_env),
        )

    def _base_command(self, *args: str) -> list[str]:
        return [
            os.environ.get("PYRONAUT_E2E_PYTHON", "python3"),
            "-m",
            "pyronaut_cli_v2",
            *args,
        ]

    @staticmethod
    def _env(extra_env: dict[str, str] | None = None) -> dict[str, str]:
        env = dict(os.environ)
        env["NO_PROXY"] = "localhost,127.0.0.1"
        env["no_proxy"] = "localhost,127.0.0.1"
        if extra_env:
            env.update(extra_env)
        return env

    @staticmethod
    def _allocate_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    @staticmethod
    def _write_project_sources(project_dir: Path, port: int) -> None:
        source_dir = project_dir / "src"
        source_dir.mkdir(parents=True, exist_ok=True)
        (source_dir / "controller.py").write_text(
            "from micronaut.http.annotation import Controller, Get\n\n"
            "@Controller\n"
            "class MyController:\n"
            "    @Get(value='/', produces='text/plain')\n"
            "    def index(self) -> str:\n"
            "        return 'Hello from pyronaut e2e'\n",
            encoding="utf-8",
        )

        config_dir = project_dir / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        (config_dir / "application.yml").write_text(
            "micronaut:\n"
            "  server:\n"
            f"    port: {port}\n"
            "  custom:\n"
            "    property: pyronaut-e2e\n",
            encoding="utf-8",
        )

        tests_dir = project_dir / "tests"
        tests_dir.mkdir(parents=True, exist_ok=True)
        (tests_dir / "test_micronaut_integration.py").write_text(
            "import pytest\n"
            "\n"
            "def test_pyronaut_test_invocation():\n"
            "    assert True\n",
            encoding="utf-8",
        )
    def _wait_for_http(self, url: str) -> str:
        deadline = time.time() + 120
        last_error: Exception | None = None
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    payload = response.read().decode("utf-8")
                    if payload:
                        return payload
            except Exception as exc:
                last_error = exc
                time.sleep(1)
        if last_error is not None:
            raise AssertionError(str(last_error))
        raise AssertionError("HTTP endpoint did not respond")

    @staticmethod
    def _stop_process(process: subprocess.Popen[str]) -> None:
        if process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=20)


if __name__ == "__main__":
    unittest.main()
