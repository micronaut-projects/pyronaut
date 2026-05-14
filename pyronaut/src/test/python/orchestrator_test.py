import importlib.util
import io
import os
import subprocess
import tempfile
import time
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
    _RUN_MAIN = "io.micronaut.pyronaut.run.PyronautRunMain"
    _TEST_MAIN = "io.micronaut.pyronaut.test.PyronautTestMain"
    _DELEGATE_JVM_FLAGS = [
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
    ]
    _JDWP_FLAG = "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"

    def setUp(self):
        self._previous_run_jar = os.environ.get("PYRONAUT_RUN_JAR")
        self._previous_test_jar = os.environ.get("PYRONAUT_TEST_JAR")
        os.environ["PYRONAUT_RUN_JAR"] = "/tmp/pyronaut-run.jar"
        os.environ["PYRONAUT_TEST_JAR"] = "/tmp/pyronaut-test.jar"

    def tearDown(self):
        if self._previous_run_jar is None:
            os.environ.pop("PYRONAUT_RUN_JAR", None)
        else:
            os.environ["PYRONAUT_RUN_JAR"] = self._previous_run_jar
        if self._previous_test_jar is None:
            os.environ.pop("PYRONAUT_TEST_JAR", None)
        else:
            os.environ["PYRONAUT_TEST_JAR"] = self._previous_test_jar

    @staticmethod
    def _normalized_project_dir(value: str) -> str:
        return str(Path(value).resolve())

    def _assert_java_delegate(self, command_line, main_class: str, project_dir: str, manifest: str, extra_args: list[str] | None = None) -> None:
        self.assertEqual("/tmp/java-home/bin/java", command_line[0])
        cp_index = command_line.index("-cp")
        self.assertEqual(self._DELEGATE_JVM_FLAGS, command_line[1:3])
        classpath = command_line[cp_index + 1].split(os.pathsep)
        project_root = self._normalized_project_dir(project_dir)
        self.assertIn(str(Path(project_root) / "__pyronaut__" / manifest), "\n".join(classpath) if False else "")
        self.assertIn(str(Path(project_root) / "config"), classpath if Path(project_root, "config").is_dir() else classpath)
        self.assertEqual(main_class, command_line[cp_index + 2])
        self.assertEqual(["--project-dir", project_dir, *(extra_args or [])], command_line[cp_index + 3 :])

    def _assert_run_delegate(
        self,
        command_line,
        project_dir: str,
        extra_args: list[str] | None = None,
        *,
        resources_dir: str = "config",
    ) -> None:
        self.assertTrue(command_line[0].endswith("/bin/java") or command_line[0] == "java")
        cp_index = command_line.index("-cp")
        self.assertEqual(self._DELEGATE_JVM_FLAGS, command_line[1:3])
        classpath = command_line[cp_index + 1].split(os.pathsep)
        self.assertIn("/tmp/pyronaut-run.jar", classpath)
        project_root = self._normalized_project_dir(project_dir)
        self.assertNotIn(str(Path(project_root) / "__pyronaut__" / "classes"), classpath)
        if (Path(project_root) / "__pyronaut__" / "resolved-development-runtime-dependencies").exists():
            self.assertIn("/tmp/runtime-dev.jar", classpath)
            self.assertNotIn("/tmp/runtime.jar", classpath)
        else:
            self.assertIn("/tmp/runtime.jar", classpath)
        self.assertNotIn(str(Path(project_root) / resources_dir), classpath)
        self.assertEqual(self._RUN_MAIN, command_line[cp_index + 2])
        self.assertEqual(["--project-dir", project_dir, *(extra_args or [])], command_line[cp_index + 3 :])

    def _assert_test_delegate(
        self,
        command_line,
        project_dir: str,
        extra_args: list[str] | None = None,
        *,
        resources_dir: str = "config",
        test_resources_dir: str = "tests-config",
    ) -> None:
        self.assertTrue(command_line[0].endswith("/bin/java") or command_line[0] == "java")
        cp_index = command_line.index("-cp")
        self.assertEqual(self._DELEGATE_JVM_FLAGS, command_line[1:3])
        classpath = command_line[cp_index + 1].split(os.pathsep)
        project_root = self._normalized_project_dir(project_dir)
        self.assertIn("/tmp/test.jar", classpath)
        self.assertIn("/tmp/runtime.jar", classpath)
        self.assertIn("/tmp/build.jar", classpath)
        self.assertIn("/tmp/pyronaut-test.jar", classpath)
        self.assertTrue(
            str(Path(project_root) / "__pyronaut__" / "test-classes") in classpath
            or str(Path(project_root) / "__pyronaut__" / "classes") in classpath
        )
        resources_path = str(Path(project_root) / resources_dir)
        test_resources_path = str(Path(project_root) / test_resources_dir)
        if Path(project_root, resources_dir).is_dir():
            self.assertIn(resources_path, classpath)
        else:
            self.assertNotIn(resources_path, classpath)
        if Path(project_root, test_resources_dir).is_dir():
            self.assertIn(test_resources_path, classpath)
        else:
            self.assertNotIn(test_resources_path, classpath)
        self.assertEqual(self._TEST_MAIN, command_line[cp_index + 2])
        self.assertEqual(["--project-dir", project_dir, *(extra_args or [])], command_line[cp_index + 3 :])

    @staticmethod
    def _extract_system_properties(command_line) -> dict[str, str | None]:
        cp_index = command_line.index("-cp")
        properties: dict[str, str | None] = {}
        for token in command_line[1:cp_index]:
            if not token.startswith("-D"):
                continue
            name, has_value, value = token[2:].partition("=")
            properties[name] = value if has_value else None
        return properties

    @staticmethod
    def _write_manifests(
        project_dir: Path,
        *,
        runtime: bool = True,
        development_runtime: bool = False,
        test: bool = True,
        build: bool = True,
    ) -> None:
        cache_dir = project_dir / "__pyronaut__"
        cache_dir.mkdir(parents=True, exist_ok=True)
        if runtime:
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
        if development_runtime:
            (cache_dir / "resolved-development-runtime-dependencies").write_text("/tmp/runtime-dev.jar\n", encoding="utf-8")
        if test:
            (cache_dir / "resolved-test-dependencies").write_text("/tmp/test.jar\n", encoding="utf-8")
        if build:
            (cache_dir / "resolved-build-dependencies").write_text("/tmp/build.jar\n", encoding="utf-8")

    @staticmethod
    def _write_fake_install_dist(root_dir: Path, command_name: str) -> str:
        install_root = root_dir / f"{command_name}-install"
        bin_dir = install_root / "bin"
        lib_dir = install_root / "lib"
        bin_dir.mkdir(parents=True, exist_ok=True)
        lib_dir.mkdir(parents=True, exist_ok=True)
        launcher = bin_dir / command_name
        launcher.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        launcher.chmod(0o755)
        (lib_dir / f"{command_name}.jar").write_text("", encoding="utf-8")
        return str(launcher)

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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            def runner(command_line, env=None):
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
            self._assert_run_delegate(executed[4], str(project_dir), ["--main-class", "example.Main"])
            self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_run_skips_preflight_when_artifacts_already_exist(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "ready"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = project_dir / "__pyronaut__" / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            def runner(command_line, env=None):
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
            self._assert_run_delegate(executed[4], str(project_dir), ["--main-class", "example.Main"])
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

    def test_install_prefers_bundled_native_executable_when_available(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_install = Path(temp_dir) / "pyronaut-install"
            native_install.write_text("", encoding="utf-8")
            native_install.chmod(0o755)

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_install if command_name == "pyronaut-install" else None):
                exit_code = cli.run(
                    ["install", "--project-dir", "/tmp/demo"],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([[str(native_install), "--project-dir", "/tmp/demo"]], executed)

    def test_install_uses_jvm_delegate_when_no_bundled_native_executable(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with patch.object(cli, "_bundled_native_executable", return_value=None), \
                patch.object(cli.shutil, "which", return_value="/tmp/pyronaut-install-native") as which:
            exit_code = cli.run(
                ["install", "--project-dir", "/tmp/demo"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual([["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"]], executed)
        which.assert_not_called()

    def test_validate_config_prefers_bundled_native_executable_when_available(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_validate_config = Path(temp_dir) / "pyronaut-validate-config"
            native_validate_config.write_text("", encoding="utf-8")
            native_validate_config.chmod(0o755)

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: native_validate_config if command_name == "pyronaut-validate-config" else None,
            ):
                exit_code = cli.run(
                    ["validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [[str(native_validate_config), "--project-dir", "/tmp/demo", "--scenario", "production"]],
            executed,
        )

    def test_test_resources_server_prefers_bundled_native_executable_when_available(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_test_resources_server = Path(temp_dir) / "pyronaut-test-resources-server"
            native_test_resources_server.write_text("", encoding="utf-8")
            native_test_resources_server.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            session = cli._OwnedTestResourcesSession(project_dir=project_dir, owner_command="pyronaut run")
            with patch.object(
                cli,
                "_resolve_native_preferred_executable",
                side_effect=lambda command_name, resolver: str(native_test_resources_server) if command_name == "pyronaut-test-resources-server" else resolver(command_name),
            ):
                exit_code = session._delegate_test_resources_server(
                    ["start", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [str(native_test_resources_server), "start", "--project-dir", str(project_dir)],
            executed[0],
        )

    def test_process_uses_bundled_native_executable_when_configured(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-processor"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.processor]\nmode = \"native\"\n",
                encoding="utf-8",
            )
            native_processor = Path(temp_dir) / "pyronaut-processor"
            native_processor.write_text("", encoding="utf-8")
            native_processor.chmod(0o755)

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_processor if command_name == "pyronaut-processor" else None):
                exit_code = cli.run(
                    ["process", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([[str(native_processor), "--project-dir", str(project_dir)]], executed)

    def test_process_native_mode_fails_when_native_executable_missing(self):
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing-native-processor"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.processor]\nmode = \"native\"\n",
                encoding="utf-8",
            )

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["process", "--project-dir", str(project_dir)],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing native delegated executable for pyronaut-processor", stderr.getvalue())

    def test_test_uses_bundled_native_executable_when_configured(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-test"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.test]\nmode = \"native\"\n",
                encoding="utf-8",
            )
            native_test = Path(temp_dir) / "pyronaut-test"
            native_test.write_text("", encoding="utf-8")
            native_test.chmod(0o755)

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_test if command_name == "pyronaut-test" else None):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([str(native_test), "--project-dir", str(project_dir)], executed[4])

    def test_test_debug_vm_forces_jit_mode_even_when_native_is_configured(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-test-debug"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.test]\nmode = \"native\"\n",
                encoding="utf-8",
            )
            native_test = Path(temp_dir) / "pyronaut-test"
            native_test.write_text("", encoding="utf-8")
            native_test.chmod(0o755)

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_test if command_name == "pyronaut-test" else None), \
                    patch.object(cli, "_check_port_available", return_value=(True, None)):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir), "--debug-vm"],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self._assert_test_delegate(executed[4], str(project_dir), ["--debug-vm"])

    def test_test_native_mode_fails_when_native_executable_missing(self):
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing-native-test"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.test]\nmode = \"native\"\n",
                encoding="utf-8",
            )

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir)],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing native delegated executable for pyronaut-test", stderr.getvalue())

    def test_test_performs_process_when_test_classes_missing(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing-test-classes"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
        self._assert_test_delegate(executed[4], str(project_dir))
        self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_stop_skipped_when_session_owner_mismatch(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "mismatch"
            session_dir = project_dir / "__pyronaut__"
            (session_dir / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            self._write_manifests(project_dir)

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
        self._assert_test_delegate(executed[4], str(project_dir))
        self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_run_forwards_no_cache_to_install_and_processor(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

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
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--no-cache"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--no-cache"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self._assert_run_delegate(executed[4], "/tmp/demo")
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_forwards_no_cache_to_install_and_processor(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

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
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--no-cache"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--no-cache"], executed[2])
        self._assert_test_resources_start(executed[3], "/tmp/demo")
        self._assert_test_delegate(executed[4], "/tmp/demo")
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_starts_test_resources_before_validation_and_reuses_fresh_env(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
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
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"],
            executed[0][0],
        )
        self.assertEqual(
            ["/tmp/pyronaut-install", "--project-dir", str(project_dir)],
            executed[1][0],
        )
        self.assertEqual(
            ["/tmp/pyronaut-processor", "--project-dir", str(project_dir)],
            executed[2][0],
        )
        self._assert_test_resources_start(executed[3][0], str(project_dir))
        validate_env = executed[4][1] or {}
        self.assertEqual("http://localhost:61234", validate_env.get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))
        self._assert_test_delegate(executed[4][0], str(project_dir))
        self._assert_test_resources_stop(executed[5][0], str(project_dir))

    def test_shared_server_mode_does_not_stop_on_session_exit(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "shared"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            settings_file.write_text(
                "server.uri=http\\://127.0.0.1\\:61234\n"
                "server.access.token=external-token\n",
                encoding="utf-8",
            )

            def runner(command_line):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_test_resources_server_available", return_value=True):
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
        self._assert_run_delegate(executed[3], str(project_dir))

    def test_stale_external_test_resources_settings_start_owned_server(self):
        executed = []
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "stale-external"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            settings_file.parent.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            settings_file.write_text(
                "server.uri=http\\://127.0.0.1\\:61234\n"
                "server.access.token=external-token\n",
                encoding="utf-8",
            )

            def runner(command_line):
                executed.append(command_line)
                return 0

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertIn("stale external settings detected", stderr.getvalue())
        self._assert_test_resources_start(executed[3], str(project_dir))
        self._assert_run_delegate(executed[4], str(project_dir))
        self._assert_test_resources_stop(executed[5], str(project_dir))

    def test_test_resources_start_failure_aborts_run_before_delegate(self):
        executed = []
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "start-failure"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            def runner(command_line):
                executed.append(command_line)
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    return 10
                return 0

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Test resources server failed to start", stderr.getvalue())
        self.assertTrue(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(self._RUN_MAIN in cmd for cmd in executed))

    def test_test_resources_settings_are_propagated_to_run_via_environment(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "with-settings"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
        run_invocation = next((item for item in executed if self._RUN_MAIN in item[0]), None)
        if run_invocation is None:
            self.fail("Expected delegated pyronaut-run invocation")
        _, run_env = run_invocation
        self.assertIsInstance(run_env, dict)
        assert isinstance(run_env, dict)
        self.assertEqual("http://localhost:18900", run_env.get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))
        self.assertEqual("token-abc", run_env.get("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"))
        system_properties = self._extract_system_properties(run_invocation[0])
        self.assertEqual("http://localhost:18900", system_properties.get("micronaut.test.resources.server.uri"))
        self.assertEqual("token-abc", system_properties.get("micronaut.test.resources.server.access.token"))

    def test_resolve_test_resources_logs_dir_honors_pyproject_configuration(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            settings_file.parent.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"

[tool.pyronaut.testResources]
logsDir = "var/custom-test-resources-logs"
""".strip()
                + "\n",
                encoding="utf-8",
            )

            resolved = cli._resolve_test_resources_logs_dir(  # noqa: SLF001 - internal helper coverage
                project_dir.resolve(),
                settings_file.resolve(),
            )

        self.assertEqual((project_dir / "var" / "custom-test-resources-logs").resolve(), resolved)

    def test_test_resources_start_isolated_from_micronaut_server_port_env(self):
        seen_server_port: str | None = None
        seen_server_host: str | None = None

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "isolate-env"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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

    def test_build_delegate_classpath_includes_all_delegate_lib_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            tools_dir = Path(temp_dir) / "tools" / "pyronaut-run"
            bin_dir = tools_dir / "bin"
            lib_dir = tools_dir / "lib"
            bin_dir.mkdir(parents=True, exist_ok=True)
            lib_dir.mkdir(parents=True, exist_ok=True)
            executable = bin_dir / "pyronaut-run"
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            (lib_dir / "micronaut-pyronaut-run-0.0.1-SNAPSHOT.jar").write_text("", encoding="utf-8")
            (lib_dir / "picocli-4.7.7.jar").write_text("", encoding="utf-8")
            (lib_dir / "slf4j-api-2.0.17.jar").write_text("", encoding="utf-8")

            previous = os.environ.pop("PYRONAUT_RUN_JAR", None)
            try:
                classpath = cli._build_delegate_classpath(  # noqa: SLF001 - exercising internal helper directly
                    "run",
                    project_dir.resolve(),
                    lambda command_name: str(executable) if command_name == "pyronaut-run" else f"/tmp/{command_name}",
                )
            finally:
                if previous is not None:
                    os.environ["PYRONAUT_RUN_JAR"] = previous

            entries = classpath.split(os.pathsep)
            self.assertEqual("/tmp/runtime.jar", entries[0])
            self.assertIn(str((lib_dir / "micronaut-pyronaut-run-0.0.1-SNAPSHOT.jar").resolve()), entries)
            self.assertIn(str((lib_dir / "picocli-4.7.7.jar").resolve()), entries)
            self.assertIn(str((lib_dir / "slf4j-api-2.0.17.jar").resolve()), entries)

    def test_run_delegate_classpath_leaves_application_resources_to_run_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "app-config").mkdir(parents=True, exist_ok=True)
            (project_dir / "views").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut]
repositories = ["mavenCentral"]

[tool.pyronaut.dependencies]
runtime = []
build = []
test = []

[tool.pyronaut.sources]
resources = "app-config"
additional-resources = ["views"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_delegate_classpath("run", project_dir.resolve(), self._resolver())  # noqa: SLF001
            entries = classpath.split(os.pathsep)
            self.assertNotIn(str((project_dir / "app-config").resolve()), entries)
            self.assertNotIn(str((project_dir / "views").resolve()), entries)
            self.assertNotIn(str((project_dir / "config").resolve()), entries)

    def test_test_delegate_classpath_honors_configured_test_resources_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "app-config").mkdir(parents=True, exist_ok=True)
            (project_dir / "views").mkdir(parents=True, exist_ok=True)
            (project_dir / "assets").mkdir(parents=True, exist_ok=True)
            (project_dir / "test-resources").mkdir(parents=True, exist_ok=True)
            (project_dir / "test-fixtures").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut]
repositories = ["mavenCentral"]

[tool.pyronaut.dependencies]
runtime = []
build = []
test = []

[tool.pyronaut.sources]
resources = "app-config"
test-resources = "test-resources"
additional-resources = ["views", "assets"]
additional-test-resources = ["test-fixtures"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_delegate_classpath("test", project_dir.resolve(), self._resolver())  # noqa: SLF001
            entries = classpath.split(os.pathsep)
            self.assertIn(str((project_dir / "app-config").resolve()), entries)
            self.assertIn(str((project_dir / "views").resolve()), entries)
            self.assertIn(str((project_dir / "assets").resolve()), entries)
            self.assertIn(str((project_dir / "test-resources").resolve()), entries)
            self.assertIn(str((project_dir / "test-fixtures").resolve()), entries)
            self.assertNotIn(str((project_dir / "config").resolve()), entries)

    def test_snapshot_watched_files_honors_configured_layout(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "app").mkdir(parents=True, exist_ok=True)
            (project_dir / "app-tests").mkdir(parents=True, exist_ok=True)
            (project_dir / "src/main/java").mkdir(parents=True, exist_ok=True)
            (project_dir / "src/test/java").mkdir(parents=True, exist_ok=True)
            (project_dir / "app-config").mkdir(parents=True, exist_ok=True)
            (project_dir / "assets").mkdir(parents=True, exist_ok=True)
            (project_dir / "test-resources").mkdir(parents=True, exist_ok=True)
            (project_dir / "test-fixtures").mkdir(parents=True, exist_ok=True)
            (project_dir / "app" / "main.py").write_text("print('ok')\n", encoding="utf-8")
            (project_dir / "app-tests" / "test_main.py").write_text("def test_ok():\n    assert True\n", encoding="utf-8")
            (project_dir / "src/main/java" / "Main.java").write_text("class Main {}\n", encoding="utf-8")
            (project_dir / "src/test/java" / "MainTest.java").write_text("class MainTest {}\n", encoding="utf-8")
            (project_dir / "app-config" / "application.toml").write_text("micronaut.server.port = 8080\n", encoding="utf-8")
            (project_dir / "assets" / "logo.svg").write_text("<svg />\n", encoding="utf-8")
            (project_dir / "test-resources" / "test.properties").write_text("key=value\n", encoding="utf-8")
            (project_dir / "test-fixtures" / "book.json").write_text("{}\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.sources]
python = "app"
python-test = "app-tests"
java = "src/main/java"
java-test = "src/test/java"
resources = "app-config"
test-resources = "test-resources"
additional-resources = ["assets"]
additional-test-resources = ["test-fixtures"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            snapshot = cli._snapshot_watched_files(project_dir.resolve())  # noqa: SLF001
            watched_files = {entry[0] for entry in snapshot}
            self.assertIn("app/main.py", watched_files)
            self.assertIn("app-tests/test_main.py", watched_files)
            self.assertIn("src/main/java/Main.java", watched_files)
            self.assertIn("src/test/java/MainTest.java", watched_files)
            self.assertIn("app-config/application.toml", watched_files)
            self.assertIn("assets/logo.svg", watched_files)
            self.assertIn("test-resources/test.properties", watched_files)
            self.assertIn("test-fixtures/book.json", watched_files)

    def test_run_delegate_classpath_prefers_development_runtime_manifest_before_delegate_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir, development_runtime=True)
            tools_dir = Path(temp_dir) / "tools" / "pyronaut-run"
            bin_dir = tools_dir / "bin"
            lib_dir = tools_dir / "lib"
            bin_dir.mkdir(parents=True, exist_ok=True)
            lib_dir.mkdir(parents=True, exist_ok=True)
            executable = bin_dir / "pyronaut-run"
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            (lib_dir / "micronaut-pyronaut-run-0.0.1-SNAPSHOT.jar").write_text("", encoding="utf-8")

            previous = os.environ.pop("PYRONAUT_RUN_JAR", None)
            try:
                classpath = cli._build_delegate_classpath(  # noqa: SLF001 - exercising internal helper directly
                    "run",
                    project_dir.resolve(),
                    lambda command_name: str(executable) if command_name == "pyronaut-run" else f"/tmp/{command_name}",
                )
            finally:
                if previous is not None:
                    os.environ["PYRONAUT_RUN_JAR"] = previous

            entries = classpath.split(os.pathsep)
            self.assertEqual("/tmp/runtime-dev.jar", entries[0])
            self.assertNotIn("/tmp/runtime.jar", entries)
            self.assertEqual(str((lib_dir / "micronaut-pyronaut-run-0.0.1-SNAPSHOT.jar").resolve()), entries[1])

    def test_run_delegation_preserves_server_port_env_without_java_home_provider(self):
        executed = []

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "preserve-run-env"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
        run_invocation = next((item for item in executed if self._RUN_MAIN in item[0]), None)
        if run_invocation is None:
            self.fail("Expected delegated pyronaut-run invocation")
        _, run_env = run_invocation
        self.assertIsInstance(run_env, dict)
        assert isinstance(run_env, dict)
        self.assertEqual("8181", run_env.get("MICRONAUT_SERVER_PORT"))

    def test_build_does_not_orchestrate_test_resources_lifecycle(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-no-test-resources"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            def runner(command_line):
                executed.append(command_line)
                return 0

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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

    def test_owned_test_resources_session_mirrors_late_container_logs(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            settings_file = settings_dir / "test-resources.properties"
            settings_file.write_text(
                "server.uri=http\\://localhost\\:18900\n"
                "server.access.token=token-abc\n",
                encoding="utf-8",
            )
            log_dir = settings_dir / "logs"
            log_dir.mkdir(parents=True, exist_ok=True)
            log_file = log_dir / "test-resources.log"
            log_file.write_text("", encoding="utf-8")

            session = cli._OwnedTestResourcesSession(project_dir=project_dir.resolve(), owner_command="pyronaut run")
            stderr = io.StringIO()
            try:
                with redirect_stderr(stderr):
                    session._start_log_mirror()  # noqa: SLF001 - internal helper coverage
                    with log_file.open("a", encoding="utf-8") as handle:
                        handle.write(
                            "17:36:59.686 [pool-1-thread-1] INFO  tc.mysql:8.4.0 - Creating container for image: mysql:8.4.0\n"
                        )
                        handle.flush()
                    deadline = time.time() + 2.0
                    while "Creating container for image: mysql:8.4.0" not in stderr.getvalue() and time.time() < deadline:
                        time.sleep(0.05)
            finally:
                session._stop_log_mirror()  # noqa: SLF001 - internal helper coverage

            self.assertIn("Creating container for image: mysql:8.4.0", stderr.getvalue())

    def test_owned_test_resources_session_mirrors_late_container_logs_from_launcher_stdio(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            settings_file = settings_dir / "test-resources.properties"
            settings_file.write_text(
                "server.uri=http\\://localhost\\:18900\n"
                "server.access.token=token-abc\n",
                encoding="utf-8",
            )
            log_dir = settings_dir / "logs"
            log_dir.mkdir(parents=True, exist_ok=True)
            log_file = log_dir / "launcher-stdio.log"
            log_file.write_text("", encoding="utf-8")

            session = cli._OwnedTestResourcesSession(project_dir=project_dir.resolve(), owner_command="pyronaut run")
            stderr = io.StringIO()
            try:
                with redirect_stderr(stderr):
                    session._start_log_mirror()  # noqa: SLF001 - internal helper coverage
                    with log_file.open("a", encoding="utf-8") as handle:
                        handle.write(
                            "17:36:59.686 [pool-1-thread-1] INFO  tc.mysql:8.4.0 - Creating container for image: mysql:8.4.0\n"
                        )
                        handle.flush()
                    deadline = time.time() + 2.0
                    while "Creating container for image: mysql:8.4.0" not in stderr.getvalue() and time.time() < deadline:
                        time.sleep(0.05)
            finally:
                session._stop_log_mirror()  # noqa: SLF001 - internal helper coverage

            self.assertIn("Creating container for image: mysql:8.4.0", stderr.getvalue())

    def test_owned_test_resources_session_quiet_mode_suppresses_stderr_output(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            settings_file = settings_dir / "test-resources.properties"
            settings_file.write_text(
                "server.uri=http\\://localhost\\:18900\n"
                "server.access.token=token-abc\n",
                encoding="utf-8",
            )
            log_dir = settings_dir / "logs"
            log_dir.mkdir(parents=True, exist_ok=True)
            log_file = log_dir / "test-resources.log"
            log_file.write_text("", encoding="utf-8")

            session = cli._OwnedTestResourcesSession(
                project_dir=project_dir.resolve(),
                owner_command="pyronaut --tui --project-dir demo",
                quiet=True,
            )
            stderr = io.StringIO()
            try:
                with redirect_stderr(stderr):
                    session._report_started_server()  # noqa: SLF001 - internal helper coverage
                    session._start_log_mirror()  # noqa: SLF001 - internal helper coverage
                    with log_file.open("a", encoding="utf-8") as handle:
                        handle.write(
                            "17:36:59.686 [pool-1-thread-1] INFO  tc.mysql:8.4.0 - Creating container for image: mysql:8.4.0\n"
                        )
                        handle.flush()
                    time.sleep(0.2)
            finally:
                session._stop_log_mirror()  # noqa: SLF001 - internal helper coverage

            self.assertEqual("", stderr.getvalue())

    def test_quiet_test_resources_delegate_discards_child_stdio(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            session = cli._OwnedTestResourcesSession(
                project_dir=project_dir.resolve(),
                owner_command="pyronaut --tui --project-dir demo",
                quiet=True,
            )

            completed = subprocess.CompletedProcess(args=["/tmp/pyronaut-test-resources-server"], returncode=0)
            with patch.object(cli.subprocess, "run", return_value=completed) as run_mock:
                exit_code = session._delegate_test_resources_server(  # noqa: SLF001 - internal helper coverage
                    ["start", "--project-dir", str(project_dir)],
                    runner=cli._run_subprocess,
                    resolver=self._resolver(),
                )

            self.assertEqual(0, exit_code)
            _, kwargs = run_mock.call_args
            self.assertIs(kwargs.get("stdout"), cli.subprocess.DEVNULL)
            self.assertIs(kwargs.get("stderr"), cli.subprocess.DEVNULL)

    def test_owned_test_resources_session_preserves_session_file_when_stop_fails(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            settings_file = settings_dir / "test-resources.properties"
            settings_file.write_text(
                "server.uri=http\\://localhost\\:18900\n"
                "server.access.token=token-abc\n",
                encoding="utf-8",
            )

            session = cli._OwnedTestResourcesSession(project_dir=project_dir.resolve(), owner_command="pyronaut run")
            session._started = True  # noqa: SLF001 - internal helper coverage
            (project_dir / "__pyronaut__").mkdir(parents=True, exist_ok=True)
            session._persist_session(started_at=time.time())  # noqa: SLF001 - internal helper coverage

            executed = []

            def runner(command_line, env=None):
                executed.append(command_line)
                return 10

            stderr = io.StringIO()
            with redirect_stderr(stderr):
                session.stop_if_owned(runner=runner, resolver=self._resolver())

            self.assertTrue(session._session_file.exists())  # noqa: SLF001 - internal helper coverage
            self.assertIn("stop failed (exit code 10); preserving session state for retry", stderr.getvalue())
            self._assert_test_resources_stop(executed[0], str(project_dir))

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
        self._assert_test_delegate(executed[4], "/tmp/demo", ["--tests", "tests/test_math.py::test_add", "--tests", "*test_add*"])
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_run_debug_vm_sets_jvm_arg_and_forwards_flag(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with patch.object(cli, "_check_port_available", return_value=(True, None)):
            exit_code = cli.run(
                ["run", "--project-dir", "/tmp/demo", "--debug-vm"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0][0])
        self.assertIsInstance(executed[0][1], dict)
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1][0])
        self.assertIsInstance(executed[1][1], dict)
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2][0])
        self.assertIsInstance(executed[2][1], dict)
        self._assert_test_resources_start(executed[3][0], "/tmp/demo")
        self.assertIsNone(executed[3][1])
        self._assert_run_delegate(executed[4][0], "/tmp/demo", ["--debug-vm"])
        self.assertEqual(self._JDWP_FLAG, executed[4][0][3])

    def test_test_debug_vm_sets_jvm_arg(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with patch.object(cli, "_check_port_available", return_value=(True, None)):
            exit_code = cli.run(
                ["test", "--project-dir", "/tmp/demo", "--debug-vm"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self._assert_test_delegate(executed[4][0], "/tmp/demo", ["--debug-vm"])
        self.assertEqual(self._JDWP_FLAG, executed[4][0][3])

    def test_debug_vm_fails_fast_when_port_busy(self):
        stderr = io.StringIO()
        with patch.object(cli, "_check_port_available", return_value=(False, "already in use")):
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["run", "--project-dir", "/tmp/demo", "--debug-vm"],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )

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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            self.assertEqual(9, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["--scenario", "run"], executed[0][3:])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[2][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[2][2]))
            self.assertEqual("/tmp/pyronaut-test-resources-server", executed[3][0])
            self.assertEqual("start", executed[3][1])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[4][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[4][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[5][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[5][2]))
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[6][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[6][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[7][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[7][2]))
            self.assertEqual("/tmp/pyronaut-test-resources-server", executed[8][0])
            self.assertEqual("stop", executed[8][1])
            self.assertEqual(2, len(started))
            self._assert_run_delegate(started[0][0], str(project_dir))
            self._assert_run_delegate(started[1][0], str(project_dir))
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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            self.assertEqual(0, len(started))

    def test_run_restart_aborts_when_stop_fails(self):
        executed = []
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "watched-stop-fail"
            src_dir = project_dir / "src"
            src_dir.mkdir(parents=True, exist_ok=True)
            source_file = src_dir / "controller.py"
            source_file.write_text("print('v1')\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

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
            self.assertEqual(5, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["--scenario", "run"], executed[0][3:])
            self.assertEqual(["/tmp/pyronaut-install", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[2][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[2][2]))
            self.assertEqual(["/tmp/pyronaut-test-resources-server", "start", "--project-dir"], executed[3][:3])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[3][3]))
            self.assertEqual(ANY, executed[3][5])
            self.assertEqual(["/tmp/pyronaut-test-resources-server", "stop", "--project-dir"], executed[4][:3])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[4][3]))
            self.assertEqual(ANY, executed[4][5])
            self._assert_run_delegate(started[0][0], str(project_dir))

    def test_run_auto_restart_stops_when_validation_fails(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if "pyronaut-validate-config" in command_line[0]:
                return 1
            return 0

        def process_runner(command_line, env):
            raise AssertionError("process runner should not be invoked when validation fails")

        exit_code = cli.run(
            ["run", "--project-dir", "/tmp/demo"],
            runner_with_env=runner_with_env,
            process_runner=process_runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(1, exit_code)
        self.assertEqual(1, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0][0])

    def test_run_uses_provided_java_home_for_run_delegation(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

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
        self.assertIsInstance(executed[0][1], dict)
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"], executed[1][0])
        self.assertIsInstance(executed[1][1], dict)
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo"], executed[2][0])
        self.assertIsInstance(executed[2][1], dict)
        self._assert_test_resources_start(executed[3][0], "/tmp/demo")
        self.assertIsNone(executed[3][1])
        self._assert_run_delegate(executed[4][0], "/tmp/demo")
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
        staged_files: list[str] = []
        staged_manifest = ""

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            nonlocal staged_files, staged_manifest
            if len(command_line) >= 5 and command_line[1:4] == ["-m", "pip", "wheel"]:
                stage_dir = Path(command_line[-1])
                staged_files = sorted(str(path.relative_to(stage_dir)) for path in stage_dir.rglob("*"))
                staged_manifest = (stage_dir / "pyproject.toml").read_text(encoding="utf-8")
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["build", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(4, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir.resolve()), "--scenario", "production"], executed[0][0])
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir.resolve())], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir.resolve())], executed[2][0])
        self.assertEqual("-m", executed[3][0][1])
        self.assertEqual("pip", executed[3][0][2])
        self.assertEqual("wheel", executed[3][0][3])
        self.assertIn("--wheel-dir", executed[3][0])
        self.assertIsNone(executed[3][1])
        self.assertIn("setup.py", staged_files)
        self.assertIn("MANIFEST.in", staged_files)
        self.assertTrue(any(path.endswith("launcher.py") for path in staged_files))
        self.assertIn("setuptools.build_meta", staged_manifest)
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

    def test_build_docker_requires_native_when_static_is_requested(self):
        stderr = io.StringIO()

        with tempfile.TemporaryDirectory() as temp_dir, redirect_stderr(stderr):
            project_dir = Path(temp_dir) / "docker-static-error"
            project_dir.mkdir(parents=True, exist_ok=True)
            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir), "--docker", "--static"],
                runner_with_env=lambda *_args: 0,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("--static is only supported with pyronaut build --native --docker", stderr.getvalue())

    def test_build_docker_jvm_runs_preflight_then_docker_build(self):
        executed = []
        captured: dict[str, object] = {}

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if len(command_line) >= 2 and command_line[1] == "build":
                context_dir = Path(command_line[-1])
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["docker_command"] = command_line
                captured["dockerfile"] = dockerfile.read_text(encoding="utf-8")
                captured["manifest"] = (context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies").read_text(encoding="utf-8")
                captured["context_files"] = sorted(str(path.relative_to(context_dir)) for path in context_dir.rglob("*"))
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            root_dir = Path(temp_dir)
            project_dir = root_dir / "docker-demo"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes" / "example").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes" / "example" / "Demo.class").write_text("", encoding="utf-8")
            (project_dir / "config").mkdir(parents=True, exist_ok=True)
            (project_dir / "config" / "application.toml").write_text("micronaut.server.port = 8080\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n",
                encoding="utf-8",
            )
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo-app\"\nversion = \"1.2.3\"\n",
                encoding="utf-8",
            )
            run_executable = self._write_fake_install_dist(root_dir, "pyronaut-run")

            def resolver(command_name):
                if command_name == "pyronaut-run":
                    return run_executable
                return f"/tmp/{command_name}"

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["build", "--project-dir", str(project_dir), "--docker"],
                    runner_with_env=runner_with_env,
                    resolver=resolver,
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir.resolve()), "--scenario", "production"],
            executed[0][0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir.resolve())], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir.resolve())], executed[2][0])
        docker_command = captured["docker_command"]
        self.assertEqual("/usr/bin/docker", docker_command[0])
        self.assertEqual("build", docker_command[1])
        self.assertIn("--build-arg", docker_command)
        self.assertIn("PYRONAUT_BUILD_MODE=jvm", docker_command)
        self.assertIn("PYRONAUT_PROJECT_NAME=demo-app", docker_command)
        self.assertIn("PYRONAUT_PROJECT_VERSION=1.2.3", docker_command)
        self.assertIn("PYRONAUT_JVM_BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:25", docker_command)
        self.assertIn("-t", docker_command)
        self.assertIn("demo-app:1.2.3", docker_command)
        self.assertIn('ENTRYPOINT ["/app/__pyronaut__/tools/pyronaut-run/bin/pyronaut-run", "--project-dir", "/app"]', captured["dockerfile"])
        self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", captured["manifest"])
        self.assertIn("app/__pyronaut__/tools/pyronaut-run/bin/pyronaut-run", captured["context_files"])
        self.assertIn("app/config/application.toml", captured["context_files"])
        self.assertIn("app/pyproject.toml", captured["context_files"])
        self.assertIn("Docker image build complete: demo-app:1.2.3", stdout.getvalue())

    def test_build_docker_native_uses_container_build_and_static_args(self):
        executed = []
        captured: dict[str, object] = {}

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if len(command_line) >= 2 and command_line[1] == "build":
                context_dir = Path(command_line[-1])
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["docker_command"] = command_line
                captured["dockerfile"] = dockerfile.read_text(encoding="utf-8")
                captured["manifest"] = (context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies").read_text(encoding="utf-8")
                captured["context_files"] = sorted(str(path.relative_to(context_dir)) for path in context_dir.rglob("*"))
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            root_dir = Path(temp_dir)
            project_dir = root_dir / "native-docker-demo"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes" / "example").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes" / "example" / "Demo.class").write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n",
                encoding="utf-8",
            )
            (project_dir / "pyproject.toml").write_text(
                "\n".join(
                    [
                        "[project]",
                        "name = \"demo-app\"",
                        "version = \"1.2.3\"",
                        "",
                        "[tool.pyronaut.build.docker]",
                        "image-name = \"example/demo\"",
                        "native-builder-image = \"example/native-builder:1\"",
                        "native-base-image = \"example/native-base:1\"",
                        "static-native-builder-image = \"example/static-builder:1\"",
                        "static-native-base-image = \"example/static-base:1\"",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            native_executable = self._write_fake_install_dist(root_dir, "pyronaut-native-build")

            def resolver(command_name):
                if command_name == "pyronaut-native-build":
                    return native_executable
                return f"/tmp/{command_name}"

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    [
                        "build",
                        "--native",
                        "--docker",
                        "--static",
                        "--project-dir",
                        str(project_dir),
                        "--main-class",
                        "example.Main",
                        "--verbose",
                        "--",
                        "--initialize-at-run-time=example.Foo",
                    ],
                    runner_with_env=runner_with_env,
                    resolver=resolver,
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir.resolve()), "--scenario", "production"],
            executed[0][0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir.resolve())], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir.resolve())], executed[2][0])
        self.assertEqual(4, len(executed))
        docker_command = captured["docker_command"]
        self.assertEqual("/usr/bin/docker", docker_command[0])
        self.assertEqual("build", docker_command[1])
        self.assertIn("--progress=plain", docker_command)
        self.assertIn("PYRONAUT_BUILD_MODE=native", docker_command)
        self.assertIn("PYRONAUT_NATIVE_STATIC=true", docker_command)
        self.assertIn("PYRONAUT_NATIVE_BUILDER_IMAGE=example/static-builder:1", docker_command)
        self.assertIn("PYRONAUT_NATIVE_BASE_IMAGE=example/static-base:1", docker_command)
        self.assertIn("example/demo:1.2.3-native", docker_command)
        self.assertIn("FROM example/static-builder:1 AS builder", captured["dockerfile"])
        self.assertIn("FROM example/static-base:1", captured["dockerfile"])
        self.assertIn("--static --libc=musl", captured["dockerfile"])
        self.assertIn("--initialize-at-run-time=example.Foo", captured["dockerfile"])
        self.assertIn("app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build", captured["context_files"])
        self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", captured["manifest"])
        self.assertIn("Docker image build complete: example/demo:1.2.3-native", stdout.getvalue())

    def test_build_docker_uses_custom_dockerfiles_from_pyproject(self):
        executed = []
        captured: dict[str, object] = {}

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if len(command_line) >= 2 and command_line[1] == "build":
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["docker_command"] = command_line
                captured["dockerfile_name"] = dockerfile.name
                captured["dockerfile"] = dockerfile.read_text(encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            root_dir = Path(temp_dir)
            project_dir = root_dir / "custom-docker-demo"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n",
                encoding="utf-8",
            )
            (project_dir / "docker").mkdir(parents=True, exist_ok=True)
            (project_dir / "docker" / "Dockerfile.jvm").write_text(
                "FROM ${PYRONAUT_JVM_BASE_IMAGE}\nARG PYRONAUT_PROJECT_NAME\n",
                encoding="utf-8",
            )
            (project_dir / "docker" / "Dockerfile.native").write_text(
                "FROM ${PYRONAUT_NATIVE_BUILDER_IMAGE} AS builder\nARG PYRONAUT_NATIVE_STATIC\n",
                encoding="utf-8",
            )
            (project_dir / "pyproject.toml").write_text(
                "\n".join(
                    [
                        "[project]",
                        "name = \"demo-app\"",
                        "version = \"1.2.3\"",
                        "",
                        "[tool.pyronaut.build.docker]",
                        "dockerfile = \"docker/Dockerfile.jvm\"",
                        "dockerfile-native = \"docker/Dockerfile.native\"",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            run_executable = self._write_fake_install_dist(root_dir, "pyronaut-run")
            native_executable = self._write_fake_install_dist(root_dir, "pyronaut-native-build")

            def resolver(command_name):
                if command_name == "pyronaut-run":
                    return run_executable
                if command_name == "pyronaut-native-build":
                    return native_executable
                return f"/tmp/{command_name}"

            exit_code_jvm = cli.run(
                ["build", "--project-dir", str(project_dir), "--docker"],
                runner_with_env=runner_with_env,
                resolver=resolver,
                platform_name="linux",
            )
            self.assertEqual(0, exit_code_jvm)
            self.assertEqual("Dockerfile.jvm", captured["dockerfile_name"])
            self.assertIn("ARG PYRONAUT_PROJECT_NAME", captured["dockerfile"])
            self.assertIn("PYRONAUT_JVM_BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:25", captured["docker_command"])

            captured.clear()
            executed.clear()
            exit_code_native = cli.run(
                ["build", "--native", "--docker", "--project-dir", str(project_dir), "--main-class", "example.Main"],
                runner_with_env=runner_with_env,
                resolver=resolver,
                platform_name="linux",
            )

        self.assertEqual(0, exit_code_native)
        self.assertEqual("Dockerfile.native", captured["dockerfile_name"])
        self.assertIn("ARG PYRONAUT_NATIVE_STATIC", captured["dockerfile"])
        self.assertIn("PYRONAUT_NATIVE_BUILDER_IMAGE=container-registry.oracle.com/graalvm/native-image:25", captured["docker_command"])

    def test_prepare_jvm_build_wheel_staging_rewrites_manifest_and_generates_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "stage-demo"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "config").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(str(runtime_jar.resolve()) + "\n", encoding="utf-8")
            staging_dir = Path(temp_dir) / "stage-out"

            cli._prepare_build_wheel_staging(  # noqa: SLF001 - internal helper coverage
                project_dir=project_dir,
                staging_dir=staging_dir,
                project_name="demo-app",
                project_version="1.2.3",
                mode="jvm",
                main_class="example.Main",
            )

            launcher_pkg = staging_dir / "demo_app_launcher"
            self.assertTrue((launcher_pkg / "launcher.py").exists())
            self.assertTrue((launcher_pkg / "app" / "__pyronaut__" / "classes").is_dir())
            self.assertTrue((launcher_pkg / "app" / "__pyronaut__" / "m2-repository").is_dir())
            manifest = (launcher_pkg / "app" / "__pyronaut__" / "resolved-runtime-dependencies").read_text(encoding="utf-8")
            self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", manifest)
            launcher_code = (launcher_pkg / "launcher.py").read_text(encoding="utf-8")
            self.assertIn("example.Main", launcher_code)
            self.assertIn("_build_java_delegate_invocation", launcher_code)

    def test_prepare_native_docker_context_stages_distribution_and_rewrites_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root_dir = Path(temp_dir)
            project_dir = root_dir / "native-context-demo"
            context_dir = root_dir / "context"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n",
                encoding="utf-8",
            )
            native_executable = self._write_fake_install_dist(root_dir, "pyronaut-native-build")

            cli._prepare_native_docker_context(  # noqa: SLF001 - internal helper coverage
                project_dir=project_dir,
                context_dir=context_dir,
                resolver=lambda command_name: native_executable if command_name == "pyronaut-native-build" else None,
            )

            manifest = (context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies").read_text(encoding="utf-8")
            self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", manifest)
            self.assertTrue((context_dir / "app" / "__pyronaut__" / "tools" / "pyronaut-native-build" / "bin" / "pyronaut-native-build").exists())

    def test_remove_existing_built_wheels_cleans_matching_distribution_prefix(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            dist_dir = Path(temp_dir)
            keep = dist_dir / "another_app-1.0.0-py3-none-any.whl"
            remove_jvm = dist_dir / "pyronaut_demo-1.0.0-py3-none-any.whl"
            remove_native = dist_dir / "pyronaut_demo-1.0.0-graalpy312.whl"
            keep.write_text("", encoding="utf-8")
            remove_jvm.write_text("", encoding="utf-8")
            remove_native.write_text("", encoding="utf-8")

            cli._remove_existing_built_wheels(dist_dir, "pyronaut-demo")  # noqa: SLF001 - internal helper coverage

            self.assertTrue(keep.exists())
            self.assertFalse(remove_jvm.exists())
            self.assertFalse(remove_native.exists())

    def test_build_no_cache_forwards_to_validation_step(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-no-cache"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir), "--no-cache"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(4, len(executed))
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
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(project_dir.resolve()), "--no-cache"], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir.resolve()), "--no-cache"], executed[2][0])

    def test_build_native_runs_preflight_then_native_delegate_with_java_home(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
            return 0

        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-demo"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache_dir / "native" / "native-demo").parent.mkdir(parents=True, exist_ok=True)

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
        self.assertIsInstance(executed[0][1], dict)
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", resolved_project_dir], executed[1][0])
        self.assertIsInstance(executed[1][1], dict)
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir], executed[2][0])
        self.assertIsInstance(executed[2][1], dict)
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertIn("--main-class", executed[3][0])
        self.assertIn("--output", executed[3][0])
        self.assertIn(str(project_dir.resolve() / "__pyronaut__" / "native" / "native-demo"), executed[3][0])
        self.assertEqual("/tmp/graalvm-jdk-25", executed[3][1]["JAVA_HOME"])
        self.assertEqual("-m", executed[4][0][1])
        self.assertEqual("pip", executed[4][0][2])
        self.assertEqual("wheel", executed[4][0][3])
        self.assertIn("Native wheel build complete", stdout.getvalue())

    def test_build_native_no_cache_not_forwarded_to_native_build_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-demo"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache_dir / "native" / "native-demo").parent.mkdir(parents=True, exist_ok=True)

            exit_code = cli.run(
                ["build", "--native", "--project-dir", str(project_dir), "--no-cache", "--verbose"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        resolved_project_dir = str(project_dir.resolve())
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "production", "--no-cache"],
            executed[0][0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", resolved_project_dir, "--no-cache"], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir, "--no-cache"], executed[2][0])
        self.assertEqual("/tmp/pyronaut-native-build", executed[3][0][0])
        self.assertNotIn("--no-cache", executed[3][0])
        self.assertIn("--verbose", executed[3][0])

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
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
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
        self.assertEqual("wheel", executed[4][0][3])

    def test_build_mode_flag_with_separate_value_uses_native(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
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
        self.assertEqual("wheel", executed[4][0][3])

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
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
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
        self.assertEqual("wheel", executed[4][0][3])

    def test_build_native_forwards_unconsumed_native_image_args_to_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
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
        self.assertEqual("wheel", executed[4][0][3])

    def test_build_native_forwards_args_after_separator_to_delegate(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_text("binary", encoding="utf-8")
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
        self.assertEqual("wheel", executed[4][0][3])

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
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "sdks", create=True), \
                patch.object(cli, "_legacy_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root, _spec=None: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root, _spec=None: call_order.append("sdkman") or expected, create=True), \
                patch.object(cli, "_find_with_jenv", lambda _spec: call_order.append("jenv") or None, create=True), \
                patch.object(cli, "_find_with_gradle_jdks", lambda _spec: call_order.append("gradle") or None, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root, _spec=None: call_order.append("download") or None, create=True):
                resolved = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), resolved)
        self.assertEqual(["sdkman"], call_order)

    def test_ensure_graalvm_java_home_falls_back_to_download(self):
        call_order = []
        with tempfile.TemporaryDirectory() as temp_dir:
            expected = Path(temp_dir) / "download-home"
        with patch.object(cli, "_provisioned_graalvm_home", None, create=True), \
                patch.object(cli, "_read_env", lambda name: None, create=True), \
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "sdks", create=True), \
                patch.object(cli, "_legacy_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root, _spec=None: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root, _spec=None: call_order.append("sdkman") or None, create=True), \
                patch.object(cli, "_find_with_jenv", lambda _spec: call_order.append("jenv") or None, create=True), \
                patch.object(cli, "_find_with_gradle_jdks", lambda _spec: call_order.append("gradle") or None, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root, _spec=None: call_order.append("download") or expected, create=True):
                resolved = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), resolved)
        self.assertEqual(["sdkman", "jenv", "gradle", "download"], call_order)

    def test_ensure_graalvm_java_home_is_idempotent_after_first_resolution(self):
        counts = {"sdkman": 0, "jenv": 0, "gradle": 0, "download": 0}
        with tempfile.TemporaryDirectory() as temp_dir:
            expected = Path(temp_dir) / "download-home"
        with patch.object(cli, "_provisioned_graalvm_home", None, create=True), \
                patch.object(cli, "_read_env", lambda name: None, create=True), \
                patch.object(cli, "_graalvm_jdks_root", lambda: Path(temp_dir) / "sdks", create=True), \
                patch.object(cli, "_legacy_graalvm_jdks_root", lambda: Path(temp_dir) / "jdks", create=True), \
                patch.object(cli, "_matches_requested_graalvm_home", lambda home, spec: str(home) == str(expected), create=True), \
                patch.object(cli, "_find_compatible_cached_jdk", lambda _root, _spec=None: None, create=True), \
                patch.object(cli, "_install_with_sdkman", lambda _root, _spec=None: counts.__setitem__("sdkman", counts["sdkman"] + 1) or None, create=True), \
                patch.object(cli, "_find_with_jenv", lambda _spec: counts.__setitem__("jenv", counts["jenv"] + 1) or None, create=True), \
                patch.object(cli, "_find_with_gradle_jdks", lambda _spec: counts.__setitem__("gradle", counts["gradle"] + 1) or None, create=True), \
                patch.object(cli, "_download_and_install_graalvm", lambda _root, _spec=None: counts.__setitem__("download", counts["download"] + 1) or expected, create=True):
                first = cli._ensure_graalvm_java_home()
                second = cli._ensure_graalvm_java_home()

        self.assertEqual(str(expected), first)
        self.assertEqual(str(expected), second)
        self.assertEqual(1, counts["sdkman"])
        self.assertEqual(1, counts["jenv"])
        self.assertEqual(1, counts["gradle"])
        self.assertEqual(1, counts["download"])

    def test_read_pyproject_toolchain_spec_supports_dev_builds(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"

[tool.pyronaut]
version = "5.0.0-SNAPSHOT"

[tool.pyronaut.toolchain]
distribution = "dev"
version = "25.1.0-dev+10.1"
java-version = 25
release-tag = "jdk-25.1.0-dev-20260429_0111"
download-url = "https://example.invalid/graalvm-dev.tar.gz"
                """.strip(),
                encoding="utf-8",
            )

            spec = cli._read_pyproject_toolchain_spec(project_dir)

        self.assertEqual("dev", spec.distribution)
        self.assertEqual("25.1.0-dev+10.1", spec.version)
        self.assertEqual(25, spec.java_version)
        self.assertEqual("jdk-25.1.0-dev-20260429_0111", spec.release_tag)
        self.assertEqual("https://example.invalid/graalvm-dev.tar.gz", spec.download_url)
        self.assertTrue(spec.explicit)

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
                "--validate-executable",
                "/tmp/pyronaut-validate-config",
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run/bin/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test/bin/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertEqual("http://localhost:18900", executed[1][1].get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))

    def test_tui_interactive_suppresses_test_resources_stderr_chatter(self):
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

        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            settings_file = project_dir.resolve() / ".micronaut" / "test-resources" / "test-resources.properties"

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self.assertEqual("", stderr.getvalue())

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
                "--validate-executable",
                "/tmp/pyronaut-validate-config",
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run/bin/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test/bin/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertEqual("http://localhost:18900", executed[1][1].get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))

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
                "--validate-executable",
                "/tmp/pyronaut-validate-config",
                "--install-executable",
                "/tmp/pyronaut-install",
                "--process-executable",
                "/tmp/pyronaut-processor",
                "--run-executable",
                "/tmp/pyronaut-run/bin/pyronaut-run",
                "--test-executable",
                "/tmp/pyronaut-test/bin/pyronaut-test",
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertEqual("http://localhost:18900", executed[1][1].get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))

    def test_tui_interactive_prefers_native_delegate_executables(self):
        executed = []
        settings_file: Path | None = None

        def runner_with_env(command_line, env):
            nonlocal settings_file
            executed.append((command_line, env))
            if command_line[:2] == ["/tmp/native-test-resources-server", "start"]:
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
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.processor]\nmode = \"native\"\n"
                "[tool.pyronaut.test]\nmode = \"native\"\n",
                encoding="utf-8",
            )
            expected_project_dir = project_dir.resolve()
            settings_file = expected_project_dir / ".micronaut" / "test-resources" / "test-resources.properties"

            native_install = Path(temp_dir) / "pyronaut-install"
            native_install.write_text("", encoding="utf-8")
            native_install.chmod(0o755)
            native_validate = Path(temp_dir) / "pyronaut-validate-config"
            native_validate.write_text("", encoding="utf-8")
            native_validate.chmod(0o755)
            native_processor = Path(temp_dir) / "pyronaut-processor"
            native_processor.write_text("", encoding="utf-8")
            native_processor.chmod(0o755)
            native_test = Path(temp_dir) / "pyronaut-test"
            native_test.write_text("", encoding="utf-8")
            native_test.chmod(0o755)
            native_test_resources = Path(temp_dir) / "pyronaut-test-resources-server"
            native_test_resources.write_text("", encoding="utf-8")
            native_test_resources.chmod(0o755)

            def bundled_native(command_name: str) -> Path | None:
                return {
                    "pyronaut-install": native_install,
                    "pyronaut-validate-config": native_validate,
                    "pyronaut-processor": native_processor,
                    "pyronaut-test": native_test,
                    "pyronaut-test-resources-server": native_test_resources,
                }.get(command_name)

            with patch.object(cli, "_bundled_native_executable", side_effect=bundled_native):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self.assertEqual(
            [
                str(native_test_resources),
                "start",
                "--project-dir",
                str(expected_project_dir),
                "--owner-token",
                executed[0][0][5],
            ],
            executed[0][0],
        )
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
                "--validate-executable",
                str(native_validate),
                "--install-executable",
                str(native_install),
                "--process-executable",
                str(native_processor),
                "--run-executable",
                "/tmp/pyronaut-run/bin/pyronaut-run",
                "--test-executable",
                str(native_test),
            ],
            executed[1][0],
        )
        self.assertEqual(
            [
                str(native_test_resources),
                "stop",
                "--project-dir",
                str(expected_project_dir),
                "--owner-token",
                executed[2][0][5],
            ],
            executed[2][0],
        )

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
        self.assertEqual(5, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(expected_project_dir), "--scenario", "test"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-install", "--project-dir", str(expected_project_dir)], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir)], executed[2])
        self._assert_test_resources_start(executed[3], str(expected_project_dir))
        self._assert_test_resources_stop(executed[4], str(expected_project_dir))
        self.assertIn("tests: 1 passed, 1 failed, 0 skipped", stderr.getvalue())

    def test_run_validates_run_scenario_before_preflight(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

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
        self._assert_run_delegate(executed[4], "/tmp/demo")
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_test_validates_test_scenario_before_preflight(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

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
        self._assert_test_delegate(executed[4], "/tmp/demo")
        self._assert_test_resources_stop(executed[5], "/tmp/demo")

    def test_build_validates_production_scenario(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-app"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
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
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner(command_line):
            executed.append(command_line)
            return 0

        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)
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
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner(command_line):
            executed.append(command_line)
            return 0

        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)
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
        self._assert_run_delegate(executed[3], "/tmp/demo")
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
        original_uri = os.environ.get("MICRONAUT_TEST_RESOURCES_SERVER_URI")
        original_token = os.environ.get("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN")
        os.environ["JAVA_TOOL_OPTIONS"] = (
            "-Dmicronaut.test.resources.server.uri=http://localhost:61247 "
            "-Dmicronaut.test.resources.server.access.token=stale-token "
            "-Duser.timezone=UTC"
        )
        os.environ["MICRONAUT_TEST_RESOURCES_SERVER_URI"] = "http://localhost:61247"
        os.environ["MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"] = "stale-token"
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
            if original_uri is None:
                os.environ.pop("MICRONAUT_TEST_RESOURCES_SERVER_URI", None)
            else:
                os.environ["MICRONAUT_TEST_RESOURCES_SERVER_URI"] = original_uri
            if original_token is None:
                os.environ.pop("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", None)
            else:
                os.environ["MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"] = original_token

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "production"], executed[0][0])
        run_env = executed[0][1] or {}
        options = run_env.get("JAVA_TOOL_OPTIONS", "")
        self.assertNotIn("micronaut.test.resources.server.uri", options)
        self.assertNotIn("micronaut.test.resources.server.access.token", options)
        self.assertNotIn("MICRONAUT_TEST_RESOURCES_SERVER_URI", run_env)
        self.assertNotIn("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", run_env)

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
            if command_name in {"pyronaut-run", "pyronaut-test"}:
                return f"/tmp/{command_name}/bin/{command_name}"
            return f"/tmp/{command_name}"

        return resolve

    @staticmethod
    def _runner_ok():
        def runner(_command_line):
            return 0

        return runner


if __name__ == "__main__":
    unittest.main()
