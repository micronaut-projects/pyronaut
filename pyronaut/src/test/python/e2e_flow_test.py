from __future__ import annotations

import os
import json
import signal
import socket
import shutil
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def _e2e_enabled() -> bool:
    if os.environ.get("PYRONAUT_E2E", "false").lower() != "true":
        return False
    return bool(os.environ.get("PYRONAUT_E2E_FIXTURE_DIR"))


def _e2e_full_enabled() -> bool:
    if not _e2e_enabled():
        return False
    return os.environ.get("PYRONAUT_E2E_FULL", "false").lower() == "true"


def _external_e2e_enabled() -> bool:
    return (
        os.environ.get("PYRONAUT_E2E", "false").lower() == "true"
        and os.environ.get("PYRONAUT_E2E_EXTERNAL", "false").lower() == "true"
    )


@unittest.skipUnless(_e2e_enabled() or _external_e2e_enabled(), "Set PYRONAUT_E2E=true to run e2e tests")
class E2EFlowTest(unittest.TestCase):
    @unittest.skipUnless(_external_e2e_enabled(), "Set PYRONAUT_E2E_EXTERNAL=true to run external-build e2e tests")
    def test_external_maven_and_gradle_fixtures(self):
        fixture_root = Path(__file__).resolve().parents[1] / "resources" / "e2e-external"
        for fixture_name in ("gradle-kotlin", "maven"):
            with self.subTest(fixture=fixture_name), tempfile.TemporaryDirectory() as temp_dir:
                build_tool = "gradle" if fixture_name == "gradle-kotlin" else "mvn"
                if not self._external_build_tool_available(build_tool):
                    continue
                project_dir = Path(temp_dir) / fixture_name
                shutil.copytree(fixture_root / fixture_name, project_dir)
                (project_dir / "project.toml").write_text(
                    "[tool.pyronaut.processor]\n"
                    "incremental = true\n",
                    encoding="utf-8",
                )
                port = self._allocate_port()
                resources = project_dir / "src/main/resources"
                resources.mkdir(parents=True, exist_ok=True)
                (resources / "application.properties").write_text(
                    f"micronaut.server.port={port}\n", encoding="utf-8"
                )

                install_result = self._run_cli("install", "--project-dir", str(project_dir), timeout_seconds=1800)
                self.assertEqual(0, install_result.returncode, install_result.stdout + install_result.stderr)
                cache_dir = project_dir / "__pyronaut__"
                self.assertTrue((cache_dir / "project-layout.properties").is_file())
                layout_text = (cache_dir / "project-layout.properties").read_text(encoding="utf-8")
                self.assertIn("annotationProcessorClasspath=", layout_text)
                self.assertTrue(
                    any(line.startswith("annotationProcessorClasspath=") and line.partition("=")[2].strip()
                        for line in layout_text.splitlines())
                )
                if fixture_name == "maven":
                    self.assertIn("micronaut-core-processor", layout_text)

                process_result = self._run_cli("process", "--project-dir", str(project_dir), timeout_seconds=1800)
                self.assertEqual(0, process_result.returncode, process_result.stdout + process_result.stderr)
                self.assertTrue((cache_dir / "classes").is_dir())
                self.assertTrue((cache_dir / "test-classes").is_dir())

                run_process = self._start_cli(
                    "run", "--project-dir", str(project_dir),
                    extra_env={"MICRONAUT_SERVER_PORT": str(port)},
                    capture_output=True,
                )
                try:
                    self.assertEqual(
                        "external-main-resource",
                        self._wait_for_http(f"http://localhost:{port}/", timeout_seconds=60),
                    )
                except AssertionError as error:
                    output, _ = run_process.communicate(timeout=5)
                    raise AssertionError(f"{error}\n{output or ''}") from error
                finally:
                    self._stop_process(run_process)

                test_result = self._run_cli(
                    "test", "--project-dir", str(project_dir), "--select-class", "example.ExternalBuildTest",
                    timeout_seconds=1800,
                )
                self.assertEqual(0, test_result.returncode, test_result.stdout + test_result.stderr)

                controller = project_dir / "src/main/java/example/ResourceController.java"
                controller.write_text(
                    controller.read_text(encoding="utf-8") + "\n// Incremental test change.\n",
                    encoding="utf-8",
                )
                incremental_test_result = self._run_cli(
                    "test", "--project-dir", str(project_dir), "--select-class", "example.ExternalBuildTest",
                    timeout_seconds=1800,
                )
                incremental_output = incremental_test_result.stdout + incremental_test_result.stderr
                self.assertEqual(0, incremental_test_result.returncode, incremental_output)
                self.assertIn("Incrementally compiling main sources (1 of 2 files)", incremental_output)
                self.assertIn("ResourceController.java", incremental_output)

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
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
                self._force_stop_test_resources_server(project_dir)

            test_result = self._run_cli(
                "test",
                "--project-dir",
                str(project_dir),
            )
            self.assertEqual(0, test_result.returncode, test_result.stdout + "\n" + test_result.stderr)
            combined_output = test_result.stdout + test_result.stderr
            self.assertIn("1 passed", combined_output)
            self.assertNotIn("A restricted method in java.lang.System has been called", combined_output)
            self.assertNotIn("A terminally deprecated method in sun.misc.Unsafe has been called", combined_output)

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
    def test_run_and_test_always_delegate_install_and_process(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            install_result = self._run_cli("install", "--project-dir", str(project_dir))
            self.assertEqual(0, install_result.returncode, install_result.stderr)
            process_result = self._run_cli("process", "--project-dir", str(project_dir))
            self.assertEqual(0, process_result.returncode, process_result.stderr)

            env = {"PYRONAUT_TRACE_DELEGATION": "true"}
            run_process = self._start_cli("run", "--project-dir", str(project_dir), extra_env=env, capture_output=True)
            run_output = ""
            try:
                try:
                    completed_output, _ = run_process.communicate(timeout=20)
                    run_output = completed_output or ""
                except subprocess.TimeoutExpired as timeout:
                    run_output = timeout.stdout or ""
            finally:
                self._stop_process(run_process)
                self._force_stop_test_resources_server(project_dir)
            self.assertIn("pyronaut-install", run_output)
            self.assertIn("pyronaut-processor", run_output)

            test_result = self._run_cli(
                "test",
                "--project-dir",
                str(project_dir),
                extra_env=env,
                timeout_seconds=1800,
            )
            self.assertEqual(0, test_result.returncode, test_result.stdout + test_result.stderr)
            self.assertIn("pyronaut-install", test_result.stderr)
            self.assertIn("pyronaut-processor", test_result.stderr)

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
    def test_no_cache_propagates_to_install_refresh_and_processor(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            env = {"PYRONAUT_TRACE_DELEGATION": "true"}
            result = self._run_cli("test", "--no-cache", "--project-dir", str(project_dir), extra_env=env)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            self.assertIn("pyronaut-install", result.stderr)
            self.assertIn("--refresh", result.stderr)
            self.assertIn("pyronaut-processor", result.stderr)
            self.assertIn("--no-cache", result.stderr)

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
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

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
    def test_concurrent_install_produces_valid_cache_manifests(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            warmup = self._run_cli("install", "--project-dir", str(project_dir))
            self.assertEqual(0, warmup.returncode, warmup.stdout + warmup.stderr)

            command = self._base_command("install", "--project-dir", str(project_dir))
            env = self._env()
            first = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True, env=env)
            second = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True, env=env)

            first.wait(timeout=900)
            second.wait(timeout=900)
            self.assertEqual(0, first.returncode)
            self.assertEqual(0, second.returncode)

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

    @unittest.skipUnless(_e2e_enabled(), "Set PYRONAUT_E2E_FIXTURE_DIR for test-resources e2e tests")
    def test_test_resources_insights_api_auth_matrix(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            start_result = self._run_cli_without_capture("test-resources-server", "start", "--project-dir", str(project_dir))
            self.assertEqual(0, start_result.returncode, start_result.stdout + start_result.stderr)
            try:
                server_uri, token = self._wait_for_test_resources_server(project_dir)

                missing_status, _ = self._http_json(server_uri + "/api/test-resources/health")
                self.assertEqual(401, missing_status)

                invalid_status, _ = self._http_json(
                    server_uri + "/api/test-resources/health",
                    headers={"Authorization": "Bearer invalid-token"},
                )
                self.assertEqual(401, invalid_status)

                valid_health_status, valid_health_payload = self._http_json(
                    server_uri + "/api/test-resources/health",
                    headers={"Authorization": f"Bearer {token}"},
                )
                self.assertIn(valid_health_status, {200, 401})
                if valid_health_status == 200:
                    health = valid_health_payload.get("health")
                    self.assertIsInstance(health, dict)
                    assert isinstance(health, dict)
                    self.assertEqual("UP", health.get("status"))
                    self.assertIsInstance(health.get("uri"), str)
                    self.assertIsInstance(health.get("port"), int)

                    for endpoint, key in (("containers", "containers"), ("properties", "properties"), ("errors", "errors")):
                        status, payload = self._http_json(
                            server_uri + f"/api/test-resources/{endpoint}",
                            headers={"Authorization": f"Bearer {token}"},
                        )
                        self.assertEqual(200, status)
                        self.assertIn(key, payload)
                        self.assertIsInstance(payload[key], list)
            finally:
                stop_result = self._run_cli_without_capture("test-resources-server", "stop", "--project-dir", str(project_dir))
                self.assertEqual(0, stop_result.returncode, stop_result.stdout + stop_result.stderr)

    @unittest.skipUnless(_e2e_enabled(), "Set PYRONAUT_E2E_FIXTURE_DIR for test-resources e2e tests")
    def test_test_resources_stale_state_recovery_and_cleanup(self):
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)

            self._seed_stale_test_resources_settings(project_dir)

            start_result = self._run_cli_without_capture("test-resources-server", "start", "--project-dir", str(project_dir))
            self.assertEqual(0, start_result.returncode, start_result.stdout + start_result.stderr)
            server_uri = ""
            try:
                server_uri, _ = self._wait_for_test_resources_server(project_dir)
                self.assertNotEqual("http://127.0.0.1:9", server_uri)

                properties = self._read_test_resources_settings(project_dir)
                self.assertNotEqual("http://127.0.0.1:9", properties.get("server.uri"))
                self.assertNotEqual("stale-token", properties.get("server.access.token"))

                status, payload = self._http_json(
                    server_uri + "/api/test-resources/health",
                    headers={"Authorization": f"Bearer {properties.get('server.access.token', '')}"},
                )
                self.assertIn(status, {200, 401})
                if status == 200:
                    health = payload.get("health")
                    self.assertIsInstance(health, dict)
            finally:
                stop_result = self._run_cli_without_capture("test-resources-server", "stop", "--project-dir", str(project_dir))
                self.assertEqual(0, stop_result.returncode, stop_result.stdout + stop_result.stderr)

            self.assertTrue(
                not self._test_resources_settings_file(project_dir).exists()
                or self._read_test_resources_settings(project_dir).get("server.uri") != "http://127.0.0.1:9"
            )
            self.assertTrue(server_uri)
            self.assertFalse(self._http_health_available(server_uri + "/health"))

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
    def test_mysql_test_resources_resolution_during_dev_and_test(self):
        self._require_mysql_e2e_environment()
        fixture_dir = Path(os.environ["PYRONAUT_E2E_FIXTURE_DIR"])
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "app"
            shutil.copytree(fixture_dir, project_dir)
            port = self._allocate_port()
            self._enable_mysql_dependencies(project_dir)
            self._write_project_sources(project_dir, port)
            self._write_mysql_test_resources_config(project_dir)

            run_process = self._start_cli(
                "dev",
                "--project-dir",
                str(project_dir),
                extra_env={"MICRONAUT_SERVER_PORT": str(port), "PYRONAUT_TRACE_DELEGATION": "true"},
            )
            try:
                self._wait_for_http(f"http://localhost:{port}/")
                server_uri, token = self._wait_for_test_resources_server(project_dir)
                status, payload = self._http_json(
                    server_uri + "/api/test-resources/properties",
                    headers={"Authorization": f"Bearer {token}"},
                )
                self.assertIn(status, {200, 401})
                if status == 200:
                    properties_payload = payload.get("properties", [])
                    self.assertIsInstance(properties_payload, list)
                    assert isinstance(properties_payload, list)
                    self.assertTrue(self._contains_mysql_resolution(properties_payload), payload)
            finally:
                self._stop_process(run_process)
                self._force_stop_test_resources_server(project_dir)

            test_result = self._run_cli("test", "--project-dir", str(project_dir), extra_env={"PYRONAUT_TRACE_DELEGATION": "true"})
            self.assertEqual(0, test_result.returncode, test_result.stdout + test_result.stderr)
            self.assertIn("[test-resources] start owned server", test_result.stdout + test_result.stderr)
            self.assertIn("[test-resources] stop owned server", test_result.stdout + test_result.stderr)

    @unittest.skipUnless(_e2e_full_enabled(), "Set PYRONAUT_E2E_FULL=true to run full e2e flow tests")
    def test_direct_source_mysql_test_resources_repository_flow(self):
        self._require_mysql_e2e_environment()
        fixture = (
            Path(__file__).resolve().parents[4]
            / "src/main/docs/examples/direct-source/mysql/App.java"
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            shutil.copy2(fixture, project_dir / "App.java")
            port = self._allocate_port()
            process = self._start_cli(
                "dev",
                "App.java",
                "--port",
                str(port),
                capture_output=True,
                cwd=project_dir,
            )
            server_uri = ""
            try:
                self.assertEqual(
                    "0",
                    self._wait_for_http(
                        f"http://localhost:{port}/books/count",
                        timeout_seconds=180,
                    ),
                )
                server_uri, _ = self._wait_for_test_resources_server(project_dir)
                request = urllib.request.Request(
                    f"http://localhost:{port}/books/TheDispossessed",
                    method="POST",
                )
                with urllib.request.urlopen(request, timeout=30) as response:
                    self.assertEqual("1", response.read().decode("utf-8"))
                self.assertEqual(
                    "1",
                    self._wait_for_http(f"http://localhost:{port}/books/count"),
                )
            except AssertionError as error:
                output, _ = process.communicate(timeout=5)
                raise AssertionError(f"{error}\n{output or ''}") from error
            finally:
                self._stop_process(process)
            deadline = time.time() + 30
            while time.time() < deadline and self._test_resources_settings_file(project_dir).exists():
                time.sleep(0.25)
            self.assertFalse(self._test_resources_settings_file(project_dir).exists())
            if server_uri:
                self.assertFalse(self._uri_port_open(server_uri))

    def _run_cli(
        self,
        *args: str,
        extra_env: dict[str, str] | None = None,
        timeout_seconds: int = 900,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self._base_command(*args),
            check=False,
            capture_output=True,
            text=True,
            env=self._env(extra_env),
            timeout=timeout_seconds,
        )

    def _run_cli_without_capture(
        self,
        *args: str,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        completed = subprocess.run(
            self._base_command(*args),
            check=False,
            capture_output=False,
            text=True,
            env=self._env(extra_env),
            timeout=240,
        )
        return subprocess.CompletedProcess(
            completed.args,
            completed.returncode,
            "",
            "",
        )

    def _start_cli(
        self,
        *args: str,
        extra_env: dict[str, str] | None = None,
        capture_output: bool = False,
        cwd: Path | None = None,
    ) -> subprocess.Popen[str]:
        stdout = subprocess.PIPE if capture_output else subprocess.DEVNULL
        stderr = subprocess.STDOUT if capture_output else subprocess.DEVNULL
        return subprocess.Popen(
            self._base_command(*args),
            stdout=stdout,
            stderr=stderr,
            text=True,
            env=self._env(extra_env),
            cwd=cwd,
            start_new_session=True,
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
    def _test_resources_settings_file(project_dir: Path) -> Path:
        return project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

    def _wait_for_test_resources_server(self, project_dir: Path) -> tuple[str, str]:
        deadline = time.time() + 30
        settings_file = self._test_resources_settings_file(project_dir)
        while time.time() < deadline:
            settings = self._read_test_resources_settings(project_dir)
            uri = settings.get("server.uri")
            token = settings.get("server.access.token")
            if uri and token and self._uri_port_open(uri):
                return uri, token
            if settings_file.exists():
                time.sleep(0.25)
                continue
            time.sleep(0.25)
        raise AssertionError(f"Timed out waiting for test resources settings in {settings_file}")

    @staticmethod
    def _uri_port_open(uri: str) -> bool:
        try:
            parsed = urllib.parse.urlparse(uri)
            host = parsed.hostname
            port = parsed.port
            if host is None or port is None:
                return False
            with socket.create_connection((host, port), timeout=1):
                return True
        except OSError:
            return False

    def _read_test_resources_settings(self, project_dir: Path) -> dict[str, str]:
        settings_file = self._test_resources_settings_file(project_dir)
        if not settings_file.exists():
            return {}
        result: dict[str, str] = {}
        for raw in settings_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            result[key.strip()] = self._unescape_properties_value(value.strip())
        return result

    @staticmethod
    def _unescape_properties_value(value: str) -> str:
        return (
            value.replace("\\:", ":")
            .replace("\\=", "=")
            .replace("\\ ", " ")
            .replace("\\\\", "\\")
        )

    @staticmethod
    def _seed_stale_test_resources_settings(project_dir: Path) -> None:
        settings_file = E2EFlowTest._test_resources_settings_file(project_dir)
        settings_file.parent.mkdir(parents=True, exist_ok=True)
        settings_file.write_text(
            "server.uri=http://127.0.0.1:9\n"
            "server.port=9\n"
            "server.access.token=stale-token\n",
            encoding="utf-8",
        )

    @staticmethod
    def _http_json(url: str, headers: dict[str, str] | None = None) -> tuple[int, dict[str, object]]:
        request = urllib.request.Request(url, method="GET", headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                payload = response.read().decode("utf-8")
                return int(response.status), json.loads(payload)
        except urllib.error.HTTPError as exc:
            payload = exc.read().decode("utf-8") if exc.fp is not None else "{}"
            parsed = json.loads(payload) if payload else {}
            return int(exc.code), parsed

    @staticmethod
    def _http_health_available(url: str) -> bool:
        request = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                return int(response.status) == 200
        except Exception:
            return False

    def _require_mysql_e2e_environment(self) -> None:
        if os.environ.get("PYRONAUT_E2E_MYSQL", "false").lower() != "true":
            self.skipTest("Set PYRONAUT_E2E_MYSQL=true to run MySQL test-resources e2e")

        docker = shutil.which("docker")
        if docker is None:
            self.skipTest("Docker CLI is not available")

        probe = subprocess.run(
            [docker, "info"],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        if probe.returncode != 0:
            reason = (probe.stderr or probe.stdout or "docker info failed").strip().splitlines()[0]
            self.skipTest(f"Docker daemon unavailable: {reason}")

    @staticmethod
    def _external_build_tool_available(command: str) -> bool:
        executable = shutil.which(command)
        if executable is None:
            return False
        try:
            return subprocess.run(
                [executable, "--version"], check=False, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, timeout=15,
            ).returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

    @staticmethod
    def _write_mysql_test_resources_config(project_dir: Path) -> None:
        config_dir = project_dir / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        (config_dir / "application.yml").write_text(
            "micronaut:\n"
            "  application:\n"
            "    name: pyronaut-e2e\n"
            "datasources:\n"
            "  default:\n"
            "    dialect: MYSQL\n"
            "    db-type: mysql\n"
            "    driver-class-name: com.mysql.cj.jdbc.Driver\n"
            "    username: test\n"
            "    password: test\n",
            encoding="utf-8",
        )

    @staticmethod
    def _enable_mysql_dependencies(project_dir: Path) -> None:
        pyproject = project_dir / "pyproject.toml"
        content = pyproject.read_text(encoding="utf-8")
        runtime_marker = '  "ch.qos.logback:logback-classic"\n]'
        test_marker = '  "io.micronaut.test:micronaut-test-junit5"\n]'
        if runtime_marker not in content or test_marker not in content:
            raise AssertionError("Unexpected e2e pyproject fixture format")

        content = content.replace(
            runtime_marker,
            '  "ch.qos.logback:logback-classic",\n'
            '  "io.micronaut.sql:micronaut-jdbc-hikari",\n'
            '  "com.mysql:mysql-connector-j"\n'
            ']',
        )
        content = content.replace(
            test_marker,
            '  "io.micronaut.test:micronaut-test-junit5",\n'
            '  "io.micronaut.testresources:micronaut-test-resources-jdbc-mysql"\n'
            ']',
        )
        if "[tool.pyronaut.test-resources]" in content:
            content = content.replace("enabled = false", "enabled = true")
            content = content.replace("infer-classpath = false", "infer-classpath = true")
        else:
            content += "\n[tool.pyronaut.test-resources]\nenabled = true\ninfer-classpath = true\n"
        pyproject.write_text(content, encoding="utf-8")

    @staticmethod
    def _contains_mysql_resolution(properties_payload: list[object]) -> bool:
        for entry in properties_payload:
            if not isinstance(entry, dict):
                continue
            key = str(entry.get("key", ""))
            value = str(entry.get("value", ""))
            resolver = str(entry.get("resolver", ""))
            if key == "datasources.default.url" and value.startswith("jdbc:mysql://"):
                return True
            if "mysql" in resolver.lower() and value.startswith("jdbc:mysql://"):
                return True
        return False

    def _force_stop_test_resources_server(self, project_dir: Path) -> None:
        self._run_cli_without_capture("test-resources-server", "stop", "--project-dir", str(project_dir))

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
    def _wait_for_http(self, url: str, timeout_seconds: int = 720) -> str:
        deadline = time.time() + timeout_seconds
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
        process_group_id: int | None = None
        try:
            process_group_id = os.getpgid(process.pid)
        except ProcessLookupError:
            process_group_id = None
        except OSError:
            process_group_id = None

        process.terminate()
        if process_group_id is not None:
            try:
                os.killpg(process_group_id, signal.SIGTERM)
            except ProcessLookupError:
                pass
            except OSError:
                pass
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            if process_group_id is not None:
                try:
                    os.killpg(process_group_id, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except OSError:
                    pass
            process.wait(timeout=20)


if __name__ == "__main__":
    unittest.main()
