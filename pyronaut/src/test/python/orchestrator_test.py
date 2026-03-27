import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import socket
import threading
from unittest.mock import patch
from unittest.mock import ANY

_CLI_MODULE_PATH = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2" / "cli.py"
_CLI_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.cli", _CLI_MODULE_PATH)
if _CLI_SPEC is None or _CLI_SPEC.loader is None:
    raise RuntimeError("Failed loading pyronaut_cli_v2.cli for tests")
_TUI_PACKAGE_DIR = _CLI_MODULE_PATH.parent / "tui"
if str(_TUI_PACKAGE_DIR.parent) not in __import__("sys").path:
    __import__("sys").path.insert(0, str(_TUI_PACKAGE_DIR.parent))

__import__("sys").modules.pop("pyronaut_cli_v2", None)
pkg = __import__("types").ModuleType("pyronaut_cli_v2")
pkg.__path__ = [str(_CLI_MODULE_PATH.parent)]
__import__("sys").modules["pyronaut_cli_v2"] = pkg

cli = importlib.util.module_from_spec(_CLI_SPEC)
_CLI_SPEC.loader.exec_module(cli)


class OrchestratorTest(unittest.TestCase):
    @staticmethod
    def _normalized_project_dir(value: str) -> str:
        return str(Path(value).resolve())

    def _assert_test_resources_start(self, command_line, project_dir: str) -> None:
        self.assertEqual("/tmp/pyronaut-test-resources-server", command_line[0])
        self.assertEqual("start", command_line[1])
        self.assertEqual("--project-dir", command_line[2])
        self.assertEqual(self._normalized_project_dir(project_dir), command_line[3])
        self.assertEqual("--owner-token", command_line[4])
        self.assertIsInstance(command_line[5], str)

    def _assert_test_resources_stop(self, command_line, project_dir: str) -> None:
        self.assertEqual("/tmp/pyronaut-test-resources-server", command_line[0])
        self.assertEqual("stop", command_line[1])
        self.assertEqual("--project-dir", command_line[2])
        self.assertEqual(self._normalized_project_dir(project_dir), command_line[3])
        self.assertEqual("--owner-token", command_line[4])
        self.assertIsInstance(command_line[5], str)
    def test_unknown_command_returns_usage_error(self):
        exit_code = cli.run(["unknown"], runner=self._runner_ok(), resolver=self._resolver(), platform_name="linux")
        self.assertEqual(cli.USAGE_ERROR, exit_code)

    def test_run_performs_install_process_then_run(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["run", "--project", str(project_dir), "--main-class", "example.Main"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

            self.assertEqual(0, exit_code)
            self.assertEqual(6, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"], executed[0])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir)], executed[1])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir)], executed[2])
            self._assert_test_resources_start(executed[3], str(project_dir))
            self.assertEqual(
                ["/tmp/pyronaut-run", "--project-dir", str(project_dir), "--main-class", "example.Main"],
                executed[4],
            )
            self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_run_skips_preflight_when_artifacts_already_exist(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "ready"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = project_dir / "__pyronaut__" / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            for file_name in [
                "resolved-build-dependencies",
                "resolved-runtime-dependencies",
                "resolved-test-dependencies",
            ]:
                (cache_dir / file_name).write_text("/tmp/stub.jar\n", encoding="utf-8")

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["run", "--project", str(project_dir), "--main-class", "example.Main"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

            self.assertEqual(0, exit_code)
            self.assertEqual(6, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"], executed[0])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir)], executed[1])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir)], executed[2])
            self._assert_test_resources_start(executed[3], str(project_dir))
            self.assertEqual(
                ["/tmp/pyronaut-run", "--project-dir", str(project_dir), "--main-class", "example.Main"],
                executed[4],
            )
            self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_preflight_failure_stops_run(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing"
            project_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line):
                executed.append(command_line)
                if "pyronaut-install" in command_line[0]:
                    return 4
                return 0

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

            self.assertEqual(4, exit_code)
            self.assertEqual(2, len(executed))
            self.assertIn("pyronaut-validate-config", executed[0][0])
            self.assertIn("pyronaut-install", executed[1][0])

    def test_platform_guardrail(self):
        exit_code = cli.run(["install"], runner=self._runner_ok(), resolver=self._resolver(), platform_name="win32")
        self.assertEqual(cli.PLATFORM_UNSUPPORTED, exit_code)

    def test_default_delegation_does_not_print_command_line(self):
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["install", "--project-dir", "/tmp/demo"],
                runner=self._runner_ok(),
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("", stderr.getvalue())

    def test_trace_delegation_prints_command_line_when_enabled(self):
        stderr = io.StringIO()
        original = os.environ.get("PYRONAUT_TRACE_DELEGATION")
        os.environ["PYRONAUT_TRACE_DELEGATION"] = "true"
        try:
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["install", "--project-dir", "/tmp/demo"],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )
        finally:
            if original is None:
                del os.environ["PYRONAUT_TRACE_DELEGATION"]
            else:
                os.environ["PYRONAUT_TRACE_DELEGATION"] = original

        self.assertEqual(0, exit_code)
        self.assertIn("/tmp/pyronaut-install --project-dir /tmp/demo", stderr.getvalue())

    def test_process_forwards_verbose_flag(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["process", "--project-dir", "/tmp/demo", "--verbose"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--verbose"]],
            executed,
        )

    def test_test_performs_process_when_test_classes_missing(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing-test-classes"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            for file_name in [
                "resolved-build-dependencies",
                "resolved-runtime-dependencies",
                "resolved-test-dependencies",
            ]:
                (cache_dir / file_name).write_text("/tmp/stub.jar\n", encoding="utf-8")

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["test", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir)], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir)], executed[2])
        self._assert_test_resources_start(executed[3], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", str(project_dir)], executed[4])
        self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_stop_skipped_when_session_owner_mismatch(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "mismatch"
            session_dir = project_dir / "__pyronaut__"
            session_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line):
                executed.append(command_line)
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    (session_dir / "test-resources-session.json").write_text(
                        "{\"ownerToken\":\"other\",\"ownerPid\":999,\"ownerCommand\":\"other\",\"startedAt\":0}\n",
                        encoding="utf-8",
                    )
                return 0

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))

    def test_test_skips_process_when_both_output_dirs_exist(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "ready-test"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            for file_name in [
                "resolved-build-dependencies",
                "resolved-runtime-dependencies",
                "resolved-test-dependencies",
            ]:
                (cache_dir / file_name).write_text("/tmp/stub.jar\n", encoding="utf-8")

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["test", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir)], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir)], executed[2])
        self._assert_test_resources_start(executed[3], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", str(project_dir)], executed[4])
        self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_run_forwards_no_cache_to_install_and_processor(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["run", "--project-dir", "/tmp/demo", "--no-cache"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run", "--no-cache"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--refresh"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--no-cache"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-run", "--project-dir", "/tmp/demo"], executed[4])
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_forwards_no_cache_to_install_and_processor(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["test", "--project-dir", "/tmp/demo", "--no-cache"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test", "--no-cache"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--refresh"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--no-cache"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", "/tmp/demo"], executed[4])
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_starts_test_resources_before_validation_and_reuses_fresh_env(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            settings_file = settings_dir / "test-resources.properties"

            def runner(command_line, env=None):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:61234\n"
                        "server.access.token=fresh-token\n",
                        encoding="utf-8",
                    )
                return 0

            exit_code = cli.run(
                ["test", "--project-dir", str(project_dir)],
                runner_with_env=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-install", "--project-dir", str(project_dir)],
            executed[0][0],
        )
        self.assertEqual(
            ["/tmp/pyronaut-processor", "--project-dir", str(project_dir)],
            executed[1][0],
        )
        self._assert_test_resources_start(executed[2][0], str(project_dir))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"],
            executed[3][0],
        )
        validate_env = executed[3][1] or {}
        self.assertIn("micronaut.test.resources.server.uri=http://localhost:61234", validate_env.get("JAVA_TOOL_OPTIONS", ""))
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", str(project_dir)], executed[4][0])
        self._assert_test_resources_stop(executed[5][0], str(project_dir))

    def test_shared_server_mode_does_not_stop_on_session_exit(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "shared"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "shared"

[tool.pyronaut.testResources]
sharedServer = true
""".strip()
                + "\n",
                encoding="utf-8",
            )

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))

    def test_external_test_resources_server_is_not_claimed_or_stopped(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "external"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            settings_file.parent.mkdir(parents=True, exist_ok=True)
            settings_file.write_text(
                "server.uri=http\\://localhost\\:61234\n"
                "server.access.token=external-token\n",
                encoding="utf-8",
            )

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir)], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir)], executed[2])
        self.assertEqual(["/tmp/pyronaut-run", "--project-dir", str(project_dir)], executed[3])

    def test_test_resources_settings_are_propagated_to_run_via_java_tool_options(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "with-settings"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    settings_file.parent.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:18900\n"
                        "server.access.token=token-abc\n",
                        encoding="utf-8",
                    )
                return 0

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        run_invocation = next((item for item in executed if item[0][:2] == ["/tmp/pyronaut-run", "--project-dir"]), None)
        if run_invocation is None:
            self.fail("Expected delegated pyronaut-run invocation")
        _, run_env = run_invocation
        self.assertIsInstance(run_env, dict)
        assert isinstance(run_env, dict)
        options = run_env.get("JAVA_TOOL_OPTIONS", "")
        self.assertIn("-Dmicronaut.test.resources.server.uri=http://localhost:18900", options)
        self.assertIn("-Dmicronaut.test.resources.server.access.token=token-abc", options)

    def test_test_resources_start_isolated_from_micronaut_server_port_env(self):
        seen_server_port: str | None = None
        seen_server_host: str | None = None

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "isolate-env"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            def runner_with_env(command_line, env):
                nonlocal seen_server_port, seen_server_host
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    seen_server_port = os.environ.get("MICRONAUT_SERVER_PORT")
                    seen_server_host = os.environ.get("MICRONAUT_SERVER_HOST")
                    settings_file.parent.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:18900\n"
                        "server.access.token=token-abc\n",
                        encoding="utf-8",
                    )
                return 0

            previous_port = os.environ.get("MICRONAUT_SERVER_PORT")
            previous_host = os.environ.get("MICRONAUT_SERVER_HOST")
            os.environ["MICRONAUT_SERVER_PORT"] = "8181"
            os.environ["MICRONAUT_SERVER_HOST"] = "127.0.0.2"
            try:
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
            finally:
                if previous_port is None:
                    os.environ.pop("MICRONAUT_SERVER_PORT", None)
                else:
                    os.environ["MICRONAUT_SERVER_PORT"] = previous_port
                if previous_host is None:
                    os.environ.pop("MICRONAUT_SERVER_HOST", None)
                else:
                    os.environ["MICRONAUT_SERVER_HOST"] = previous_host

        self.assertEqual(0, exit_code)
        self.assertIsNone(seen_server_port)
        self.assertIsNone(seen_server_host)

    def test_run_delegation_preserves_server_port_env_without_java_home_provider(self):
        executed = []

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "preserve-run-env"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    settings_file.parent.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:18900\n"
                        "server.access.token=token-abc\n",
                        encoding="utf-8",
                    )
                return 0

            previous_port = os.environ.get("MICRONAUT_SERVER_PORT")
            os.environ["MICRONAUT_SERVER_PORT"] = "8181"
            try:
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
            finally:
                if previous_port is None:
                    os.environ.pop("MICRONAUT_SERVER_PORT", None)
                else:
                    os.environ["MICRONAUT_SERVER_PORT"] = previous_port

        self.assertEqual(0, exit_code)
        run_invocation = next((item for item in executed if item[0][:2] == ["/tmp/pyronaut-run", "--project-dir"]), None)
        if run_invocation is None:
            self.fail("Expected delegated pyronaut-run invocation")
        _, run_env = run_invocation
        self.assertIsInstance(run_env, dict)
        assert isinstance(run_env, dict)
        self.assertEqual("8181", run_env.get("MICRONAUT_SERVER_PORT"))

    def test_build_does_not_orchestrate_test_resources_lifecycle(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["build", "--project-dir", "/tmp/demo"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))

    def test_repeated_run_invocations_keep_owned_session_state_consistent(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "repeat"
            session_file = project_dir / "__pyronaut__" / "test-resources-session.json"

            for _ in range(2):
                executed = []

                def runner(command_line):
                    executed.append(command_line)
                    return 0

                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

                self.assertEqual(0, exit_code)
                self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
                self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))
                self.assertFalse(session_file.exists())

    def test_test_forwards_tests_selectors_after_preflight(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            [
                "test",
                "--project-dir",
                "/tmp/demo",
                "--tests",
                "tests/test_math.py::test_add",
                "--tests",
                "*test_add*",
            ],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self.assertEqual(
            [
                "/tmp/pyronaut-test",
                "--project-dir",
                "/tmp/demo",
                "--tests",
                "tests/test_math.py::test_add",
                "--tests",
                "*test_add*",
            ],
            executed[4],
        )
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_run_debug_vm_sets_java_tool_options_and_forwards_flag(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        exit_code = cli.run(
            ["run", "--project-dir", "/tmp/demo", "--debug-vm"],
            runner_with_env=runner_with_env,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0][0])
        self.assertIsNone(executed[0][1])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1][0])
        self.assertIsNone(executed[1][1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2][0])
        self.assertIsNone(executed[2][1])
        self._assert_test_resources_start(executed[3][0], "/tmp/demo")
        self.assertIsNone(executed[3][1])
        self.assertEqual(
            [
                "/tmp/pyronaut-run",
                "--project-dir",
                "/tmp/demo",
                "--debug-vm",
            ],
            executed[4][0],
        )
        self.assertEqual(
            "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005",
            executed[4][1]["JAVA_TOOL_OPTIONS"],
        )

    def test_test_debug_vm_sets_java_tool_options(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        exit_code = cli.run(
            ["test", "--project-dir", "/tmp/demo", "--debug-vm"],
            runner_with_env=runner_with_env,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                "/tmp/pyronaut-test",
                "--project-dir",
                "/tmp/demo",
                "--debug-vm",
            ],
            executed[4][0],
        )
        self.assertEqual(
            "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005",
            executed[4][1]["JAVA_TOOL_OPTIONS"],
        )

    def test_debug_vm_fails_fast_when_port_busy(self):
        stderr = io.StringIO()
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.bind(("127.0.0.1", 5005))
        try:
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", "/tmp/demo", "--debug-vm"],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )
        finally:
            sock.close()

        self.assertNotEqual(0, exit_code)
        self.assertIn("port 5005", stderr.getvalue().lower())

    def test_run_restarts_after_src_change_and_replays_preflight(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")

            class FakeProcess:
                def __init__(self, exit_after_polls, exit_code=0):
                    self.exit_after_polls = exit_after_polls
                    self.exit_code = exit_code
                    self.polls = 0
                    self.terminated = False

                def poll(self):
                    if self.terminated:
                        return 0
                    self.polls += 1
                    if self.polls >= self.exit_after_polls:
                        return self.exit_code
                    return None

                def terminate(self):
                    self.terminated = True

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    self.terminated = True

            first_process = FakeProcess(exit_after_polls=100)
            second_process = FakeProcess(exit_after_polls=1, exit_code=0)
            processes = [first_process, second_process]

            def process_runner(command_line, env):
                started.append((command_line, env))
                return processes.pop(0)

            def runner(command_line):
                executed.append(command_line)
                return 0

            ticks = {"count": 0}
            now = {"value": 0.0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source_file.write_text("print('v2')\n", encoding="utf-8")
                if ticks["count"] > 50:
                    raise TimeoutError("Test did not trigger restart within expected ticks")

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                process_runner=process_runner,
                resolver=self._resolver(),
                platform_name="linux",
                watch_poll_interval=0.2,
                watch_debounce_seconds=0.2,
                monotonic=monotonic,
                sleep=sleep,
            )

            resolved_project_dir = str(project_dir.resolve())
            self.assertEqual(0, exit_code)
            self.assertEqual(8, len(executed))
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual("/tmp/pyronaut-test-resources-server", executed[2][0])
            self.assertEqual("start", executed[2][1])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[3][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[3][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[4][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[4][2]))
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[5][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[5][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[6][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[6][2]))
            self.assertEqual("/tmp/pyronaut-test-resources-server", executed[7][0])
            self.assertEqual("stop", executed[7][1])
            self.assertEqual(2, len(started))
            self.assertEqual("/tmp/pyronaut-run", started[0][0][0])
            self.assertEqual("/tmp/pyronaut-run", started[1][0][0])
            self.assertTrue(first_process.terminated)

    def test_run_parallelizes_stop_and_preflight_during_restart(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-parallel"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")

            stop_entered = threading.Event()
            allow_stop_complete = threading.Event()
            preflight_entered = threading.Event()

            class FakeProcess:
                def __init__(self, exit_after_polls, exit_code=0):
                    self.exit_after_polls = exit_after_polls
                    self.exit_code = exit_code
                    self.polls = 0
                    self.terminated = False

                def poll(self):
                    if self.terminated:
                        return 0
                    self.polls += 1
                    if self.polls >= self.exit_after_polls:
                        return self.exit_code
                    return None

                def terminate(self):
                    self.terminated = True
                    stop_entered.set()

                def wait(self, timeout=None):
                    allow_stop_complete.wait(timeout=2)
                    return 0

                def kill(self):
                    self.terminated = True

            first_process = FakeProcess(exit_after_polls=100)
            second_process = FakeProcess(exit_after_polls=1, exit_code=0)
            processes = [first_process, second_process]

            def process_runner(command_line, env):
                started.append((command_line, env))
                return processes.pop(0)

            def runner(command_line):
                executed.append(command_line)
                if "pyronaut-install" in command_line[0] and len(executed) > 2:
                    preflight_entered.set()
                    self.assertTrue(stop_entered.wait(timeout=2))
                    allow_stop_complete.set()
                return 0

            ticks = {"count": 0}
            now = {"value": 0.0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source_file.write_text("print('v2')\n", encoding="utf-8")

            try:
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    process_runner=process_runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    watch_poll_interval=0.05,
                    watch_debounce_seconds=0.05,
                    monotonic=monotonic,
                    sleep=sleep,
                )
            except SystemExit as exc:
                exit_code = int(exc.code) if exc.code is not None else 0

            self.assertEqual(0, exit_code)
            self.assertTrue(preflight_entered.is_set())
            self.assertEqual(2, len(started))

    def test_run_restart_aborts_when_refresh_preflight_fails(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-fail-refresh"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")

            class FakeProcess:
                def __init__(self):
                    self.terminated = False
                    self.polls = 0

                def poll(self):
                    self.polls += 1
                    return 0 if self.terminated else None

                def terminate(self):
                    self.terminated = True

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    self.terminated = True

            processes = [FakeProcess(), FakeProcess()]

            def process_runner(command_line, env):
                started.append((command_line, env))
                return processes.pop(0)

            def runner(command_line):
                executed.append(command_line)
                if len(executed) > 2 and "pyronaut-processor" in command_line[0]:
                    return 7
                return 0

            ticks = {"count": 0}
            now = {"value": 0.0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source_file.write_text("print('v2')\n", encoding="utf-8")

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                process_runner=process_runner,
                resolver=self._resolver(),
                platform_name="linux",
                watch_poll_interval=0.05,
                watch_debounce_seconds=0.05,
                monotonic=monotonic,
                sleep=sleep,
            )

            self.assertEqual(7, exit_code)
            self.assertEqual(1, len(started))

    def test_run_restart_aborts_when_stop_fails(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-stop-fail"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")

            class StopFailProcess:
                def __init__(self, exit_after_polls=100):
                    self.exit_after_polls = exit_after_polls
                    self.polls = 0

                def poll(self):
                    self.polls += 1
                    if self.polls >= self.exit_after_polls:
                        return 0
                    return None

                def terminate(self):
                    raise TimeoutError("terminate timeout")

                def wait(self, timeout=None):
                    raise TimeoutError("wait timeout")

                def kill(self):
                    raise TimeoutError("kill timeout")

            def process_runner(command_line, env):
                started.append((command_line, env))
                return StopFailProcess()

            def runner(command_line):
                executed.append(command_line)
                return 0

            now = {"value": 0.0}
            ticks = {"count": 0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source_file.write_text("print('v2')\n", encoding="utf-8")

            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    process_runner=process_runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    watch_poll_interval=0.05,
                    watch_debounce_seconds=0.05,
                    monotonic=monotonic,
                    sleep=sleep,
                )

            self.assertEqual(cli.INTERNAL_ERROR, exit_code)
            self.assertEqual(1, len(started))
            self.assertIn("Failed to stop running process for restart", stderr.getvalue())

    def test_run_restart_aborts_when_refresh_fails_after_initial_start(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-refresh-fail-after-start"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")

            class FakeProcess:
                def __init__(self):
                    self.terminated = False
                    self.polls = 0

                def poll(self):
                    self.polls += 1
                    return 0 if self.terminated else None

                def terminate(self):
                    self.terminated = True

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    self.terminated = True

            processes = [FakeProcess(), FakeProcess()]
            processor_calls = {"count": 0}

            def process_runner(command_line, env):
                started.append((command_line, env))
                return processes.pop(0)

            def runner(command_line):
                executed.append(command_line)
                if "pyronaut-processor" in command_line[0]:
                    processor_calls["count"] += 1
                    if processor_calls["count"] >= 2:
                        return 7
                return 0

            now = {"value": 0.0}
            ticks = {"count": 0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source_file.write_text("print('v2')\n", encoding="utf-8")

            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    process_runner=process_runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    watch_poll_interval=0.05,
                    watch_debounce_seconds=0.05,
                    monotonic=monotonic,
                    sleep=sleep,
                )

            self.assertEqual(7, exit_code)
            self.assertEqual(1, len(started))
            self.assertIn("Failed to refresh artifacts for restart (exit code 7)", stderr.getvalue())

    def test_run_ignores_generated_output_changes_for_restart(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-ignore"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            (src_dir / "controller.py").write_text("print('v1')\n", encoding="utf-8")
            reports_dir = project_dir / "__pyronaut__" / "reports" / "tests"
            reports_dir.mkdir(parents=True, exist_ok=True)

            class FakeProcess:
                def __init__(self, exit_after_polls, exit_code=0):
                    self.exit_after_polls = exit_after_polls
                    self.exit_code = exit_code
                    self.polls = 0

                def poll(self):
                    self.polls += 1
                    if self.polls >= self.exit_after_polls:
                        return self.exit_code
                    return None

                def terminate(self):
                    return None

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    return None

            process = FakeProcess(exit_after_polls=3, exit_code=0)

            def process_runner(command_line, env):
                started.append((command_line, env))
                return process

            def runner(command_line):
                executed.append(command_line)
                return 0

            now = {"value": 0.0}
            ticks = {"count": 0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    (reports_dir / "junit.xml").write_text("<testsuite/>\n", encoding="utf-8")

            exit_code = cli.run(
                ["run", "--project-dir", str(project_dir)],
                runner=runner,
                process_runner=process_runner,
                resolver=self._resolver(),
                platform_name="linux",
                watch_poll_interval=0.2,
                watch_debounce_seconds=0.2,
                monotonic=monotonic,
                sleep=sleep,
            )

            resolved_project_dir = str(project_dir.resolve())
            self.assertEqual(0, exit_code)
            self.assertEqual(1, len(started))
            self.assertEqual(4, len(executed))
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual(["/tmp/pyronaut-test-resources-server", "start", "--project-dir"], executed[2][:3])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[2][3]))
            self.assertEqual(ANY, executed[2][5])
            self.assertEqual(["/tmp/pyronaut-test-resources-server", "stop", "--project-dir"], executed[3][:3])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[3][3]))
            self.assertEqual(ANY, executed[3][5])

    def test_run_uses_provided_java_home_for_run_delegation(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        exit_code = cli.run(
            ["run", "--project-dir", "/tmp/demo"],
            runner_with_env=runner_with_env,
            resolver=self._resolver(),
            platform_name="linux",
            java_home_provider=lambda: "/tmp/graalvm-jdk-25",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0][0])
        self.assertIsNone(executed[0][1])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1][0])
        self.assertIsNone(executed[1][1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2][0])
        self.assertIsNone(executed[2][1])
        self._assert_test_resources_start(executed[3][0], "/tmp/demo")
        self.assertIsNone(executed[3][1])
        self.assertEqual(["/tmp/pyronaut-run", "--project-dir", "/tmp/demo"], executed[4][0])
        self.assertEqual("/tmp/graalvm-jdk-25", executed[4][1]["JAVA_HOME"])
        self.assertTrue(executed[4][1]["PATH"].startswith("/tmp/graalvm-jdk-25/bin"))

    def test_run_fails_when_java_home_provider_cannot_provision(self):
        executed = []
        stderr = io.StringIO()

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["run", "--project-dir", "/tmp/demo"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: None,
            )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("compatible GraalVM JDK", stderr.getvalue())
        self.assertEqual(5, len(executed))

    def test_build_defaults_to_jvm_wheel_command(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-demo"
            project_dir.mkdir(parents=True, exist_ok=True)

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["build", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(2, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir.resolve()), "--scenario", "production"], executed[0][0])
        self.assertEqual("-m", executed[1][0][1])
        self.assertEqual("pip", executed[1][0][2])
        self.assertEqual("wheel", executed[1][0][3])
        self.assertIn("--wheel-dir", executed[1][0])
        self.assertIsNone(executed[1][1])
        self.assertIn("Wheel build complete", stdout.getvalue())
        self.assertIn("Install with:", stdout.getvalue())

    def test_build_help_prints_usage_without_validation_or_build_execution(self):
        executed = []
        stdout = io.StringIO()

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with redirect_stdout(stdout):
            exit_code = cli.run(
                ["build", "--help"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual([], executed)
        self.assertIn("Usage: pyronaut build", stdout.getvalue())

    def test_build_no_cache_forwards_to_validation_step(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-no-cache"
            project_dir.mkdir(parents=True, exist_ok=True)

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir), "--no-cache"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(2, len(executed))
        self.assertEqual(
            [
                "/tmp/pyronaut-validate-config",
                "--project-dir",
                str(project_dir.resolve()),
                "--scenario",
                "production",
                "--no-cache",
            ],
            executed[0][0],
        )

    def test_build_native_runs_preflight_then_native_delegate_with_java_home(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-demo"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["build", "--native", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(0, exit_code)
        resolved_project_dir = str(project_dir.resolve())
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "production"], executed[0][0])
        self.assertIsNone(executed[0][1])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", resolved_project_dir], executed[1][0])
        self.assertIsNone(executed[1][1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir], executed[2][0])
        self.assertIsNone(executed[2][1])
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertIn("--main-class", executed[3][0])
        self.assertIn("--output", executed[3][0])
        self.assertEqual("/tmp/graalvm-jdk-25", executed[3][1]["JAVA_HOME"])
        self.assertIn("Native build complete", stdout.getvalue())

    def test_stop_managed_process_kills_when_terminate_times_out(self):
        class HungProcess:
            def __init__(self):
                self.terminate_called = False
                self.kill_called = False
                self.wait_calls = 0

            def terminate(self):
                self.terminate_called = True

            def wait(self, timeout=None):
                self.wait_calls += 1
                if self.wait_calls == 1:
                    raise TimeoutError("process did not stop")
                return 0

            def kill(self):
                self.kill_called = True

        process = HungProcess()
        stopped = cli._stop_managed_process(process)
        self.assertTrue(stopped)
        self.assertTrue(process.terminate_called)
        self.assertTrue(process.kill_called)

    def test_stop_managed_process_returns_false_when_kill_fails(self):
        class BrokenProcess:
            def terminate(self):
                raise RuntimeError("terminate failure")

            def wait(self, timeout=None):
                raise RuntimeError("wait failure")

            def kill(self):
                raise RuntimeError("kill failure")

        self.assertFalse(cli._stop_managed_process(BrokenProcess()))

    def test_build_mode_from_pyproject_defaults_to_native(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-default"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.build]\nmode = \"native\"\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])

    def test_build_mode_flag_with_separate_value_uses_native(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-flag"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            exit_code = cli.run(
                ["build", "--mode", "native", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])

    def test_build_native_returns_precondition_when_native_delegate_missing(self):
        stderr = io.StringIO()

        def resolver(command_name):
            if command_name == "pyronaut-native-build":
                return None
            return f"/tmp/{command_name}"

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-missing-delegate"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["build", "--native", "--project-dir", str(project_dir)],
                    runner_with_env=lambda _cmd, _env: 0,
                    resolver=resolver,
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing delegated executable: pyronaut-native-build", stderr.getvalue())

    def test_build_native_forwards_verbose_to_native_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-verbose"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            exit_code = cli.run(
                ["build", "--native", "--verbose", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertIn("--verbose", executed[3][0])

    def test_build_native_forwards_unconsumed_native_image_args_to_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-passthrough"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            exit_code = cli.run(
                [
                    "build",
                    "--native",
                    "--project-dir",
                    str(project_dir),
                    "--trace-object-instantiation=ch.qos.logback.classic.Logger",
                    "--initialize-at-run-time",
                    "io.netty.util.ResourceLeakDetector",
                ],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertIn("--trace-object-instantiation=ch.qos.logback.classic.Logger", executed[3][0])
        self.assertIn("--initialize-at-run-time", executed[3][0])
        self.assertIn("io.netty.util.ResourceLeakDetector", executed[3][0])

    def test_build_native_forwards_args_after_separator_to_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-separator"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            exit_code = cli.run(
                [
                    "build",
                    "--native",
                    "--project-dir",
                    str(project_dir),
                    "--",
                    "--trace-object-instantiation=ch.qos.logback.classic.Logger",
                ],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertIn("--trace-object-instantiation=ch.qos.logback.classic.Logger", executed[3][0])

    def test_build_rejects_invalid_mode_flag_value(self):
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["build", "--mode=fast", "--project-dir", "/tmp/demo"],
                runner=self._runner_ok(),
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("Invalid value for --mode", stderr.getvalue())

    def test_build_rejects_invalid_mode_in_pyproject(self):
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "invalid-mode"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.build]\nmode = \"fast\"\n",
                encoding="utf-8",
            )

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["build", "--project-dir", str(project_dir)],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("Invalid build mode in pyproject.toml", stderr.getvalue())

    def test_build_native_requires_processed_classes(self):
        stderr = io.StringIO()

        def runner_with_env(command_line, env):
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-missing-classes"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["build", "--native", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing processed classes directory", stderr.getvalue())

    def test_ensure_graalvm_java_home_prefers_sdkman_before_download(self):
        call_order = []
        with tempfile.TemporaryDirectory() as temp_dir:
            expected = Path(temp_dir) / "sdkman-home"
        with patch.object(cli, "_provisioned_graalvm_home", None, create=True), \
                patch.object(cli, "_read_env", lambda name: None, create=True), \
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root: call_order.append("sdkman") or expected, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root: call_order.append("download") or None, create=True):
                resolved = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), resolved)
        self.assertEqual(["sdkman"], call_order)

    def test_ensure_graalvm_java_home_falls_back_to_download(self):
        call_order = []
        with tempfile.TemporaryDirectory() as temp_dir:
            expected = Path(temp_dir) / "download-home"
        with patch.object(cli, "_provisioned_graalvm_home", None, create=True), \
                patch.object(cli, "_read_env", lambda name: None, create=True), \
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root: call_order.append("sdkman") or None, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root: call_order.append("download") or expected, create=True):
                resolved = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), resolved)
        self.assertEqual(["sdkman", "download"], call_order)

    def test_ensure_graalvm_java_home_is_idempotent_after_first_resolution(self):
        counts = {"sdkman": 0, "download": 0}
        with tempfile.TemporaryDirectory() as temp_dir:
            expected = Path(temp_dir) / "download-home"
        with patch.object(cli, "_provisioned_graalvm_home", None, create=True), \
                patch.object(cli, "_read_env", lambda name: None, create=True), \
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root: counts.__setitem__("sdkman", counts["sdkman"] + 1) or None, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root: counts.__setitem__("download", counts["download"] + 1) or expected, create=True):
                first = cli._ensure_graalvm_java_home()
                second = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), first)
        self.assertEqual(str(expected), second)
        self.assertEqual(1, counts["sdkman"])
        self.assertEqual(1, counts["download"])

    def test_tui_smoke_delegates_install_process_run(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            expected_project_dir = project_dir.resolve()
            exit_code = cli.run(
                ["--tui", "--smoke", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                ["/tmp/pyronaut-install", "--project-dir", str(expected_project_dir)],
                ["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir)],
            ],
            executed,
        )

    def test_tui_interactive_delegates_to_tamboui_command(self):
        executed = []
        settings_file: Path | None = None

        def runner_with_env(command_line, env):
            nonlocal settings_file
            executed.append((command_line, env))
            if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                assert settings_file is not None
                settings_file.parent.mkdir(parents=True, exist_ok=True)
                settings_file.write_text(
                    "server.uri=http\\://localhost\\:18900\n"
                    "server.access.token=token-abc\n",
                    encoding="utf-8",
                )
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            expected_project_dir = project_dir.resolve()
            settings_file = expected_project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            exit_code = cli.run(
                ["--tui", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self._assert_test_resources_start(executed[0][0], str(expected_project_dir))
        self.assertEqual(
            [
                "/tmp/pyronaut-tui",
                "delegating-tui",
                "--project-dir",
                str(expected_project_dir),
                "--mode",
                "run",
                "--report-dir",
                str(expected_project_dir / "__pyronaut__" / "reports" / "tests"),
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertIn("-Dmicronaut.test.resources.server.uri=http://localhost:18900", executed[1][1].get("JAVA_TOOL_OPTIONS", ""))

    def test_tui_interactive_test_mode_delegates_to_tamboui_command(self):
        executed = []
        settings_file: Path | None = None

        def runner_with_env(command_line, env):
            nonlocal settings_file
            executed.append((command_line, env))
            if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                assert settings_file is not None
                settings_file.parent.mkdir(parents=True, exist_ok=True)
                settings_file.write_text(
                    "server.uri=http\\://localhost\\:18900\n"
                    "server.access.token=token-abc\n",
                    encoding="utf-8",
                )
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            reports_dir = project_dir / "custom-reports"
            expected_project_dir = project_dir.resolve()
            expected_reports_dir = reports_dir.resolve()
            settings_file = expected_project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            exit_code = cli.run(
                [
                    "--tui",
                    "--test",
                    "--project-dir",
                    str(project_dir),
                    "--report-dir",
                    str(reports_dir),
                ],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self._assert_test_resources_start(executed[0][0], str(expected_project_dir))
        self.assertEqual(
            [
                "/tmp/pyronaut-tui",
                "delegating-tui",
                "--project-dir",
                str(expected_project_dir),
                "--mode",
                "test",
                "--report-dir",
                str(expected_reports_dir),
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertIn("-Dmicronaut.test.resources.server.uri=http://localhost:18900", executed[1][1].get("JAVA_TOOL_OPTIONS", ""))

    def test_tui_interactive_without_project_dir_defaults_to_cwd(self):
        executed = []
        settings_file: Path | None = None

        def runner_with_env(command_line, env):
            nonlocal settings_file
            executed.append((command_line, env))
            if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                assert settings_file is not None
                settings_file.parent.mkdir(parents=True, exist_ok=True)
                settings_file.write_text(
                    "server.uri=http\\://localhost\\:18900\n"
                    "server.access.token=token-abc\n",
                    encoding="utf-8",
                )
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            expected_project_dir = project_dir.resolve()
            settings_file = expected_project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            previous_cwd = Path.cwd()
            try:
                os.chdir(project_dir)
                exit_code = cli.run(
                    ["--tui"],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
            finally:
                os.chdir(previous_cwd)

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self._assert_test_resources_start(executed[0][0], str(expected_project_dir))
        self.assertEqual(
            [
                "/tmp/pyronaut-tui",
                "delegating-tui",
                "--project-dir",
                str(expected_project_dir),
                "--mode",
                "run",
                "--report-dir",
                str(expected_project_dir / "__pyronaut__" / "reports" / "tests"),
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertIn("-Dmicronaut.test.resources.server.uri=http://localhost:18900", executed[1][1].get("JAVA_TOOL_OPTIONS", ""))

    def test_tui_help_delegates_to_tui_executable_help(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["--tui", "--help"],
            runner_with_env=runner_with_env,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual([["/tmp/pyronaut-tui", "--help"]], executed)

    def test_tui_interactive_returns_precondition_when_tui_executable_missing(self):
        executed = []
        stderr = io.StringIO()

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        def resolver(command_name):
            if command_name == "pyronaut-tui":
                return None
            return f"/tmp/{command_name}"

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=resolver,
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertEqual([], executed)
        self.assertIn("Missing delegated executable: pyronaut-tui", stderr.getvalue())

    def test_tui_smoke_test_mode_delegates_test_and_summarizes_reports(self):
        executed = []
        stderr = io.StringIO()

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            reports_dir = project_dir / "__pyronaut__" / "reports" / "tests"
            reports_dir.mkdir(parents=True, exist_ok=True)
            expected_project_dir = project_dir.resolve()
            (reports_dir / "junit.xml").write_text(
                "<testsuite tests='2' failures='1' skipped='0'>"
                "<testcase classname='t' name='a'/><testcase classname='t' name='b'><failure/></testcase>"
                "</testsuite>",
                encoding="utf-8",
            )
            (reports_dir / ".pyronaut-last-nodeid.txt").write_text("tests/test_t.py::test_b\n", encoding="utf-8")

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    [
                        "--tui",
                        "--smoke",
                        "--test",
                        "--project-dir",
                        str(project_dir),
                        "--report-dir",
                        str(reports_dir),
                    ],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(expected_project_dir), "--scenario", "test"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(expected_project_dir)], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir)], executed[2])
        self._assert_test_resources_start(executed[3], str(expected_project_dir))
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", str(expected_project_dir)], executed[4])
        self._assert_test_resources_stop(executed[5], str(expected_project_dir))
        self.assertIn("tests: 1 passed, 1 failed, 0 skipped", stderr.getvalue())

    def test_run_validates_run_scenario_before_preflight(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["run", "--project-dir", "/tmp/demo"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-run", "--project-dir", "/tmp/demo"], executed[4])
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_validates_test_scenario_before_preflight(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["test", "--project-dir", "/tmp/demo"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(6, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-test", "--project-dir", "/tmp/demo"], executed[4])
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_build_validates_production_scenario(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-app"
            project_dir.mkdir(parents=True, exist_ok=True)
            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertGreaterEqual(len(executed), 2)
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir.resolve()), "--scenario", "production"],
            executed[0],
        )

    def test_no_validate_skips_lifecycle_validation(self):
        executed = []
        stderr = io.StringIO()

        def runner(command_line):
            executed.append(command_line)
            return 0

        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["run", "--project-dir", "/tmp/demo", "--no-validate"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertFalse(any("pyronaut-validate-config" in cmd[0] for cmd in executed))
        self.assertIn("[validation] skipped (--no-validate)", stderr.getvalue())

    def test_test_resources_disabled_env_skips_server_orchestration(self):
        executed = []
        stderr = io.StringIO()

        def runner(command_line):
            executed.append(command_line)
            return 0

        original = os.environ.get("PYRONAUT_TEST_RESOURCES_DISABLED")
        os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = "true"
        try:
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", "/tmp/demo"],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
        finally:
            if original is None:
                os.environ.pop("PYRONAUT_TEST_RESOURCES_DISABLED", None)
            else:
                os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = original

        self.assertEqual(0, exit_code)
        self.assertEqual(4, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2])
        self.assertEqual(["/tmp/pyronaut-run", "--project-dir", "/tmp/demo"], executed[3])
        self.assertFalse(any("pyronaut-test-resources-server" in cmd[0] for cmd in executed))
        self.assertIn("[test-resources] skipped (disabled via PYRONAUT_TEST_RESOURCES_DISABLED)", stderr.getvalue())

    def test_validate_config_delegates_to_validate_config_executable(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"]],
            executed,
        )

    def test_validate_config_strips_stale_test_resources_java_tool_options(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        original = os.environ.get("JAVA_TOOL_OPTIONS")
        os.environ["JAVA_TOOL_OPTIONS"] = (
            "-Dmicronaut.test.resources.server.uri=http://localhost:61247 "
            "-Dmicronaut.test.resources.server.access.token=stale-token "
            "-Duser.timezone=UTC"
        )
        try:
            def runner(command_line, env=None):
                executed.append((command_line, env))
                return 0

            exit_code = cli.run(
                ["validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"],
                runner_with_env=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )
        finally:
            if original is None:
                os.environ.pop("JAVA_TOOL_OPTIONS", None)
            else:
                os.environ["JAVA_TOOL_OPTIONS"] = original

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"], executed[0][0])
        run_env = executed[0][1] or {}
        options = run_env.get("JAVA_TOOL_OPTIONS", "")
        self.assertNotIn("micronaut.test.resources.server.uri", options)
        self.assertNotIn("micronaut.test.resources.server.access.token", options)

    def test_validate_config_returns_precondition_when_executable_missing(self):
        stderr = io.StringIO()

        def resolver(command_name):
            if command_name == "pyronaut-validate-config":
                return None
            return f"/tmp/{command_name}"

        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["validate-config", "--project-dir", "/tmp/demo"],
                runner=self._runner_ok(),
                resolver=resolver,
                platform_name="linux",
            )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing delegated executable: pyronaut-validate-config", stderr.getvalue())

    def test_test_resources_server_delegates_to_executable(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["test-resources-server", "start", "--project-dir", "/tmp/demo"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                ["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"],
                ["/tmp/pyronaut-test-resources-server", "start", "--project-dir", "/tmp/demo"],
            ],
            executed,
        )

    def test_test_resources_server_returns_precondition_when_executable_missing(self):
        stderr = io.StringIO()

        def resolver(command_name):
            if command_name == "pyronaut-test-resources-server":
                return None
            return f"/tmp/{command_name}"

        with redirect_stderr(stderr):
            exit_code = cli.run(
                ["test-resources-server", "status", "--project-dir", "/tmp/demo"],
                runner=self._runner_ok(),
                resolver=resolver,
                platform_name="linux",
            )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing delegated executable: pyronaut-test-resources-server", stderr.getvalue())

    @staticmethod
    def _resolver():
        def resolve(command_name):
            return f"/tmp/{command_name}"

        return resolve

    @staticmethod
    def _runner_ok():
        def runner(_command_line):
            return 0

        return runner


if __name__ == "__main__":
    unittest.main()
