import importlib.util
import hashlib
import io
import os
import subprocess
import shutil
import tarfile
import tempfile
import time
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import socket
import threading
from unittest.mock import MagicMock, Mock, patch
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

    @classmethod
    def setUpClass(cls):
        cls._fake_python_delegate_root = Path(tempfile.mkdtemp(prefix="pyronaut-run-python-test-"))
        cls._fake_dev_delegate_root = Path(tempfile.mkdtemp(prefix="pyronaut-dev-test-"))
        for root, command_name in (
            (cls._fake_python_delegate_root, "pyronaut-run-python"),
            (cls._fake_dev_delegate_root, "pyronaut-dev"),
        ):
            fake_bin = root / "bin"
            fake_lib = root / "lib"
            fake_bin.mkdir()
            fake_lib.mkdir()
            launcher = fake_bin / command_name
            launcher.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            launcher.chmod(0o755)
            (fake_lib / f"{command_name}.jar").write_text("", encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls._fake_python_delegate_root, ignore_errors=True)
        shutil.rmtree(cls._fake_dev_delegate_root, ignore_errors=True)

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

    @staticmethod
    def _write_native_compile_descriptor(
        executable: Path,
        home: Path,
        coordinates: list[tuple[str, str, str, str]],
    ) -> list[Path]:
        entries = []
        artifacts = []
        for group, artifact, version, filename in coordinates:
            entries.append(
                "\t".join(("maven", group, artifact, version, "jar", "", filename))
            )
            resolved = (
                home
                / ".m2"
                / "repository"
                / Path(*group.split("."))
                / artifact
                / version
                / filename
            )
            resolved.parent.mkdir(parents=True, exist_ok=True)
            resolved.write_text(artifact, encoding="utf-8")
            artifacts.append(resolved)
        (executable.parent / "native-compile-classpath.txt").write_text(
            "\n".join(entries) + "\n", encoding="utf-8"
        )
        return artifacts

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
        expect_project_jars: bool = True,
    ) -> None:
        self.assertTrue(command_line[0].endswith("/bin/java") or command_line[0] == "java")
        cp_index = command_line.index("-cp")
        self.assertEqual(self._DELEGATE_JVM_FLAGS, command_line[1:3])
        classpath = command_line[cp_index + 1].split(os.pathsep)
        project_root = self._normalized_project_dir(project_dir)
        self.assertIn("/tmp/pyronaut-test.jar", classpath)
        if expect_project_jars:
            self.assertIn("/tmp/test.jar", classpath)
            self.assertIn("/tmp/runtime.jar", classpath)
            self.assertIn("/tmp/build.jar", classpath)
        else:
            self.assertNotIn("/tmp/test.jar", classpath)
            self.assertNotIn("/tmp/runtime.jar", classpath)
            self.assertNotIn("/tmp/build.jar", classpath)
        self.assertNotIn(str(Path(project_root) / "__pyronaut__" / "test-classes"), classpath)
        self.assertNotIn(str(Path(project_root) / "__pyronaut__" / "classes"), classpath)
        resources_path = str(Path(project_root) / resources_dir)
        test_resources_path = str(Path(project_root) / test_resources_dir)
        self.assertNotIn(resources_path, classpath)
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
    def _extract_native_system_properties(command_line, command: str) -> dict[str, str | None]:
        command_index = command_line.index(command)
        properties: dict[str, str | None] = {}
        for token in command_line[1:command_index]:
            if not token.startswith("-D"):
                continue
            name, has_value, value = token[2:].partition("=")
            properties[name] = value if has_value else None
        return properties

    @staticmethod
    def _write_test_resources_enabled(project_dir: Path, enabled: bool = True) -> None:
        (project_dir / "pyproject.toml").write_text(
            "[tool.pyronaut.test-resources]\n"
            f"enabled = {'true' if enabled else 'false'}\n",
            encoding="utf-8",
        )

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
        if not (project_dir / "pyproject.toml").exists():
            OrchestratorTest._write_test_resources_enabled(project_dir)

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

    def _assert_no_classpath_artifact_prefix(self, classpath: list[str], prefix: str) -> None:
        artifact_names = [Path(entry).name for entry in classpath]
        self.assertFalse(
            any(name.startswith(prefix) for name in artifact_names),
            f"Classpath should not contain an artifact starting with {prefix}: {artifact_names}",
        )

    def test_unknown_command_returns_usage_error(self):
        exit_code = cli.run(["unknown"], runner=self._runner_ok(), resolver=self._resolver(), platform_name="linux")
        self.assertEqual(cli.USAGE_ERROR, exit_code)

    def test_jvm_delegate_strips_orchestrator_only_options(self):
        self.assertEqual(
            ["run", "--project-dir", "/tmp/demo", "--tests", "example.Test"],
            cli._strip_orchestrator_only_args(
                [
                    "run",
                    "--progress",
                    "on",
                    "--local-repository",
                    "/tmp/m2",
                    "--project-dir",
                    "/tmp/demo",
                    "--tests",
                    "example.Test",
                ]
            ),
        )

    def test_run_processes_main_then_run(self):
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
            self.assertEqual(3, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"], executed[0])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "main"], executed[1])
            self._assert_run_delegate(executed[2], str(project_dir), ["--main-class", "example.Main"])

    def test_run_help_prints_orchestrator_help_without_lifecycle_phases(self):
        executed = []

        def runner(command_line, env=None):
            executed.append(command_line)
            return 0

        stdout = io.StringIO()
        with redirect_stdout(stdout):
            exit_code = cli.run(
                ["run", "--help"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual([], executed)
        help_text = stdout.getvalue()
        self.assertIn("Usage: pyronaut run", help_text)
        self.assertIn("Run a processed Pyronaut application or direct Java/Python sources", help_text)
        self.assertIn("--port=<port>", help_text)
        self.assertIn("--no-validate", help_text)
        self.assertIn("<source.java|source.py|source-dir>...", help_text)
        self.assertNotIn("pyronaut-run", help_text)

    def test_test_help_prints_orchestrator_help_without_lifecycle_phases(self):
        executed = []

        def runner(command_line, env=None):
            executed.append(command_line)
            return 0

        stdout = io.StringIO()
        with redirect_stdout(stdout):
            exit_code = cli.run(
                ["test", "--help"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual([], executed)
        help_text = stdout.getvalue()
        self.assertIn("Usage: pyronaut test", help_text)
        self.assertIn("Run tests for a processed Pyronaut application or direct Java/Python JUnit 5 sources", help_text)
        self.assertIn("--tests=<tests>", help_text)
        self.assertIn("--test-classes-dir=<testClassesDir>", help_text)
        self.assertIn("--continuous", help_text)
        self.assertIn("pyronaut test <source> -- <test-source>", help_text)
        self.assertNotIn("pyronaut-test", help_text)

    def test_run_help_colors_options_on_tty(self):
        class TtyStringIO(io.StringIO):
            def isatty(self):
                return True

        previous_no_color = os.environ.pop("NO_COLOR", None)
        try:
            stdout = TtyStringIO()

            cli._print_run_usage(stdout)

            help_text = stdout.getvalue()
            self.assertIn("\033[33m--port=<port>\033[0m", help_text)
            self.assertIn("\033[33m-h, --help\033[0m", help_text)
        finally:
            if previous_no_color is not None:
                os.environ["NO_COLOR"] = previous_no_color

    def test_run_processes_main_when_artifacts_already_exist(self):
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
            self.assertEqual(3, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"], executed[0])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "main"], executed[1])
            self._assert_run_delegate(executed[2], str(project_dir), ["--main-class", "example.Main"])

    def test_preflight_failure_stops_run(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "missing"
            project_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line):
                executed.append(command_line)
                if "pyronaut-processor" in command_line[0]:
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
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "main"], executed[1])

    def test_project_commands_are_supported_on_windows_with_native_launcher(self):
        executed = []
        with patch.dict(os.environ, {"PYRONAUT_DEV_NATIVE_EXECUTABLE": "C:/pyronaut-dev.cmd"}):
            exit_code = cli.run(
                ["install", "--project-dir", "C:/demo"],
                runner=lambda command_line: executed.append(command_line) or 0,
                resolver=self._resolver(),
                platform_name="win32",
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        self.assertEqual([["/tmp/pyronaut-install", "--project-dir", "C:/demo"]], executed)

    def test_windows_project_commands_require_native_launcher(self):
        exit_code = cli.run(["install"], runner=self._runner_ok(), resolver=self._resolver(), platform_name="win32")
        self.assertEqual(cli.PLATFORM_UNSUPPORTED, exit_code)

    def test_setup_remains_unsupported_on_windows(self):
        exit_code = cli.run(["setup"], runner=self._runner_ok(), resolver=self._resolver(), platform_name="win32")
        self.assertEqual(cli.PLATFORM_UNSUPPORTED, exit_code)

    def test_windows_batch_launchers_use_command_shell(self):
        with patch.object(cli.sys, "platform", "win32"):
            self.assertTrue(cli._uses_windows_batch_shell([r"C:\tools\pyronaut.cmd", "install"]))
            self.assertTrue(cli._uses_windows_batch_shell([r"C:\tools\pyronaut-install.bat"]))
            self.assertFalse(cli._uses_windows_batch_shell([r"C:\tools\pyronaut.exe"]))
        with patch.object(cli.sys, "platform", "linux"):
            self.assertFalse(cli._uses_windows_batch_shell(["pyronaut-install.bat"]))

    def test_windows_batch_delegate_runs_through_shell(self):
        launch = Mock()
        launch.environment.return_value = {}
        launch.pass_fds = ()
        process = MagicMock()
        process.wait.return_value = cli.SUCCESS

        with (
            patch.object(cli.sys, "platform", "win32"),
            patch.object(cli, "_launch_indicator", return_value=launch),
            patch.object(cli.subprocess, "Popen", return_value=process) as popen,
        ):
            exit_code = cli._run_subprocess([r"C:\tools\pyronaut-install.bat", "install"], {})

        self.assertEqual(cli.SUCCESS, exit_code)
        popen.assert_called_once_with(
            [r"C:\tools\pyronaut-install.bat", "install"], env={}, pass_fds=(), shell=True
        )

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

    def test_install_uses_jvm_delegate_by_default_when_pyronaut_dev_native_executable_is_available(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([["/tmp/pyronaut-install", "--project-dir", str(project_dir)]], executed)

    def test_external_install_uses_current_wheel_installer_by_default(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: Path(temp_dir) / "pyronaut-dev"
                if command_name == "pyronaut-dev" else None,
            ):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([["/tmp/pyronaut-install", "--project-dir", str(project_dir)]], executed)

    def test_install_uses_bundled_pyronaut_dev_native_executable_when_toolchain_native(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([[str(native_dev), "install", "--project-dir", str(project_dir)]], executed)

    def test_install_toolchain_native_fails_when_pyronaut_dev_native_executable_missing(self):
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )

            with patch.object(cli, "_bundled_native_executable", return_value=None), redirect_stderr(stderr):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir)],
                    runner=self._runner_ok(),
                    resolver=lambda name: None if name == "pyronaut-dev" else self._resolver()(name),
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing native delegated executable for pyronaut-dev", stderr.getvalue())

    def test_native_install_forwards_offline_and_local_repository_to_delegate(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n", encoding="utf-8"
            )

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda name: native_dev if name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir), "--offline", "--local-repository", "/tmp/m2"],
                    runner=lambda command_line, _env=None: executed.append(command_line) or 0,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertIn("--offline", executed[0])
        self.assertIn("--local-repository", executed[0])
        self.assertIn("/tmp/m2", executed[0])

    def test_install_rejects_invalid_toolchain_type(self):
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"jit\"\n",
                encoding="utf-8",
            )

            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["install", "--project-dir", str(project_dir)],
                    runner=self._runner_ok(),
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Invalid toolchain type in pyproject.toml. Use tool.pyronaut.toolchain.type = 'jvm' or 'native'", stderr.getvalue())

    def test_direct_source_invocation_uses_pyronaut_dev_native_executable(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "HelloController.py"
            source.write_text("print('ok')\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    [
                        "test",
                        "--port",
                        "8181",
                        "--property",
                        "a.b=c",
                        "--disable-test-resources",
                        str(source),
                        "--",
                        str(source),
                    ],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [[str(native_dev), "-Djava.home=/tmp/java-home", f"-Dpyronaut.dev.project.dir={Path.cwd().resolve()}", "-Dmicronaut.environments=test", "-Dmicronaut.graalvm.imagesingletons.enabled=false", "-Dpyronaut.dev.direct.restartable=true", "test", "--port", "8181", "--property", "a.b=c", "--disable-test-resources", str(source), "--", str(source)]],
            executed,
        )

    def test_direct_source_install_forwards_native_dev_compiler_classpath(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            bin_dir = root / "bin"
            lib_dir = root / "lib"
            bin_dir.mkdir()
            lib_dir.mkdir()
            native_dev = bin_dir / "pyronaut-dev"
            native_install = bin_dir / "pyronaut-install"
            native_dev.write_text("", encoding="utf-8")
            native_install.write_text("", encoding="utf-8")
            home = root / "home"
            compiler_jar = self._write_native_compile_descriptor(
                native_dev,
                home,
                [("io.micronaut", "micronaut-runtime", "5.1.0", "micronaut-runtime-5.1.0.jar")],
            )[0]
            source = root / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")
            captured = {}

            def runner(command_line, env=None):
                captured["command"] = command_line
                captured["env"] = env
                return 0

            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: {
                    "pyronaut-dev": native_dev,
                    "pyronaut-install": native_install,
                }.get(command_name),
            ), patch("pathlib.Path.home", return_value=home):
                exit_code = cli.run(
                    ["install", "--project-dir", str(root), str(source)],
                    runner_with_env=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [str(native_install), "--project-dir", str(root), str(source)],
            captured["command"],
        )
        self.assertEqual(str(compiler_jar.resolve()), captured["env"]["PYRONAUT_DIRECT_CLASSPATH"])

    def test_control_panel_option_without_source_uses_project_development(self):
        self.assertFalse(cli._looks_like_direct_source_invocation(["--control-panel"]))
        self.assertFalse(cli._looks_like_direct_source_invocation(["--port", "8080"]))
        self.assertFalse(cli._looks_like_direct_source_invocation(["-Dmicronaut.server.port=8080"]))
        self.assertTrue(cli._looks_like_direct_source_invocation(["--control-panel", "app.py"]))
        jvm_args = cli._build_direct_source_native_jvm_args(
            "/tmp/pyronaut-dev",
            {"JAVA_HOME": "/tmp/java-home"},
            command="dev",
            environment="dev",
            args=["--control-panel", "app.py"],
        )
        self.assertIn("-Dmicronaut.control-panel.enabled=true", jvm_args)
        self.assertIn("-Dmicronaut.control-panel.path=/control-panel", jvm_args)
        self.assertIn("-Dmicronaut.control-panel.security.access=ANONYMOUS", jvm_args)

    def test_direct_control_panel_flag_is_preserved_for_native_jvm_properties(self):
        captured = {}
        with tempfile.TemporaryDirectory() as temp_dir:
            previous_cwd = Path.cwd()
            try:
                os.chdir(temp_dir)
                (Path(temp_dir) / "app.py").write_text("", encoding="utf-8")
                def capture(command, env=None):
                    captured["command"] = command
                    return 0
                with patch.object(cli, "_resolve_direct_source_dev_executable", return_value="/tmp/pyronaut-dev"):
                    with patch.object(cli, "_direct_control_panel_classpath_entries", return_value=["/tmp/control-panel.jar"]):
                        exit_code = cli._delegate_direct_source(
                            "dev",
                            ["--control-panel", "app.py"],
                            capture,
                            lambda _: "/tmp/pyronaut-dev",
                            java_home_provider=lambda: "/tmp/java-home",
                        )
            finally:
                os.chdir(previous_cwd)

        self.assertEqual(0, exit_code)
        self.assertIn("-Dpyronaut.dev.control.panel.class.path=/tmp/control-panel.jar", captured["command"])
        # pyronaut-dev loads the panels with the direct-source runtime classloader.
        self.assertFalse(any(value.startswith("-Djava.class.path=") for value in captured["command"]))

    def test_optional_control_panels_are_selected_from_application_dependencies(self):
        bundled = [
            "/tools/lib/control-panel/micronaut-control-panel-ui-2.1.0.jar",
            "/tools/lib/control-panel/micronaut-control-panel-datasource-2.1.0.jar",
            "/tools/lib/control-panel/micronaut-control-panel-hibernate-2.1.0.jar",
            "/tools/lib/control-panel/micronaut-control-panel-kafka-2.1.0.jar",
            "/tools/lib/control-panel/micronaut-control-panel-object-storage-2.1.0.jar",
            "/tools/lib/control-panel/micronaut-control-panel-cache-2.1.0.jar",
        ]

        self.assertEqual(
            [bundled[0]],
            cli._select_control_panel_entries(bundled, ["/m2/micronaut-jdbc-hikari-7.2.0.jar"]),  # noqa: SLF001
        )
        self.assertEqual(
            [bundled[0], bundled[1], bundled[3]],
            cli._select_control_panel_entries(bundled, [  # noqa: SLF001
                "/m2/io/micronaut/sql/micronaut-jdbc/7.2.0/micronaut-jdbc-7.2.0.jar",
                "/gradle/io.micronaut.kafka/micronaut-kafka/6.0.0/hash/micronaut-kafka-6.0.0.jar",
                "/project/__pyronaut__/classes",
            ]),
        )
        self.assertEqual(
            bundled,
            cli._select_control_panel_entries(bundled, [  # noqa: SLF001
                "/m2/micronaut-jdbc-7.2.0.jar",
                "/m2/hibernate-core-7.1.0.Final.jar",
                "/m2/micronaut-kafka-6.0.0.jar",
                "/m2/micronaut-object-storage-core-3.0.0.jar",
                "/m2/micronaut-cache-caffeine-6.1.1.jar",
            ]),
        )

    def test_direct_control_panel_keeps_every_panel_off_the_launcher_classpath(self):
        # Control Panel core reads each panel's default configuration through
        # its own classloader, and optional panels need the application's
        # libraries. Splitting them between java.class.path and the
        # direct-source runtime classloader left the datasource panel without
        # its "micronaut.control-panel.panels.datasource" configuration.
        captured = {}
        bundled = [
            "/tmp/micronaut-control-panel-core-2.1.0.jar",
            "/tmp/micronaut-control-panel-ui-2.1.0.jar",
            "/tmp/micronaut-control-panel-datasource-2.1.0.jar",
            "/tmp/micronaut-control-panel-kafka-2.1.0.jar",
        ]
        application = os.pathsep.join([
            "/m2/micronaut-jdbc-7.2.0.jar",
            "/m2/micronaut-control-panel-core-2.1.0.jar",
        ])
        with tempfile.TemporaryDirectory() as temp_dir:
            previous_cwd = Path.cwd()
            try:
                os.chdir(temp_dir)
                (Path(temp_dir) / "app.py").write_text("", encoding="utf-8")
                (Path(temp_dir) / "__pyronaut__").mkdir()
                def capture(command, env=None):
                    captured["command"] = command
                    return 0
                with patch.object(cli, "_resolve_direct_source_dev_executable", return_value="/tmp/pyronaut-dev"), \
                        patch.object(cli, "_direct_control_panel_classpath_entries", return_value=bundled), \
                        patch.object(cli, "_build_native_application_classpath", return_value=application):
                    exit_code = cli._delegate_direct_source(
                        "dev",
                        ["--control-panel", "app.py"],
                        capture,
                        lambda _: "/tmp/pyronaut-dev",
                        java_home_provider=lambda: "/tmp/java-home",
                    )
            finally:
                os.chdir(previous_cwd)

        self.assertEqual(0, exit_code)
        self.assertIn("-Djava.class.path=/m2/micronaut-jdbc-7.2.0.jar", captured["command"])
        self.assertIn("-Dpyronaut.dev.application.class.path=" + application, captured["command"])
        # pyronaut-dev selects optional panels after resolving direct-source
        # dependency declarations, so it receives every bundled module.
        self.assertIn(
            "-Dpyronaut.dev.control.panel.class.path=" + os.pathsep.join(bundled),
            captured["command"],
        )

    def test_control_panel_is_not_enabled_by_generated_schema_or_development_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut]\n[tool.pyronaut.dependencies]\nruntime = []\n",
                encoding="utf-8",
            )
            self.assertFalse(cli._control_panel_enabled_for_project(project_dir))

    def test_control_panel_dependency_is_an_explicit_opt_in(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.dependencies]\ndevelopment-runtime = [\"io.micronaut.controlpanel:micronaut-control-panel-ui\"]\n",
                encoding="utf-8",
            )
            self.assertTrue(cli._control_panel_enabled_for_project(project_dir))

    def test_dev_detects_standalone_main_python_source(self):
        previous_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "main.py").write_text("print('ok')\n", encoding="utf-8")
            try:
                os.chdir(project_dir)
                with patch.object(cli, "_run_direct_source", return_value=0) as direct_source:
                    exit_code = cli.run(
                        [
                            "dev",
                            "--port",
                            "8181",
                            "--property",
                            "example.enabled=true",
                            "--control-panel",
                        ],
                        runner=self._runner_ok(),
                        resolver=self._resolver(),
                        platform_name="linux",
                        java_home_provider=lambda: "/tmp/java-home",
                    )
            finally:
                os.chdir(previous_cwd)

        self.assertEqual(0, exit_code)
        direct_source.assert_called_once()
        self.assertEqual(
            [
                "--port",
                "8181",
                "--property",
                "example.enabled=true",
                "--control-panel",
                "main.py",
            ],
            direct_source.call_args.args[1],
        )

    def test_dev_explicit_source_remains_authoritative_when_main_python_exists(self):
        previous_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "main.py").write_text("print('default')\n", encoding="utf-8")
            source = project_dir / "app.py"
            source.write_text("print('explicit')\n", encoding="utf-8")
            source_dir = project_dir / "src"
            source_dir.mkdir()
            try:
                os.chdir(project_dir)
                for selector in (source.name, source_dir.name):
                    with self.subTest(selector=selector):
                        with patch.object(cli, "_run_direct_source", return_value=0) as direct_source:
                            exit_code = cli.run(
                                ["dev", selector],
                                runner=self._runner_ok(),
                                resolver=self._resolver(),
                                platform_name="linux",
                                java_home_provider=lambda: "/tmp/java-home",
                            )
                        self.assertEqual(0, exit_code)
                        self.assertEqual([selector], direct_source.call_args.args[1])
            finally:
                os.chdir(previous_cwd)

    def test_dev_does_not_detect_main_python_source_in_project(self):
        previous_cwd = Path.cwd()
        project_markers = (
            "pyproject.toml",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "main.py").write_text("print('ok')\n", encoding="utf-8")
            try:
                os.chdir(project_dir)
                for marker in project_markers:
                    marker_file = project_dir / marker
                    marker_file.write_text("", encoding="utf-8")
                    self.assertIsNone(cli._default_dev_source([]))
                    marker_file.unlink()
                self.assertIsNone(cli._default_dev_source(["--project-dir", str(project_dir)]))
            finally:
                os.chdir(previous_cwd)

    def test_dev_without_main_python_source_stays_in_project_mode(self):
        previous_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temp_dir:
            try:
                os.chdir(temp_dir)
                self.assertIsNone(cli._default_dev_source([]))
            finally:
                os.chdir(previous_cwd)

    def test_run_and_test_do_not_detect_main_python_source(self):
        previous_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "main.py").write_text("print('ok')\n", encoding="utf-8")
            try:
                os.chdir(project_dir)
                for command in ("run", "test"):
                    with self.subTest(command=command):
                        with (
                            patch.object(cli, "_run_direct_source", return_value=0) as direct_source,
                            redirect_stderr(io.StringIO()),
                        ):
                            cli.run(
                                [command],
                                runner=self._runner_ok(),
                                resolver=self._resolver(),
                                platform_name="linux",
                            )
                        direct_source.assert_not_called()
            finally:
                os.chdir(previous_cwd)

    def test_run_direct_python_script_uses_pyronaut_dev_native_executable(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "controller.py"
            source.write_text("print('ok')\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["run", "--progress", "on", str(source)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [[
                str(native_dev),
                "-Djava.home=/tmp/java-home",
                f"-Dpyronaut.dev.project.dir={Path.cwd().resolve()}",
                "-Dmicronaut.control-panel.enabled=false",
                "-Dmicronaut.graalvm.imagesingletons.enabled=false",
                str(source),
            ]],
            executed,
        )

    def test_run_direct_source_with_explicit_build_mode_is_detected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "app.py"
            source.write_text("print('ok')\n", encoding="utf-8")
            for mode in ("--native", "--jvm", "--mode=native", "--mode", "jvm"):
                with self.subTest(mode=mode):
                    args = [mode, str(source)] if mode not in {"--mode", "jvm"} else ["--mode", "jvm", str(source)]
                    self.assertTrue(cli._looks_like_direct_source_invocation(args))

    def test_run_direct_java_script_uses_pyronaut_dev_native_executable(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["run", str(source)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [[
                str(native_dev),
                "-Djava.home=/tmp/java-home",
                f"-Dpyronaut.dev.project.dir={Path.cwd().resolve()}",
                "-Dmicronaut.control-panel.enabled=false",
                "-Dmicronaut.graalvm.imagesingletons.enabled=false",
                str(source),
            ]],
            executed,
        )

    def test_run_direct_java_script_auto_provisions_graalvm_when_provider_is_omitted(self):
        executed = []
        provisioning_calls = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            with patch.object(
                cli,
                "_ensure_graalvm_java_home",
                side_effect=lambda project_dir=None: provisioning_calls.append(project_dir) or "/tmp/provisioned-graalvm-25",
            ), patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None,
            ):
                exit_code = cli.run(
                    ["run", str(source)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(provisioning_calls))
        self.assertEqual(Path.cwd(), provisioning_calls[0])
        self.assertEqual("/tmp/provisioned-graalvm-25", executed[0][1]["JAVA_HOME"])

    def test_test_direct_java_sources_use_pyronaut_dev_native_executable(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "App.java"
            test = Path(temp_dir) / "AppTest.java"
            source.write_text("class App {}\n", encoding="utf-8")
            test.write_text("class AppTest {}\n", encoding="utf-8")

            def runner(command_line, env=None):
                return 0

            class CompletedProcess:
                def poll(self):
                    return 0

                def terminate(self):
                    return None

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    return None

            def stop_after_initial_run(_seconds):
                raise KeyboardInterrupt

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["test", "-t", str(source), "--", str(test)],
                    runner=runner,
                    process_runner=lambda command_line, env=None: (executed.append(command_line) or CompletedProcess()),
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                    sleep=stop_after_initial_run,
                )

        self.assertEqual(130, exit_code)
        self.assertEqual(1, len(executed))
        self.assertEqual(str(native_dev), executed[0][0])
        self.assertIn("-Dmicronaut.graalvm.imagesingletons.enabled=false", executed[0])
        self.assertIn("-Dpyronaut.dev.direct.restartable=true", executed[0])
        self.assertEqual(["test", str(source), "--", str(test)], executed[0][-4:])

    def test_run_direct_python_directory_uses_pyronaut_dev_native_executable(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source_dir = Path(temp_dir) / "src"
            source_dir.mkdir()
            (source_dir / "controller.py").write_text("print('ok')\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["run", str(source_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        self.assertEqual(str(native_dev), executed[0][0])
        self.assertIn("-Dmicronaut.control-panel.enabled=false", executed[0])
        self.assertIn("-Dmicronaut.graalvm.imagesingletons.enabled=false", executed[0])
        self.assertEqual(str(source_dir), executed[0][-1])

    def test_dev_direct_python_script_restarts_when_source_changes(self):
        started = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "controller.py"
            source.write_text("print('v1')\n", encoding="utf-8")

            class FakeProcess:
                def __init__(self, exit_after_polls=100):
                    self.exit_after_polls = exit_after_polls
                    self.polls = 0
                    self.terminated = False

                def poll(self):
                    if self.terminated:
                        return 0
                    self.polls += 1
                    if self.polls >= self.exit_after_polls:
                        return 0
                    return None

                def terminate(self):
                    self.terminated = True

                def wait(self, timeout=None):
                    return 0

                def kill(self):
                    self.terminated = True

            first_process = FakeProcess()
            processes = [first_process, FakeProcess(exit_after_polls=1)]

            def process_runner(command_line, env=None):
                started.append(command_line)
                return processes.pop(0)

            ticks = {"count": 0}
            now = {"value": 0.0}

            def monotonic():
                return now["value"]

            def sleep(seconds):
                now["value"] += seconds
                ticks["count"] += 1
                if ticks["count"] == 1:
                    source.write_text("print('v2')\n", encoding="utf-8")
                if ticks["count"] > 50:
                    raise TimeoutError("Test did not trigger direct-source restart within expected ticks")

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["dev", str(source)],
                    process_runner=process_runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/java-home",
                    watch_poll_interval=0.05,
                    watch_debounce_seconds=0.05,
                    monotonic=monotonic,
                    sleep=sleep,
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(2, len(started))
        self.assertEqual(
            [
                str(native_dev),
                "-Djava.home=/tmp/java-home",
                f"-Dpyronaut.dev.project.dir={Path.cwd().resolve()}",
                "-Dmicronaut.environments=dev",
                "-Dpyronaut.dev.direct.command=dev",
                "-Dmicronaut.control-panel.enabled=false",
                "-Dmicronaut.graalvm.imagesingletons.enabled=false",
                "-Dpyronaut.dev.direct.restartable=true",
                str(source),
            ],
            started[0],
        )
        self.assertTrue(first_process.terminated)

    def test_direct_source_native_compiler_classpath_does_not_duplicate_native_image_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            install_root = Path(temp_dir) / "pyronaut-dev"
            bin_dir = install_root / "bin"
            lib_dir = install_root / "lib"
            bin_dir.mkdir(parents=True)
            lib_dir.mkdir()
            native_dev = bin_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (bin_dir / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-context-python\n",
                encoding="utf-8",
            )
            home = Path(temp_dir) / "home"
            self._write_native_compile_descriptor(
                native_dev,
                home,
                [
                    ("io.micronaut", "micronaut-context-python", "5.1.0", "micronaut-context-python-5.1.0.jar"),
                    ("io.micronaut", "micronaut-inject-python", "5.1.0", "micronaut-inject-python-5.1.0.jar"),
                    ("io.micronaut", "micronaut-runtime", "5.1.0", "micronaut-runtime-5.1.0.jar"),
                ],
            )

            with patch("pathlib.Path.home", return_value=home):
                jvm_args = cli._build_direct_source_native_jvm_args(str(native_dev), {"JAVA_HOME": "/tmp/java-home"})

        self.assertEqual(2, len(jvm_args))
        self.assertEqual("-Djava.home=/tmp/java-home", jvm_args[0])
        compiler_arg = next(arg for arg in jvm_args if arg.startswith("-Dpyronaut.dev.compiler.class.path="))
        self.assertIn("micronaut-context-python-5.1.0.jar", compiler_arg)
        self.assertIn("micronaut-inject-python-5.1.0.jar", compiler_arg)

    def test_native_provided_jars_resolve_from_packaged_native_lib(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dir = Path(temp_dir) / "tools" / "pyronaut-dev" / "native"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (native_dir / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-jdbc\n", encoding="utf-8"
            )
            native_lib = native_dir / "lib"
            native_lib.mkdir()
            binary = native_lib / "micronaut-jdbc-7.1.0.jar"
            source = native_lib / "micronaut-jdbc-7.1.0-sources.jar"
            binary.write_text("", encoding="utf-8")
            source.write_text("", encoding="utf-8")

            jars = cli._native_launcher_provided_jar_entries(str(native_dev))  # noqa: SLF001
            coordinates = cli._native_launcher_provided_artifact_coordinates(str(native_dev))  # noqa: SLF001

        self.assertEqual([str(binary.resolve())], jars)
        self.assertEqual({"io.micronaut:micronaut-jdbc"}, coordinates)

    def test_native_dev_command_passes_provided_jar_directories(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            install_dir = Path(temp_dir) / "tools" / "pyronaut-dev"
            bin_dir = install_dir / "bin"
            lib_dir = install_dir / "lib"
            bin_dir.mkdir(parents=True)
            lib_dir.mkdir()
            native_dev = bin_dir / "pyronaut-dev.cmd"
            native_dev.write_text("", encoding="utf-8")
            (bin_dir / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-jdbc\nio.micronaut:micronaut-core\n",
                encoding="utf-8",
            )
            (lib_dir / "micronaut-jdbc-7.1.0.jar").write_text("", encoding="utf-8")
            (lib_dir / "micronaut-core-5.2.0.jar").write_text("", encoding="utf-8")
            project_dir = Path(temp_dir) / "project"
            project_dir.mkdir()

            with patch.object(cli, "_use_pyronaut_dev_native_toolchain", return_value=True), patch.object(
                cli, "_resolve_pyronaut_dev_native_executable", return_value=str(native_dev)
            ):
                command_line = cli._pyronaut_dev_native_command_line(
                    "install", ["--project-dir", str(project_dir)], self._resolver()
                )

        assert command_line is not None
        jars_arg = next(arg for arg in command_line if arg.startswith("-Dpyronaut.dev.native.provided.jars="))
        self.assertEqual(f"-Dpyronaut.dev.native.provided.jars={lib_dir.resolve()}", jars_arg)
        self.assertLess(len(jars_arg), 256)

    def test_native_provided_coordinates_keep_same_artifact_id_from_different_group(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            bin_dir = Path(temp_dir) / "bin"
            bin_dir.mkdir()
            native_dev = bin_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (bin_dir / "native-provided-classpath.txt").write_text(
                "tools.jackson.core:jackson-databind\n", encoding="utf-8"
            )

            entries = [
                "com.fasterxml.jackson.core:jackson-databind",
                "tools.jackson.core:jackson-databind",
            ]
            filtered = cli._filter_native_launcher_provided_entries(  # noqa: SLF001
                entries, str(native_dev), "dev"
            )

        self.assertEqual(["com.fasterxml.jackson.core:jackson-databind"], filtered)

    def test_delegate_lib_entries_resolve_wheel_shared_classpath_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            tools = Path(temp_dir) / "tools"
            tool_dir = tools / "pyronaut-run"
            (tool_dir / "bin").mkdir(parents=True)
            shared_lib = tools / "shared" / "lib"
            shared_lib.mkdir(parents=True)
            executable = tool_dir / "bin" / "pyronaut-run"
            executable.write_text("", encoding="utf-8")
            first = shared_lib / "first-1.0.jar"
            second = shared_lib / "second-1.0.jar"
            first.write_text("first", encoding="utf-8")
            second.write_text("second", encoding="utf-8")
            (tool_dir / "bin" / "pyronaut-classpath.txt").write_text(
                "second-1.0.jar\nfirst-1.0.jar\n", encoding="utf-8"
            )

            entries = cli._delegate_lib_entries(str(executable))  # noqa: SLF001

        self.assertEqual([str(second.resolve()), str(first.resolve())], entries)

    def test_stage_delegate_distribution_copies_wheel_shared_sibling(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            install_root = root / "tools" / "pyronaut-run"
            (install_root / "bin").mkdir(parents=True)
            executable = install_root / "bin" / "pyronaut-run"
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            shared_jar = root / "tools" / "shared" / "lib" / "runtime.jar"
            shared_jar.parent.mkdir(parents=True)
            resolved_jar = root / "repository" / "runtime.jar"
            resolved_jar.parent.mkdir(parents=True)
            resolved_jar.write_text("shared", encoding="utf-8")
            shared_jar.symlink_to(resolved_jar)
            target = root / "context" / "tools" / "pyronaut-run"

            cli._stage_delegate_distribution(str(executable), target)  # noqa: SLF001

            staged_jar = target.parent / "shared" / "lib" / "runtime.jar"
            self.assertEqual("shared", staged_jar.read_text(encoding="utf-8"))
            self.assertFalse(staged_jar.is_symlink())

    def test_native_provided_jars_resolve_from_wheel_shared_lib_with_source_sibling(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            tools = Path(temp_dir) / "tools"
            native_dir = tools / "pyronaut-dev" / "native"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (native_dir / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-jdbc\n", encoding="utf-8"
            )
            shared_lib = tools / "shared" / "lib"
            shared_lib.mkdir(parents=True)
            binary = shared_lib / "micronaut-jdbc-7.1.0.jar"
            source = shared_lib / "micronaut-jdbc-7.1.0-sources.jar"
            binary.write_text("binary", encoding="utf-8")
            source.write_text("source", encoding="utf-8")

            jars = cli._native_launcher_provided_jar_entries(str(native_dev))  # noqa: SLF001

        self.assertEqual([str(binary.resolve())], jars)

    def test_native_compile_classpath_resolves_source_checkout_maven_repository(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            tools = Path(temp_dir) / "tools"
            native_dir = tools / "pyronaut-dev" / "native"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            home = Path(temp_dir) / "home"
            binary = self._write_native_compile_descriptor(
                native_dev,
                home,
                [("io.micronaut", "micronaut-inject-python", "5.2.3", "micronaut-inject-python-5.2.3.jar")],
            )[0]

            with patch("pathlib.Path.home", return_value=home):
                entries = cli._native_launcher_compile_classpath_entries(str(native_dev))  # noqa: SLF001

        self.assertEqual([str(binary.resolve())], entries)

    def test_native_compile_classpath_falls_back_to_project_local_repository(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dir = root / "tools" / "pyronaut-dev" / "native"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            filename = "micronaut-inject-python-5.2.3.jar"
            descriptor = "\t".join((
                "maven", "io.micronaut", "micronaut-inject-python", "5.2.3", "jar", "", filename
            ))
            (native_dir / "native-compile-classpath.txt").write_text(descriptor + "\n", encoding="utf-8")
            project = root / "project"
            artifact = project / ".pyronaut-m2" / "io" / "micronaut" / "micronaut-inject-python" / "5.2.3" / filename
            artifact.parent.mkdir(parents=True)
            artifact.write_text("inject-python", encoding="utf-8")

            with patch("pathlib.Path.cwd", return_value=project):
                with patch.dict(os.environ, {cli.LOCAL_REPOSITORY_ENV: str(root / "missing-repository")}):
                    entries = cli._native_launcher_compile_classpath_entries(str(native_dev))  # noqa: SLF001

        self.assertEqual([str(artifact.resolve())], entries)

    def test_native_compile_classpath_uses_explicit_local_repository(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dir = root / "tools" / "pyronaut-dev" / "native"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            filename = "micronaut-inject-python-5.2.3.jar"
            descriptor = "\t".join((
                "maven", "io.micronaut", "micronaut-inject-python", "5.2.3", "jar", "", filename
            ))
            (native_dir / "native-compile-classpath.txt").write_text(descriptor + "\n", encoding="utf-8")
            repository = root / "explicit-repository"
            artifact = repository / "io" / "micronaut" / "micronaut-inject-python" / "5.2.3" / filename
            artifact.parent.mkdir(parents=True)
            artifact.write_text("inject-python", encoding="utf-8")

            with (
                patch.object(cli, "_setup_is_required", return_value=True),
                patch.dict(os.environ, {cli.LOCAL_REPOSITORY_ENV: str(root / "missing-repository")}),
            ):
                entries = cli._native_launcher_compile_classpath_entries(  # noqa: SLF001
                    str(native_dev), str(repository)
                )

        self.assertEqual([str(artifact.resolve())], entries)

    def test_native_compile_classpath_falls_back_to_launcher_distribution_lib(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dir = root / "build" / "native" / "nativeCompile"
            native_dir.mkdir(parents=True)
            native_dev = native_dir / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            filename = "micronaut-inject-python-5.2.3.jar"
            (native_dir / "native-compile-classpath.txt").write_text(
                "\t".join(("maven", "io.micronaut", "micronaut-inject-python", "5.2.3", "jar", "", filename)) + "\n",
                encoding="utf-8",
            )
            distribution_lib = root / "build" / "install" / "micronaut-pyronaut-dev" / "lib"
            distribution_lib.mkdir(parents=True)
            artifact = distribution_lib / filename
            artifact.write_text("inject-python", encoding="utf-8")

            entries = cli._native_launcher_compile_classpath_entries(str(native_dev))  # noqa: SLF001

        self.assertEqual([str(artifact.resolve())], entries)

    def test_run_prefers_bundled_production_native_executable_with_application_classpath(self):
        executed = []
        original_test_resources_disabled = os.environ.get("PYRONAUT_TEST_RESOURCES_DISABLED")
        os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = "true"
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                native_dev = Path(temp_dir) / "pyronaut-dev"
                native_dev.write_text("", encoding="utf-8")
                native_dev.chmod(0o755)
                native_run = Path(temp_dir) / "pyronaut-run"
                native_run.write_text("", encoding="utf-8")
                native_run.chmod(0o755)
                project_dir = Path(temp_dir) / "demo"
                (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
                (project_dir / "app-config").mkdir(parents=True, exist_ok=True)
                (project_dir / "views").mkdir(parents=True, exist_ok=True)
                self._write_manifests(project_dir, development_runtime=True)
                (project_dir / "__pyronaut__" / "resolved-development-runtime-dependencies").write_text(
                    "\n".join(
                        [
                            "/tmp/runtime-dev.jar",
                            "/tmp/micronaut-test-resources-client-4.0.0.jar",
                            "/tmp/micronaut-test-resources-core-4.0.0.jar",
                            "/tmp/micronaut-test-resources-codec-4.0.0.jar",
                        ]
                    )
                    + "\n",
                    encoding="utf-8",
                )
                (project_dir / "pyproject.toml").write_text(
                    """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.toolchain]
type = "native"

[tool.pyronaut.sources]
resources = "app-config"
additional-resources = ["views"]
""".strip()
                    + "\n",
                    encoding="utf-8",
                )

                def runner(command_line, env=None):
                    executed.append(command_line)
                    return 0

                with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else native_run if command_name == "pyronaut-run" else None):
                    with redirect_stderr(io.StringIO()):
                        exit_code = cli.run(
                            ["run", "--project-dir", str(project_dir), "--main-class", "example.Main"],
                            runner=runner,
                            resolver=self._resolver(),
                            platform_name="linux",
                        )
        finally:
            if original_test_resources_disabled is None:
                os.environ.pop("PYRONAUT_TEST_RESOURCES_DISABLED", None)
            else:
                os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = original_test_resources_disabled

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"],
                [str(native_dev), "process", "--project-dir", str(project_dir), "--pass", "main"],
            ],
            executed[:2],
        )
        run_command = executed[2]
        self.assertNotIn("-cp", run_command)
        self.assertEqual(str(native_run), run_command[0])
        properties = self._extract_native_system_properties(run_command, "--project-dir")
        classpath = properties["java.class.path"].split(os.pathsep)
        self.assertEqual("/tmp/runtime.jar", classpath[0])
        self.assertIn(str((project_dir / "__pyronaut__" / "classes").resolve()), classpath)
        self.assertIn(str((project_dir / "app-config").resolve()), classpath)
        self.assertIn(str((project_dir / "views").resolve()), classpath)
        self.assertNotIn("/tmp/pyronaut-run.jar", classpath)
        self.assertEqual(["--project-dir", str(project_dir), "--main-class", "example.Main"], run_command[run_command.index("--project-dir") :])

    def test_native_run_selects_python_production_runtime(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True)
            runtime = Path(temp_dir) / "micronaut-context-python-5.1.0.jar"
            runtime.write_text("", encoding="utf-8")
            (cache_dir / "resolved-runtime-dependencies").write_text(f"{runtime}\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.toolchain]
type = "native"
""".strip() + "\n",
                encoding="utf-8",
            )
            native_run_python = Path(temp_dir) / "pyronaut-run-python"
            native_run_python.write_text("", encoding="utf-8")
            native_run_python.chmod(0o755)

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda name: native_run_python if name == "pyronaut-run-python" else None):
                command_line = cli._pyronaut_run_native_command_line(  # noqa: SLF001
                    "run",
                    ["--project-dir", str(project_dir), "--offline", "--no-validate"],
                    self._resolver(),
                )

        self.assertIsNotNone(command_line)
        self.assertEqual(str(native_run_python), command_line[0])
        self.assertNotIn("--offline", command_line)
        self.assertNotIn("--no-validate", command_line)

    def test_native_runner_does_not_receive_orchestrator_only_options(self):
        self.assertEqual(
            ["--project-dir", "/tmp/app"],
            cli._strip_orchestrator_only_args(  # noqa: SLF001
                ["--project-dir", "/tmp/app", "--offline", "--no-validate", "--local-repository", "/tmp/m2"]
            ),
        )

    def test_native_application_classpath_filters_only_manifested_native_image_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            (native_dev.parent / "native-provided-classpath.txt").write_text(
                "\n".join(
                    [
                        "micronaut-context-python-5.1.0.jar",
                        "micronaut-runtime-5.1.0.jar",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache_dir / "resolved-development-runtime-dependencies").write_text(
                "\n".join(
                    [
                        "/tmp/micronaut-context-python-5.1.0.jar",
                        "/tmp/micronaut-runtime-5.1.0.jar",
                        "/tmp/micronaut-runtime-5.2.3.jar",
                        "/tmp/micronaut-views-core-6.0.0.jar",
                        "/tmp/org.graalvm.polyglot-coverage-25.1.3.pom",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_native_application_classpath("dev", project_dir.resolve(), str(native_dev))  # noqa: SLF001

        entries = classpath.split(os.pathsep)
        self.assertNotIn("/tmp/micronaut-context-python-5.1.0.jar", entries)
        self.assertNotIn("/tmp/micronaut-runtime-5.1.0.jar", entries)
        self.assertNotIn("/tmp/micronaut-runtime-5.2.3.jar", entries)
        self.assertNotIn("/tmp/org.graalvm.polyglot-coverage-25.1.3.pom", entries)
        self.assertIn("/tmp/micronaut-views-core-6.0.0.jar", entries)

    def test_native_application_classpath_keeps_serde_runtime_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (native_dev.parent / "native-provided-classpath.txt").write_text(
                "io.micronaut.serde:micronaut-serde-api\n"
                "io.micronaut.serde:micronaut-serde-jackson\n"
                "io.micronaut.serde:micronaut-serde-support\n"
                "io.micrometer:micrometer-core\n",
                encoding="utf-8",
            )
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "classes").mkdir()
            (cache_dir / "resolved-runtime-dependencies").write_text(
                "\n".join(
                    [
                        "/tmp/micronaut-serde-api-3.1.0.jar",
                        "/tmp/micronaut-serde-jackson-3.1.0.jar",
                        "/tmp/micronaut-serde-support-3.1.0.jar",
                        "/tmp/micrometer-core-1.16.5.jar",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_native_application_classpath("run", project_dir, str(native_dev))  # noqa: SLF001

        entries = classpath.split(os.pathsep)
        self.assertEqual(5, len(entries))
        self.assertTrue(all(any(name in entry for entry in entries) for name in (
            "micronaut-serde-api-3.1.0.jar",
            "micronaut-serde-jackson-3.1.0.jar",
            "micronaut-serde-support-3.1.0.jar",
            "micrometer-core-1.16.5.jar",
        )))

    def test_native_application_classpath_drops_macos_watch_service_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            (native_dev.parent / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-context\n",
                encoding="utf-8",
            )
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "classes").mkdir()
            (cache_dir / "resolved-development-runtime-dependencies").write_text(
                "\n".join(
                    [
                        "/tmp/micronaut-runtime-osx-5.2.10.jar",
                        "/tmp/directory-watcher-0.19.1.jar",
                        "/tmp/micronaut-views-core-6.0.0.jar",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_native_application_classpath("dev", project_dir, str(native_dev))  # noqa: SLF001

        entries = classpath.split(os.pathsep)
        self.assertFalse(any("micronaut-runtime-osx" in entry for entry in entries))
        self.assertFalse(any("directory-watcher" in entry for entry in entries))
        self.assertIn("/tmp/micronaut-views-core-6.0.0.jar", entries)

    def test_native_application_classpath_keeps_control_panel_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            (native_dev.parent / "native-provided-classpath.txt").write_text(
                "micronaut-context-python-5.1.0.jar\n",
                encoding="utf-8",
            )

            control_panel_dir = native_dev.parent.parent / "lib" / "control-panel"
            control_panel_dir.mkdir(parents=True, exist_ok=True)
            for name in ("micronaut-control-panel-core-2.0.0.jar", "micronaut-control-panel-ui-2.0.0.jar"):
                (control_panel_dir / name).write_text("", encoding="utf-8")

            entries = cli._direct_control_panel_classpath_entries(str(native_dev))  # noqa: SLF001

        self.assertIn(str(control_panel_dir / "micronaut-control-panel-core-2.0.0.jar"), entries)
        self.assertIn(str(control_panel_dir / "micronaut-control-panel-ui-2.0.0.jar"), entries)

    def test_native_compile_binary_uses_generated_classpath_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "build" / "native" / "nativeCompile" / "pyronaut-dev"
            native_dev.parent.mkdir(parents=True)
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            manifest = root / "build" / "generated" / "native-classpaths" / "native-provided-classpath.txt"
            manifest.parent.mkdir(parents=True)
            manifest.write_text("org.graalvm.polyglot:polyglot\n", encoding="utf-8")

            self.assertEqual(
                ["org.graalvm.polyglot:polyglot"],
                cli._native_launcher_manifest_entries(str(native_dev), "native-provided-classpath.txt"),  # noqa: SLF001
            )

    def test_external_layout_native_classpath_uses_persisted_resources_and_filters_provided_artifacts(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            (root / "native-provided-classpath.txt").write_text(
                "io.micronaut:micronaut-context\n", encoding="utf-8"
            )
            project_dir = root / "external"
            cache_dir = project_dir / "build" / "pyronaut"
            classes_dir = cache_dir / "classes"
            main_resources = project_dir / "src/main/resources"
            test_resources = project_dir / "src/test/resources"
            classes_dir.mkdir(parents=True)
            main_resources.mkdir(parents=True)
            test_resources.mkdir(parents=True)
            retained = project_dir / "lib" / "example-1.0.jar"
            provided = project_dir / "repository" / "io" / "micronaut" / "micronaut-context" / "4.0" / "micronaut-context-4.0.jar"
            retained.parent.mkdir(parents=True)
            provided.parent.mkdir(parents=True)
            retained.write_text("", encoding="utf-8")
            provided.write_text("", encoding="utf-8")
            (project_dir / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
            (cache_dir / "project-layout.properties").write_text(
                "kind=GRADLE\n"
                f"testClasspath={provided}{os.pathsep}{retained}\n"
                f"mainResources={main_resources}\n"
                f"testResources={test_resources}\n",
                encoding="utf-8",
            )

            entries = cli._build_native_application_classpath("test", project_dir, str(native_dev)).split(os.pathsep)

        self.assertNotIn(str(provided.resolve()), entries)
        self.assertIn(str(retained.resolve()), entries)
        self.assertIn(str(classes_dir.resolve()), entries)
        self.assertIn(str(main_resources.resolve()), entries)
        self.assertIn(str(test_resources.resolve()), entries)

    def test_external_test_classpath_prefers_test_vfs_over_production_vfs(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "external"
            cache_dir = project_dir / "build" / "pyronaut"
            classes_dir = cache_dir / "classes"
            test_classes_dir = cache_dir / "test-classes"
            classes_dir.mkdir(parents=True)
            test_classes_dir.mkdir(parents=True)
            (project_dir / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
            (cache_dir / "project-layout.properties").write_text(
                f"testClasspath=/tmp/test.jar\n",
                encoding="utf-8",
            )

            entries = cli._build_native_application_classpath_entries("test", project_dir)

        self.assertLess(entries.index(str(test_classes_dir.resolve())), entries.index(str(classes_dir.resolve())))

    def test_external_process_classpath_does_not_require_processed_classes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = root / "external"
            cache_dir = project_dir / "target" / "pyronaut"
            build_jar = project_dir / "lib" / "compile.jar"
            build_jar.parent.mkdir(parents=True)
            build_jar.write_text("", encoding="utf-8")
            (project_dir / "pom.xml").write_text("<project/>", encoding="utf-8")
            (cache_dir).mkdir(parents=True)
            (cache_dir / "project-layout.properties").write_text(
                "kind=MAVEN\n"
                f"buildClasspath={build_jar}\n",
                encoding="utf-8",
            )

            entries = cli._build_native_application_classpath_entries("process", project_dir)

        self.assertEqual([str(build_jar.resolve())], entries)

    def test_native_process_test_classpath_excludes_generated_outputs(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            home = root / "home"
            compiler_jar = self._write_native_compile_descriptor(
                native_dev,
                home,
                [("example", "compiler", "1.0", "compiler-1.0.jar")],
            )[0]
            project_dir = root / "project"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            test_classes_dir = cache_dir / "test-classes"
            classes_dir.mkdir(parents=True)
            test_classes_dir.mkdir(parents=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\n"
                "type = \"native\"\n",
                encoding="utf-8",
            )
            dependencies = []
            for scope in ("build", "runtime", "test"):
                dependency = root / f"{scope}.jar"
                dependency.write_text(scope, encoding="utf-8")
                dependencies.append(str(dependency.resolve()))
                (cache_dir / f"resolved-{scope}-dependencies").write_text(
                    f"{dependency}\n",
                    encoding="utf-8",
                )

            entries = cli._build_native_application_classpath_entries("process-test", project_dir)
            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None,
            ), patch("pathlib.Path.home", return_value=home):
                command_line = cli._pyronaut_dev_native_command_line(
                    "process",
                    ["--project-dir", str(project_dir), "--pass", "test"],
                    self._resolver(),
                )

        self.assertEqual(set(dependencies), {str(Path(entry).resolve()) for entry in entries})
        self.assertNotIn(str(classes_dir.resolve()), entries)
        self.assertNotIn(str(test_classes_dir.resolve()), entries)
        self.assertIsNotNone(command_line)
        assert command_line is not None
        self.assertNotIn("-Dmicronaut.openapi.adoc.enabled=false", command_line)
        test_classpath = command_line[command_line.index("--test-classpath") + 1].split(os.pathsep)
        self.assertEqual(
            {*dependencies, str(compiler_jar.resolve())},
            {str(Path(entry).resolve()) for entry in test_classpath},
        )
        self.assertNotIn(str(classes_dir.resolve()), test_classpath)
        self.assertNotIn(str(test_classes_dir.resolve()), test_classpath)

    def test_run_native_application_classpath_still_filters_control_panel_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            (native_dev.parent / "native-provided-classpath.txt").write_text(
                "micronaut-context-python-5.1.0.jar\n",
                encoding="utf-8",
            )

            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text(
                "\n".join(
                    [
                        "/tmp/micronaut-control-panel-core-2.0.0.jar",
                        "/tmp/micronaut-control-panel-ui-2.0.0.jar",
                        "/tmp/micronaut-runtime-5.2.3.jar",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            classpath = cli._build_native_application_classpath("run", project_dir.resolve(), str(native_dev))  # noqa: SLF001

        entries = classpath.split(os.pathsep)
        self.assertNotIn("/tmp/micronaut-control-panel-core-2.0.0.jar", entries)
        self.assertNotIn("/tmp/micronaut-control-panel-ui-2.0.0.jar", entries)
        self.assertIn("/tmp/micronaut-runtime-5.2.3.jar", entries)

    def test_external_dev_auto_restart_uses_run_delegate_command(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/micronaut-runtime-5.2.3.jar\n", encoding="utf-8")
            (cache_dir / "resolved-development-runtime-dependencies").write_text("/tmp/micronaut-control-panel-core-2.0.0.jar\n", encoding="utf-8")

            with patch.object(cli, "_delegate_lib_entries", return_value=["/tmp/pyronaut-dev.jar"]):
                with patch.object(cli, "_resolve_pyronaut_dev_native_executable", return_value="/tmp/pyronaut-dev"):
                    executed = []

                    def runner(command_line, env=None):
                        executed.append((command_line, env))
                        return 0

                    exit_code = cli._delegate(  # noqa: SLF001 - exercising internal helper directly
                        "dev",
                        ["--project-dir", str(project_dir)],
                        runner,
                        lambda command_name: "/tmp/pyronaut-dev" if command_name == "pyronaut-dev" else None,
                        java_home_provider=lambda: "/tmp/java-home",
                    )

        self.assertEqual(0, exit_code)
        command_line, env = executed[0]
        self.assertIn("io.micronaut.pyronaut.run.PyronautRunMain", command_line)
        self.assertIn("-Dmicronaut.environments=dev", command_line)
        self.assertIsNone(env.get("PYRONAUT_TEST_RESOURCES_DISABLED") if env else None)

    def test_external_dev_uses_python_auto_restart(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "external"
            project_dir.mkdir()
            (project_dir / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
            delegated = []

            def delegate(command, *args, **kwargs):
                delegated.append(command)
                return 0

            with patch.object(cli, "_delegate", side_effect=delegate):
                with patch.object(cli, "_run_lifecycle_validation", return_value=0):
                    with patch.object(cli, "_run_with_auto_restart", return_value=0) as auto_restart:
                        exit_code = cli.run(
                            ["dev", "--project-dir", str(project_dir)],
                            runner_with_env=lambda command_line, env=None: 0,
                            process_runner=lambda command_line, env=None: None,
                        )

        self.assertEqual(0, exit_code)
        auto_restart.assert_called_once()
        self.assertEqual([], delegated)

    def test_native_compile_manifest_rejects_build_machine_paths(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "resources" / "pyronaut-dev"
            native_dev.mkdir(parents=True)
            (native_dev / "native-compile-classpath.txt").write_text(
                "/build-agent/gradle-cache/files-2.1/org.junit.jupiter/junit-jupiter-api/6.1.3/hash/junit-jupiter-api-6.1.3.jar\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "Invalid native compiler classpath descriptor"):
                cli._native_launcher_compile_classpath_entries(str(native_dev / "pyronaut-dev"))

    def test_native_direct_dev_keeps_openapi_adoc_conversion_enabled(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            jvm_args = cli._build_direct_source_native_jvm_args(  # noqa: SLF001 - command construction coverage
                str(native_dev),
                {"JAVA_HOME": "/tmp/java-home"},
                command="dev",
                args=["app.py"],
            )

        self.assertNotIn("-Dmicronaut.openapi.adoc.enabled=false", jvm_args)

    def test_direct_source_dev_preserves_user_environment_property(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")

            jvm_args = cli._build_direct_source_native_jvm_args(  # noqa: SLF001 - command construction coverage
                str(native_dev),
                {"JAVA_HOME": "/tmp/java-home"},
                command="dev",
                environment=cli._default_environment("dev", ["-Dmicronaut.environments=custom", str(source)]),  # noqa: SLF001
                args=["-Dmicronaut.environments=custom", str(source)],
            )

        self.assertNotIn("-Dmicronaut.environments=dev", jvm_args)
        self.assertIn("-Dpyronaut.dev.direct.command=dev", jvm_args)
        self.assertTrue(cli._has_micronaut_environments_property(["-Dmicronaut.environments=custom", str(source)]))  # noqa: SLF001

    def test_run_auto_restart_prefers_bundled_production_native_executable_with_application_classpath(self):
        executed = []
        started = []
        original_test_resources_disabled = os.environ.get("PYRONAUT_TEST_RESOURCES_DISABLED")
        os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = "true"
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                native_dev = Path(temp_dir) / "pyronaut-dev"
                native_dev.write_text("", encoding="utf-8")
                native_dev.chmod(0o755)
                native_run = Path(temp_dir) / "pyronaut-run"
                native_run.write_text("", encoding="utf-8")
                native_run.chmod(0o755)
                project_dir = Path(temp_dir) / "demo"
                (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
                self._write_manifests(project_dir, development_runtime=True)
                (project_dir / "pyproject.toml").write_text(
                    """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.toolchain]
type = "native"
""".strip()
                    + "\n",
                    encoding="utf-8",
                )

                class FakeProcess:
                    def poll(self):
                        return 0

                def runner(command_line, env=None):
                    executed.append(command_line)
                    return 0

                def process_runner(command_line, env):
                    started.append((command_line, env))
                    return FakeProcess()

                with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else native_run if command_name == "pyronaut-run" else None):
                    exit_code = cli.run(
                        ["run", "--project-dir", str(project_dir)],
                        runner=runner,
                        process_runner=process_runner,
                        resolver=self._resolver(),
                        platform_name="linux",
                    )
        finally:
            if original_test_resources_disabled is None:
                os.environ.pop("PYRONAUT_TEST_RESOURCES_DISABLED", None)
            else:
                os.environ["PYRONAUT_TEST_RESOURCES_DISABLED"] = original_test_resources_disabled

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "run"],
                [str(native_dev), "process", "--project-dir", str(project_dir), "--pass", "main"],
            ],
            executed[:2],
        )
        self.assertEqual([], started)
        run_command = executed[2]
        self.assertEqual(str(native_run), run_command[0])
        self.assertNotIn("-cp", run_command)
        properties = self._extract_native_system_properties(run_command, "--project-dir")
        classpath = properties["java.class.path"].split(os.pathsep)
        self.assertEqual("/tmp/runtime.jar", classpath[0])
        self.assertIn(str((project_dir / "__pyronaut__" / "classes").resolve()), classpath)
        self.assertEqual(["--project-dir", str(project_dir)], run_command[run_command.index("--project-dir") :])

    def test_test_prefers_bundled_pyronaut_dev_native_executable_with_test_classpath_and_resources_properties(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "app-config").mkdir(parents=True, exist_ok=True)
            (project_dir / "test-resources").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.toolchain]
type = "native"

[tool.pyronaut.sources]
resources = "app-config"
test-resources = "test-resources"
""".strip()
                + "\n",
                encoding="utf-8",
            )

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                command_line = cli._pyronaut_dev_native_command_line(  # noqa: SLF001 - exercising native CLI argument construction
                    "test",
                    ["--project-dir", str(project_dir)],
                    self._resolver(),
                    env_overrides={
                        "MICRONAUT_TEST_RESOURCES_SERVER_URI": "http://localhost:1234",
                        "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN": "token",
                    },
                )

            self.assertIsNotNone(command_line)
            assert command_line is not None
            self.assertEqual(str(native_dev), command_line[0])
            properties = self._extract_native_system_properties(command_line, "test")
            classpath = properties["java.class.path"].split(os.pathsep)
            self.assertIn("/tmp/test.jar", classpath)
            self.assertIn("/tmp/runtime.jar", classpath)
            self.assertIn("/tmp/build.jar", classpath)
            self.assertIn(str((project_dir / "__pyronaut__" / "test-classes").resolve()), classpath)
            self.assertIn(str((project_dir / "__pyronaut__" / "classes").resolve()), classpath)
            self.assertIn(str((project_dir / "app-config").resolve()), classpath)
            self.assertIn(str((project_dir / "test-resources").resolve()), classpath)
            self.assertEqual("http://localhost:1234", properties["micronaut.test.resources.server.uri"])
            self.assertEqual("token", properties["micronaut.test.resources.server.access.token"])
            self.assertEqual(["test", "--project-dir", str(project_dir)], command_line[command_line.index("test") :])

    def test_test_selection_stays_on_native_pyronaut_dev_executable(self):
        # A native-toolchain project must not silently switch to the JVM launcher
        # because tests were selected: the launcher understands --tests.
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.toolchain]
type = "native"
""".strip()
                + "\n",
                encoding="utf-8",
            )

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir), "--tests", "tests/test_math.py::test_add", "--tests", "*test_add*"],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        test_command = next(command for command in executed if command[0] == str(native_dev) and "test" in command)
        self.assertNotIn("/bin/java", test_command[0])
        self.assertEqual(
            ["test", "--project-dir", str(project_dir), "--tests", "tests/test_math.py::test_add", "--tests", "*test_add*"],
            test_command[test_command.index("test") :],
        )

    def test_test_resources_server_uses_dedicated_executable_when_pyronaut_dev_native_is_available(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            install_root = Path(temp_dir) / "pyronaut-dev-install"
            native_dev = install_root / "bin" / "pyronaut-dev"
            native_dev.parent.mkdir(parents=True, exist_ok=True)
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            native_test_resources_server = Path(temp_dir) / "pyronaut-test-resources-server"
            native_test_resources_server.write_text("", encoding="utf-8")
            native_test_resources_server.chmod(0o755)
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None), \
                    patch.object(
                        cli,
                        "_resolve_native_preferred_executable",
                        side_effect=lambda command_name, resolver: str(native_test_resources_server) if command_name == "pyronaut-test-resources-server" else resolver(command_name),
                    ):
                exit_code = cli.run(
                    ["test-resources-server", "start", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(str(native_dev), executed[0][0])
        self.assertIn("install", executed[0])
        self.assertEqual(str(native_test_resources_server), executed[1][0])
        self.assertFalse(any(arg.startswith("-Djava.class.path=") for arg in executed[1]))
        self.assertEqual(
            ["start", "--project-dir", str(project_dir)],
            executed[1][1:],
        )

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

    def test_process_uses_pyronaut_dev_when_toolchain_native(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-toolchain"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["process", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [[str(native_dev), "process", "--project-dir", str(project_dir)]],
            executed,
        )

    def test_process_explicit_jvm_mode_overrides_native_toolchain(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-toolchain-jvm-processor"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                """
[tool.pyronaut.toolchain]
type = "native"

[tool.pyronaut.processor]
mode = "jvm"
""".strip()
                + "\n",
                encoding="utf-8",
            )
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["process", "--project-dir", str(project_dir)],
                    runner=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([["/tmp/pyronaut-processor", "--project-dir", str(project_dir)]], executed)

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
        self.assertEqual([str(native_test), "--project-dir", str(project_dir)], executed[-1])

    def test_test_debug_vm_forces_jvm_mode_even_when_native_is_configured(self):
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
        self._assert_test_delegate(executed[-1], str(project_dir), ["--debug-vm"])

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

    def test_test_processes_all_passes_when_test_classes_missing(self):
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
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], str(project_dir))
        self._assert_test_resources_stop(executed[4], str(project_dir))

    def test_run_does_not_start_test_resources_when_enabled(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "runtime-only"
            session_dir = project_dir / "__pyronaut__"
            (session_dir / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            self._write_test_resources_enabled(project_dir, enabled=True)

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

    _TEST_RESOURCES_CLIENT_JARS = (
        "micronaut-test-resources-client-4.1.0.jar",
        "micronaut-test-resources-core-4.1.0.jar",
        "micronaut-test-resources-codec-4.1.0.jar",
    )

    def _write_disabled_test_resources_project(self, root: Path, *, toolchain: str) -> Path:
        project_dir = root / "disabled-test-resources"
        cache_dir = project_dir / "__pyronaut__"
        (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
        (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
        client_jars = [str(root / name) for name in self._TEST_RESOURCES_CLIENT_JARS]
        for scope in ("runtime", "development-runtime", "test", "build"):
            (cache_dir / f"resolved-{scope}-dependencies").write_text(
                "\n".join([f"/tmp/{scope}.jar", *client_jars]) + "\n",
                encoding="utf-8",
            )
        (project_dir / "pyproject.toml").write_text(
            "[tool.pyronaut.toolchain]\n"
            f"type = \"{toolchain}\"\n\n"
            "[tool.pyronaut.test-resources]\n"
            "enabled = false\n",
            encoding="utf-8",
        )
        return project_dir

    def _resolver_with_test_resources_client(self, root: Path):
        base = self._resolver()
        test_launcher = self._write_fake_install_dist(root, "pyronaut-test")
        for name in self._TEST_RESOURCES_CLIENT_JARS:
            (Path(test_launcher).parent.parent / "lib" / name).write_text("", encoding="utf-8")

        def resolve(command_name):
            if command_name == "pyronaut-test":
                return test_launcher
            return base(command_name)

        return resolve

    def _assert_test_resources_client_disabled(self, command_line) -> None:
        # A disabled client must not fall back to a stale
        # ~/.micronaut/test-resources/test-resources.properties.
        self.assertIn("-Dmicronaut.test.resources.enabled=false", command_line)
        self.assertIn("-Dpyronaut.dev.test.resources.bridge.enabled=false", command_line)
        self.assertFalse(
            any(value.startswith("-Dpyronaut.dev.test.resources.client.classpath=") for value in command_line),
            command_line,
        )
        self.assertFalse(any(value.startswith("-Dmicronaut.test.resources.server.uri=") for value in command_line))

    def test_jvm_test_disables_test_resources_client_when_test_resources_are_disabled(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = self._write_disabled_test_resources_project(root, toolchain="jvm")

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            exit_code = cli.run(
                ["test", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver_with_test_resources_client(root),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertFalse(any(cmd[0] == "/tmp/pyronaut-test-resources-server" for cmd, _ in executed))
        test_command = executed[-1][0]
        self.assertIn(self._TEST_MAIN, test_command)
        self._assert_test_resources_client_disabled(test_command)
        classpath = test_command[test_command.index("-cp") + 1].split(os.pathsep)
        # The pyronaut-test launcher's own copy of the client stays off the
        # classpath; the explicit property covers a declared dependency.
        self.assertFalse(
            any(
                Path(entry).name in self._TEST_RESOURCES_CLIENT_JARS
                and "pyronaut-test-install" in entry
                for entry in classpath
            ),
            classpath,
        )

    def test_jvm_dev_disables_test_resources_client_when_test_resources_are_disabled(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = self._write_disabled_test_resources_project(root, toolchain="jvm")
            command_line, _ = cli._build_dev_delegate_invocation(  # noqa: SLF001
                ["--project-dir", str(project_dir)],
                self._resolver(),
                debug_vm=False,
                env_overrides=None,
                java_home_provider=None,
            )

        self.assertIn(self._RUN_MAIN, command_line)
        self._assert_test_resources_client_disabled(command_line)
        self.assertEqual(1, command_line.count("-Dmicronaut.test.resources.enabled=false"))
        self.assertLess(
            command_line.index("-Dmicronaut.test.resources.enabled=false"),
            command_line.index("-cp"),
        )

    def test_native_dev_and_test_disable_test_resources_client_when_test_resources_are_disabled(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            project_dir = self._write_disabled_test_resources_project(root, toolchain="native")
            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None,
            ):
                dev_command, _ = cli._build_dev_delegate_invocation(  # noqa: SLF001
                    ["--project-dir", str(project_dir)],
                    self._resolver(),
                    debug_vm=False,
                    env_overrides=None,
                    java_home_provider=None,
                )
                test_command = cli._pyronaut_dev_native_command_line(  # noqa: SLF001
                    "test",
                    ["--project-dir", str(project_dir)],
                    self._resolver(),
                )

        assert test_command is not None
        for command_line in (dev_command, test_command):
            self.assertEqual(str(native_dev), command_line[0])
            self._assert_test_resources_client_disabled(command_line)

    def test_native_run_disables_test_resources_client_when_test_resources_are_disabled(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            native_dev = root / "pyronaut-dev"
            native_run = root / "pyronaut-run"
            for executable in (native_dev, native_run):
                executable.write_text("", encoding="utf-8")
                executable.chmod(0o755)
            project_dir = self._write_disabled_test_resources_project(root, toolchain="native")
            with patch.object(
                cli,
                "_bundled_native_executable",
                side_effect=lambda command_name: {"pyronaut-dev": native_dev, "pyronaut-run": native_run}.get(command_name),
            ):
                run_command = cli._pyronaut_run_native_command_line(  # noqa: SLF001
                    "run",
                    ["--project-dir", str(project_dir)],
                    self._resolver(),
                )

        assert run_command is not None
        self.assertEqual(str(native_run), run_command[0])
        self.assertIn("-Dmicronaut.test.resources.enabled=false", run_command)

    def test_test_resources_client_stays_enabled_for_owned_server_and_direct_sources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "enabled"
            project_dir.mkdir()
            self._write_test_resources_enabled(project_dir, enabled=True)
            self.assertEqual([], cli._test_resources_disabled_jvm_args(project_dir))  # noqa: SLF001
            # An owned server supplies the URI explicitly.
            disabled_project = self._write_disabled_test_resources_project(Path(temp_dir), toolchain="jvm")
            self.assertEqual(
                [],
                cli._test_resources_disabled_jvm_args(  # noqa: SLF001
                    disabled_project,
                    env_overrides={"MICRONAUT_TEST_RESOURCES_SERVER_URI": "http://localhost:1234"},
                ),
            )
            # Direct-source launches infer Test Resources in pyronaut-dev.
            direct_source = Path(temp_dir) / "direct"
            direct_source.mkdir()
            self.assertEqual([], cli._test_resources_disabled_jvm_args(direct_source))  # noqa: SLF001
            self.assertIn(
                "-Dmicronaut.test.resources.enabled=false",
                cli._test_resources_disabled_jvm_args(direct_source, ["--disable-test-resources"]),  # noqa: SLF001
            )

    def test_test_processes_all_passes_even_when_output_dirs_exist(self):
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
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], str(project_dir))
        self._assert_test_resources_stop(executed[4], str(project_dir))

    def test_run_forwards_no_cache_to_processor(self):
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
        self.assertEqual(3, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run", "--no-cache"], executed[0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "main", "--no-cache"], executed[1])
        self._assert_run_delegate(executed[2], "/tmp/demo")

    def test_test_forwards_no_cache_to_processor(self):
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
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test", "--no-cache"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "all", "--no-cache"], executed[2])
        self._assert_test_delegate(executed[3], "/tmp/demo")
        self._assert_test_resources_stop(executed[4], "/tmp/demo")

    def test_test_does_not_run_install_preflight_for_local_repository_env(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner(command_line):
            executed.append(command_line)
            return 0

        with patch.dict(os.environ, {cli.LOCAL_REPOSITORY_ENV: "/tmp/local-repository"}):
            exit_code = cli.run(
                ["test", "--project-dir", "/tmp/demo"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self._assert_test_resources_start(executed[0], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], "/tmp/demo")
        self._assert_test_resources_stop(executed[4], "/tmp/demo")

    def test_test_resources_start_forwards_local_repository_env_to_install(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with patch.dict(os.environ, {cli.LOCAL_REPOSITORY_ENV: "/tmp/local-repository"}):
            exit_code = cli.run(
                ["test-resources-server", "start", "--project-dir", "/tmp/demo"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--local-repository", "/tmp/local-repository"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-test-resources-server", "start", "--project-dir", "/tmp/demo"], executed[1])

    def test_test_forwards_local_repository_to_install_preflight_only(self):
        executed = []
        project_dir = Path("/tmp/demo-local-repo-test")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        def runner(command_line):
            executed.append(command_line)
            return 0

        exit_code = cli.run(
            ["test", "--project-dir", str(project_dir), "--local-repository", ".pyronaut-m2"],
            runner=runner,
            resolver=self._resolver(),
            platform_name="linux",
        )

        self.assertEqual(0, exit_code)
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], str(project_dir))
        self._assert_test_resources_stop(executed[4], str(project_dir))

    def test_test_continuous_space_reruns_full_pipeline_and_reuses_test_resources(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "continuous-space"
            resolved_project_dir = self._normalized_project_dir(str(project_dir.resolve()))
            (project_dir / "src").mkdir(parents=True, exist_ok=True)
            (project_dir / "src" / "main.py").write_text("print('v1')\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_file = settings_dir / "test-resources.properties"
            session_file = project_dir / "__pyronaut__" / "test-resources-session.json"

            def runner(command_line, env=None):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    owner_token = command_line[command_line.index("--owner-token") + 1]
                    settings_dir.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:61234\n"
                        "server.access.token=fresh-token\n",
                        encoding="utf-8",
                    )
                    session_file.write_text(
                        cli._json_dumps(  # noqa: SLF001 - test fixture mirrors runtime session file
                            {
                                "ownerCommand": cli.shlex.join(["pyronaut", "test", "--project-dir", str(project_dir), "--continuous"]),
                                "ownerPid": os.getpid(),
                                "ownerToken": owner_token,
                                "startedAt": time.time(),
                            }
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                return 0

            calls = {"count": 0}

            def input_reader(timeout):
                calls["count"] += 1
                return " " if calls["count"] == 1 else "q"

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir), "--continuous"],
                    runner_with_env=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    input_reader=input_reader,
                )

        self.assertEqual(0, exit_code)
        banner = "--------------------------------------\nContinuous Testing Active. Press SPACE to run again, \"w\" to watch for changes, or \"q\" to exit.\n"
        self.assertEqual(2, stdout.getvalue().count(banner))
        self.assertGreaterEqual(calls["count"], 2)
        self.assertEqual(8, len(executed))
        self._assert_test_resources_start(executed[0][0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "test"], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir, "--pass", "all"], executed[2][0])
        self._assert_test_delegate(executed[3][0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "test"], executed[4][0])
        self.assertEqual("http://localhost:61234", (executed[4][1] or {}).get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir, "--pass", "all"], executed[5][0])
        self._assert_test_delegate(executed[6][0], str(project_dir))
        self._assert_test_resources_stop(executed[7][0], str(project_dir))
        self.assertEqual(1, sum(1 for command_line, _ in executed if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]))
        self.assertEqual(1, sum(1 for command_line, _ in executed if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "stop"]))

    def test_test_continuous_watch_mode_reruns_on_pyproject_change(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "continuous-watch"
            resolved_project_dir = self._normalized_project_dir(str(project_dir.resolve()))
            (project_dir / "src").mkdir(parents=True, exist_ok=True)
            (project_dir / "src" / "main.py").write_text("print('v1')\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            pyproject = project_dir / "pyproject.toml"
            pyproject.write_text(
                """
[project]
name = "continuous-watch"

[tool.pyronaut.test-resources]
enabled = true
""".strip()
                + "\n",
                encoding="utf-8",
            )
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_file = settings_dir / "test-resources.properties"
            session_file = project_dir / "__pyronaut__" / "test-resources-session.json"
            now = {"value": 0.0}
            calls = {"count": 0}

            def runner(command_line, env=None):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    owner_token = command_line[command_line.index("--owner-token") + 1]
                    settings_dir.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:61235\n"
                        "server.access.token=fresh-token\n",
                        encoding="utf-8",
                    )
                    session_file.write_text(
                        cli._json_dumps(  # noqa: SLF001 - test fixture mirrors runtime session file
                            {
                                "ownerCommand": cli.shlex.join(["pyronaut", "test", "--project-dir", str(project_dir), "--continuous"]),
                                "ownerPid": os.getpid(),
                                "ownerToken": owner_token,
                                "startedAt": time.time(),
                            }
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                return 0

            def input_reader(timeout):
                calls["count"] += 1
                if timeout is not None:
                    now["value"] += timeout
                if calls["count"] == 1:
                    return "w"
                if calls["count"] == 2:
                    pyproject.write_text(
                        """
[project]
name = "continuous-watch"
version = "2.0.0"

[tool.pyronaut.test-resources]
enabled = true
""".strip()
                        + "\n",
                        encoding="utf-8",
                    )
                    return None
                if calls["count"] == 3:
                    return None
                return "q"

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir), "--continuous"],
                    runner_with_env=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    input_reader=input_reader,
                    watch_poll_interval=0.05,
                    watch_debounce_seconds=0.05,
                    monotonic=lambda: now["value"],
                )

        self.assertEqual(0, exit_code)
        banner = "--------------------------------------\nContinuous Testing Active. Press SPACE to run again, \"w\" to watch for changes, or \"q\" to exit.\n"
        self.assertEqual(2, stdout.getvalue().count(banner))
        self.assertGreaterEqual(calls["count"], 4)
        self.assertEqual(8, len(executed))
        self._assert_test_resources_start(executed[0][0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "test"], executed[1][0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir, "--pass", "all"], executed[2][0])
        self._assert_test_delegate(executed[3][0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "test"], executed[4][0])
        self.assertEqual("http://localhost:61235", (executed[4][1] or {}).get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", resolved_project_dir, "--pass", "all"], executed[5][0])
        self._assert_test_delegate(executed[6][0], str(project_dir))
        self._assert_test_resources_stop(executed[7][0], str(project_dir))

    def test_test_continuous_keyboard_interrupt_cleans_up_owned_test_resources(self):
        executed: list[tuple[list[str], dict[str, str] | None]] = []
        stdout = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "continuous-cancel"
            resolved_project_dir = self._normalized_project_dir(str(project_dir.resolve()))
            (project_dir / "src").mkdir(parents=True, exist_ok=True)
            (project_dir / "src" / "main.py").write_text("print('v1')\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_file = settings_dir / "test-resources.properties"
            session_file = project_dir / "__pyronaut__" / "test-resources-session.json"

            def runner(command_line, env=None):
                executed.append((command_line, env))
                if command_line[:2] == ["/tmp/pyronaut-test-resources-server", "start"]:
                    owner_token = command_line[command_line.index("--owner-token") + 1]
                    settings_dir.mkdir(parents=True, exist_ok=True)
                    settings_file.write_text(
                        "server.uri=http\\://localhost\\:61236\n"
                        "server.access.token=fresh-token\n",
                        encoding="utf-8",
                    )
                    session_file.write_text(
                        cli._json_dumps(  # noqa: SLF001 - test fixture mirrors runtime session file
                            {
                                "ownerCommand": cli.shlex.join(["pyronaut", "test", "--project-dir", str(project_dir), "--continuous"]),
                                "ownerPid": os.getpid(),
                                "ownerToken": owner_token,
                                "startedAt": time.time(),
                            }
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                return 0

            calls = {"count": 0}

            def input_reader(timeout):
                calls["count"] += 1
                raise KeyboardInterrupt

            with redirect_stdout(stdout):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir), "--continuous"],
                    runner_with_env=runner,
                    resolver=self._resolver(),
                    platform_name="linux",
                    input_reader=input_reader,
                )

        self.assertEqual(130, exit_code)
        banner = "--------------------------------------\nContinuous Testing Active. Press SPACE to run again, \"w\" to watch for changes, or \"q\" to exit.\n"
        self.assertEqual(1, stdout.getvalue().count(banner))
        self.assertEqual(1, calls["count"])
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0][0], str(project_dir))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", resolved_project_dir, "--scenario", "test"], executed[1][0])
        self._assert_test_delegate(executed[3][0], str(project_dir))
        self._assert_test_resources_stop(executed[4][0], str(project_dir))

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
            executed[1][0],
        )
        self.assertEqual(
            ["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "all"],
            executed[2][0],
        )
        self._assert_test_resources_start(executed[0][0], str(project_dir))
        validate_env = executed[1][1] or {}
        self.assertEqual("http://localhost:61234", validate_env.get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))
        self._assert_test_delegate(executed[3][0], str(project_dir))
        self._assert_test_resources_stop(executed[4][0], str(project_dir))

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
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))

    def test_shared_server_mode_honors_kebab_case_pyproject_key(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "shared-kebab"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "shared-kebab"

[tool.pyronaut.test-resources]
shared-server = true
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
            shared = cli._read_pyproject_test_resources_shared(project_dir)  # noqa: SLF001 - internal helper coverage

        self.assertEqual(0, exit_code)
        self.assertTrue(shared)
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
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
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "main"], executed[1])
        self._assert_run_delegate(executed[2], str(project_dir))

    def test_existing_test_resources_session_is_attached_and_not_restarted(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            settings_file.parent.mkdir(parents=True, exist_ok=True)
            session_file = project_dir / "__pyronaut__" / "test-resources-session.json"
            session_file.parent.mkdir(parents=True, exist_ok=True)
            settings_file.write_text(
                "server.uri=http\\://127.0.0.1\\:61234\n"
                "server.access.token=running-token\n",
                encoding="utf-8",
            )
            session_file.write_text("{\"ownerToken\":\"dev-token\",\"ownerPid\":123,\"ownerCommand\":\"pyronaut dev\"}\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            session = cli._OwnedTestResourcesSession(project_dir=project_dir, owner_command="pyronaut test")
            with patch.object(cli, "_test_resources_server_available", return_value=True):
                session.ensure_started(runner=runner, resolver=self._resolver())
                session.stop_if_owned(runner=runner, resolver=self._resolver())

        self.assertEqual([], executed)

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
        self.assertNotIn("stale external settings detected", stderr.getvalue())
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))

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

        self.assertEqual(0, exit_code)
        self.assertNotIn("Test resources server failed to start", stderr.getvalue())
        self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
        self.assertTrue(any(self._RUN_MAIN in cmd for cmd in executed))

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
        self.assertNotIn("MICRONAUT_TEST_RESOURCES_SERVER_URI", run_env)
        self.assertNotIn("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", run_env)
        system_properties = self._extract_system_properties(run_invocation[0])
        self.assertNotIn("micronaut.test.resources.server.uri", system_properties)
        self.assertNotIn("micronaut.test.resources.server.access.token", system_properties)

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

    def test_resolve_test_resources_logs_dir_honors_kebab_case_pyproject_key(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo-kebab"
            settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
            settings_file.parent.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo-kebab"

[tool.pyronaut.test-resources]
logs-dir = "var/custom-test-resources-logs"
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
            pyronaut_run_jar = lib_dir / "micronaut-pyronaut-run-fixture.jar"
            picocli_jar = lib_dir / "picocli-fixture.jar"
            slf4j_jar = lib_dir / "slf4j-api-fixture.jar"
            pyronaut_run_jar.write_text("", encoding="utf-8")
            picocli_jar.write_text("", encoding="utf-8")
            slf4j_jar.write_text("", encoding="utf-8")

            previous = os.environ.pop("PYRONAUT_RUN_JAR", None)
            try:
                classpath = cli._build_delegate_classpath(  # noqa: SLF001 - exercising internal helper directly
                    "run",
                    project_dir.resolve(),
                    lambda command_name: str(executable) if command_name in {"pyronaut-run", "pyronaut-run-python"} else f"/tmp/{command_name}",
                )
            finally:
                if previous is not None:
                    os.environ["PYRONAUT_RUN_JAR"] = previous

            entries = classpath.split(os.pathsep)
            self.assertEqual("/tmp/runtime.jar", entries[0])
            self.assertIn(str(pyronaut_run_jar.resolve()), entries)
            self.assertIn(str(picocli_jar.resolve()), entries)
            self.assertIn(str(slf4j_jar.resolve()), entries)

    def test_build_delegate_classpath_deduplicates_delegate_lib_jars_by_file_name(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            project_lib_dir = project_dir / "project-libs"
            project_lib_dir.mkdir(parents=True, exist_ok=True)
            project_runtime = project_lib_dir / "micronaut-context-python-5.1.0-SNAPSHOT.jar"
            project_runtime.write_text("", encoding="utf-8")
            (cache_dir / "resolved-runtime-dependencies").write_text(
                f"{project_runtime}\n/tmp/runtime.jar\n",
                encoding="utf-8",
            )
            self._write_manifests(project_dir, runtime=False)

            tools_dir = Path(temp_dir) / "tools" / "pyronaut-run"
            bin_dir = tools_dir / "bin"
            lib_dir = tools_dir / "lib"
            bin_dir.mkdir(parents=True, exist_ok=True)
            lib_dir.mkdir(parents=True, exist_ok=True)
            executable = bin_dir / "pyronaut-run"
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            delegate_context_python = lib_dir / "micronaut-context-python-5.1.0-SNAPSHOT.jar"
            delegate_run = lib_dir / "micronaut-pyronaut-run-fixture.jar"
            delegate_context_python.write_text("", encoding="utf-8")
            delegate_run.write_text("", encoding="utf-8")

            previous = os.environ.pop("PYRONAUT_RUN_JAR", None)
            try:
                classpath = cli._build_delegate_classpath(  # noqa: SLF001 - exercising internal helper directly
                    "run",
                    project_dir.resolve(),
                    lambda command_name: str(executable) if command_name in {"pyronaut-run", "pyronaut-run-python"} else f"/tmp/{command_name}",
                )
            finally:
                if previous is not None:
                    os.environ["PYRONAUT_RUN_JAR"] = previous

            entries = classpath.split(os.pathsep)
            artifact_names = [Path(entry).name for entry in entries]
            self.assertEqual(1, artifact_names.count("micronaut-context-python-5.1.0-SNAPSHOT.jar"))
            self.assertIn(str(project_runtime), entries)
            self.assertNotIn(str(delegate_context_python.resolve()), entries)
            self.assertIn(str(delegate_run.resolve()), entries)

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

    def test_test_delegate_classpath_includes_application_dependencies_but_leaves_resources_to_test_launcher(self):
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
            bundled_pytest_entry = "/tmp/micronaut-pyronaut-pytest-fixture.jar"
            bundled_logback_entry = "/tmp/micronaut-pyronaut-logback-fixture.jar"
            bundled_context_python_entry = "/tmp/micronaut-context-python-fixture.jar"
            (cache_dir / "resolved-test-dependencies").write_text(
                f"/tmp/test.jar\n{bundled_pytest_entry}\n{bundled_context_python_entry}\n",
                encoding="utf-8",
            )
            (cache_dir / "resolved-runtime-dependencies").write_text(
                f"/tmp/runtime.jar\n{bundled_logback_entry}\n",
                encoding="utf-8",
            )
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
            self.assertIn("/tmp/pyronaut-test.jar", entries)
            self.assertIn("/tmp/test.jar", entries)
            self.assertIn("/tmp/runtime.jar", entries)
            self.assertIn("/tmp/build.jar", entries)
            self.assertNotIn(bundled_pytest_entry, entries)
            self.assertIn(bundled_logback_entry, entries)
            self.assertNotIn(bundled_context_python_entry, entries)
            self._assert_no_classpath_artifact_prefix(entries, "micronaut-pyronaut-pytest-")
            self._assert_no_classpath_artifact_prefix(entries, "micronaut-context-python-")
            self.assertNotIn(str((project_dir / "app-config").resolve()), entries)
            self.assertNotIn(str((project_dir / "views").resolve()), entries)
            self.assertNotIn(str((project_dir / "assets").resolve()), entries)
            self.assertNotIn(str((project_dir / "test-resources").resolve()), entries)
            self.assertNotIn(str((project_dir / "test-fixtures").resolve()), entries)
            self.assertNotIn(str((project_dir / "config").resolve()), entries)

    def test_snapshot_watched_files_honors_dev_restart_excludes(self):
        """An excluded directory is still watched by the application, just not by the restarter."""
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "src").mkdir(parents=True, exist_ok=True)
            (project_dir / "views").mkdir(parents=True, exist_ok=True)
            (project_dir / "static" / "css").mkdir(parents=True, exist_ok=True)
            (project_dir / "src" / "main.py").write_text("print('ok')\n", encoding="utf-8")
            (project_dir / "views" / "ssr-components.mjs").write_text("export const App = 1;\n", encoding="utf-8")
            (project_dir / "static" / "client.js").write_text("console.log(1);\n", encoding="utf-8")
            (project_dir / "static" / "css" / "app.css").write_text("body{}\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.sources]
python = "src"
additional-resources = ["views", "static"]

[tool.pyronaut.dev]
restart-excludes = ["views", "static"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            snapshot = cli._snapshot_watched_files(project_dir.resolve())  # noqa: SLF001
            watched_files = {entry[0] for entry in snapshot}
            self.assertIn("src/main.py", watched_files)
            self.assertIn("pyproject.toml", watched_files)
            self.assertNotIn("views/ssr-components.mjs", watched_files)
            self.assertNotIn("static/client.js", watched_files)
            self.assertNotIn("static/css/app.css", watched_files)

    def test_dev_restart_excludes_default_to_nothing(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "src").mkdir(parents=True, exist_ok=True)
            (project_dir / "views").mkdir(parents=True, exist_ok=True)
            (project_dir / "src" / "main.py").write_text("print('ok')\n", encoding="utf-8")
            (project_dir / "views" / "ssr-components.mjs").write_text("export const App = 1;\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"
version = "1.0.0"

[tool.pyronaut.sources]
python = "src"
additional-resources = ["views"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            watched_files = {entry[0] for entry in cli._snapshot_watched_files(project_dir.resolve())}  # noqa: SLF001
            self.assertIn("views/ssr-components.mjs", watched_files)

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
            self.assertIn("pyproject.toml", watched_files)

    def test_run_delegate_classpath_uses_runtime_manifest_before_delegate_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir, development_runtime=True, runtime=True)
            tools_dir = Path(temp_dir) / "tools" / "pyronaut-run"
            bin_dir = tools_dir / "bin"
            lib_dir = tools_dir / "lib"
            bin_dir.mkdir(parents=True, exist_ok=True)
            lib_dir.mkdir(parents=True, exist_ok=True)
            executable = bin_dir / "pyronaut-run"
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            pyronaut_run_jar = lib_dir / "micronaut-pyronaut-run-fixture.jar"
            pyronaut_run_jar.write_text("", encoding="utf-8")

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
            self.assertNotIn("/tmp/runtime-dev.jar", entries)
            self.assertEqual(str(pyronaut_run_jar.resolve()), entries[1])

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
                    ["dev", "--project-dir", str(project_dir)],
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

    def test_run_and_test_delegation_activate_project_virtualenv(self):
        for command, main_class in (("run", self._RUN_MAIN), ("test", self._TEST_MAIN)):
            with self.subTest(command=command):
                executed = []

                with tempfile.TemporaryDirectory() as temp_dir:
                    project_dir = Path(temp_dir) / f"{command}-with-venv"
                    expected_project_dir = project_dir.resolve()
                    venv_bin = project_dir / ".venv" / "bin"
                    venv_bin.mkdir(parents=True, exist_ok=True)
                    venv_python = venv_bin / "python"
                    venv_python.write_text("", encoding="utf-8")
                    (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
                    if command == "test":
                        (project_dir / "__pyronaut__" / "test-classes").mkdir(parents=True, exist_ok=True)
                    self._write_manifests(project_dir)

                    def runner_with_env(command_line, env):
                        executed.append((command_line, env))
                        return 0

                    with patch.dict(os.environ, {"PATH": "/usr/bin", "PYTHONHOME": "/tmp/python-home"}, clear=False):
                        exit_code = cli.run(
                            [command, "--project-dir", str(project_dir)],
                            runner_with_env=runner_with_env,
                            resolver=self._resolver(),
                            platform_name="linux",
                            java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                        )

                self.assertEqual(0, exit_code)
                invocation = next((item for item in executed if main_class in item[0]), None)
                if invocation is None:
                    self.fail(f"Expected delegated pyronaut-{command} invocation")
                _, env = invocation
                self.assertIsInstance(env, dict)
                assert isinstance(env, dict)
                self.assertEqual(str(expected_project_dir / ".venv"), env.get("VIRTUAL_ENV"))
                self.assertEqual(
                    str(expected_project_dir / ".venv" / "bin" / "python"),
                    env.get("PYRONAUT_PYTHON_EXECUTABLE"),
                )
                self.assertNotIn("PYTHONHOME", env)
                self.assertTrue(
                    env.get("PATH", "").startswith(str(expected_project_dir / ".venv" / "bin") + os.pathsep)
                )

    def test_native_test_delegation_activates_project_virtualenv(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-test-with-venv"
            expected_project_dir = project_dir.resolve()
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            venv_bin = project_dir / ".venv" / "bin"
            venv_bin.mkdir(parents=True, exist_ok=True)
            venv_python = venv_bin / "python"
            venv_python.write_text("", encoding="utf-8")
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.test]\nmode = \"native\"\n",
                encoding="utf-8",
            )
            native_test = Path(temp_dir) / "pyronaut-test"
            native_test.write_text("", encoding="utf-8")
            native_test.chmod(0o755)

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_test if command_name == "pyronaut-test" else None), \
                    patch.dict(os.environ, {"PATH": "/usr/bin", "PYTHONHOME": "/tmp/python-home"}, clear=False):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(0, exit_code)
        invocation = next((item for item in executed if item[0][0] == str(native_test)), None)
        if invocation is None:
            self.fail("Expected native pyronaut-test invocation")
        _, env = invocation
        self.assertIsInstance(env, dict)
        assert isinstance(env, dict)
        self.assertEqual(str(expected_project_dir / ".venv"), env.get("VIRTUAL_ENV"))
        self.assertEqual(
            str(expected_project_dir / ".venv" / "bin" / "python"),
            env.get("PYRONAUT_PYTHON_EXECUTABLE"),
        )
        self.assertNotIn("PYTHONHOME", env)
        self.assertTrue(env.get("PATH", "").startswith(str(expected_project_dir / ".venv" / "bin") + os.pathsep))

    def test_pyronaut_dev_native_test_delegation_activates_project_virtualenv(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-dev-test-with-venv"
            expected_project_dir = project_dir.resolve()
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
            venv_bin = project_dir / ".venv" / "bin"
            venv_bin.mkdir(parents=True, exist_ok=True)
            venv_python = venv_bin / "python"
            venv_python.write_text("", encoding="utf-8")
            self._write_manifests(project_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\n"
                "type = \"native\"\n"
                "[tool.pyronaut.test-resources]\n"
                "enabled = false\n",
                encoding="utf-8",
            )
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None), \
                    patch.dict(os.environ, {"PATH": "/usr/bin", "PYTHONHOME": "/tmp/python-home"}, clear=False):
                exit_code = cli.run(
                    ["test", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(0, exit_code)
        invocation = next((item for item in executed if item[0][0] == str(native_dev) and "test" in item[0]), None)
        if invocation is None:
            self.fail("Expected native pyronaut-dev test invocation")
        _, env = invocation
        self.assertIsInstance(env, dict)
        assert isinstance(env, dict)
        self.assertEqual(str(expected_project_dir / ".venv"), env.get("VIRTUAL_ENV"))
        self.assertEqual(
            str(expected_project_dir / ".venv" / "bin" / "python"),
            env.get("PYRONAUT_PYTHON_EXECUTABLE"),
        )
        self.assertNotIn("PYTHONHOME", env)
        self.assertTrue(env.get("PATH", "").startswith(str(expected_project_dir / ".venv" / "bin") + os.pathsep))

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
                self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "start"] for cmd in executed))
                self.assertFalse(any(cmd[:2] == ["/tmp/pyronaut-test-resources-server", "stop"] for cmd in executed))
                self.assertFalse(session_file.exists())

    def test_owned_test_resources_session_hands_the_log_directory_to_the_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            (settings_dir / "test-resources.properties").write_text(
                "server.uri=http\\://localhost\\:18900\n"
                "server.access.token=token-abc\n",
                encoding="utf-8",
            )

            session = cli._OwnedTestResourcesSession(
                project_dir=project_dir.resolve(),
                owner_command="pyronaut dev",
            )
            session._client_env_overrides = cli._test_resources_client_env_from_settings(  # noqa: SLF001
                settings_dir / "test-resources.properties"
            )
            overrides = session.client_env_overrides()

            self.assertEqual(
                str((settings_dir / "logs").resolve()),
                overrides["PYRONAUT_TEST_RESOURCES_LOGS_DIR"],
            )

    def test_owned_test_resources_session_honours_a_configured_logs_dir(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            settings_dir = project_dir / ".micronaut" / "test-resources"
            settings_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.test-resources]\nlogs-dir = \"var/tr-logs\"\n",
                encoding="utf-8",
            )
            (settings_dir / "test-resources.properties").write_text(
                "server.uri=http\\://localhost\\:18900\n",
                encoding="utf-8",
            )

            session = cli._OwnedTestResourcesSession(
                project_dir=project_dir.resolve(),
                owner_command="pyronaut dev",
            )
            session._client_env_overrides = cli._test_resources_client_env_from_settings(  # noqa: SLF001
                settings_dir / "test-resources.properties"
            )
            overrides = session.client_env_overrides()

            self.assertEqual(
                str((project_dir / "var" / "tr-logs").resolve()),
                overrides["PYRONAUT_TEST_RESOURCES_LOGS_DIR"],
            )

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
            with redirect_stderr(stderr):
                session._report_started_server()  # noqa: SLF001 - internal helper coverage

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

    def test_owned_test_resources_delegate_receives_java_home_from_provider(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            session = cli._OwnedTestResourcesSession(project_dir=project_dir.resolve(), owner_command="pyronaut test")

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            with patch.dict(os.environ, {"PATH": "/usr/bin"}, clear=True):
                exit_code = session._delegate_test_resources_server(  # noqa: SLF001 - internal helper coverage
                    ["start", "--project-dir", str(project_dir)],
                    runner=runner_with_env,
                    resolver=self._resolver(),
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        command_line, env = executed[0]
        self.assertEqual(["/tmp/pyronaut-test-resources-server", "start", "--project-dir", str(project_dir)], command_line)
        self.assertIsInstance(env, dict)
        self.assertEqual("/tmp/graalvm-jdk-25", env["JAVA_HOME"])
        self.assertTrue(env["PATH"].startswith("/tmp/graalvm-jdk-25/bin" + os.pathsep))

    def test_owned_test_resources_delegate_fails_when_java_home_provider_cannot_provision(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            session = cli._OwnedTestResourcesSession(project_dir=project_dir.resolve(), owner_command="pyronaut test")

            def runner_with_env(command_line, env):
                executed.append((command_line, env))
                return 0

            with self.assertRaises(RuntimeError) as raised:
                session._delegate_test_resources_server(  # noqa: SLF001 - internal helper coverage
                    ["start", "--project-dir", str(project_dir)],
                    runner=runner_with_env,
                    resolver=self._resolver(),
                    java_home_provider=lambda: None,
                )

        self.assertIn("compatible GraalVM JDK", str(raised.exception))
        self.assertEqual([], executed)

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
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], "/tmp/demo")
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test"], executed[1])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], "/tmp/demo", ["--tests", "tests/test_math.py::test_add", "--tests", "*test_add*"])
        self._assert_test_resources_stop(executed[4], "/tmp/demo")

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
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "main"], executed[1][0])
        self.assertIsInstance(executed[1][1], dict)
        self._assert_run_delegate(executed[2][0], "/tmp/demo", ["--debug-vm"])
        self.assertEqual(self._JDWP_FLAG, executed[2][0][3])

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
        self._assert_test_delegate(executed[3][0], "/tmp/demo", ["--debug-vm"])
        jvm_args = executed[3][0][: executed[3][0].index("-cp")]
        self.assertIn(self._JDWP_FLAG, jvm_args)
        for flag in cli._SHORT_LIVED_JVM_FLAGS:  # noqa: SLF001
            self.assertIn(flag, jvm_args)

    def test_run_jvm_delegate_keeps_jit_enabled(self):
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)

        command_line, _ = cli._build_java_delegate_invocation(  # noqa: SLF001
            "run",
            ["--project-dir", "/tmp/demo"],
            self._resolver(),
        )

        for flag in cli._SHORT_LIVED_JVM_FLAGS:  # noqa: SLF001
            self.assertNotIn(flag, command_line)

    def test_jvm_dev_delegate_uses_short_lived_jvm_flags(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = self._write_disabled_test_resources_project(Path(temp_dir), toolchain="jvm")
            command_line, _ = cli._build_dev_delegate_invocation(  # noqa: SLF001
                ["--project-dir", str(project_dir)],
                self._resolver(),
                debug_vm=False,
                env_overrides=None,
                java_home_provider=None,
            )

        jvm_args = command_line[: command_line.index("-cp")]
        for flag in cli._SHORT_LIVED_JVM_FLAGS:  # noqa: SLF001
            self.assertIn(flag, jvm_args)

    def test_aot_cache_is_opt_in_through_the_toolchain_table(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir).resolve()
            pyproject = project_dir / "pyproject.toml"
            args = ["--project-dir", str(project_dir)]
            self.assertIsNone(cli._aot_cache_project(args))  # noqa: SLF001
            pyproject.write_text("[tool.pyronaut.toolchain]\ntype = 'jvm'\n", encoding="utf-8")
            self.assertIsNone(cli._aot_cache_project(args))  # noqa: SLF001
            pyproject.write_text("[tool.pyronaut.toolchain]\naot-cache = true\n", encoding="utf-8")
            self.assertEqual(project_dir, cli._aot_cache_project(args))  # noqa: SLF001
            pyproject.write_text("[tool.pyronaut.toolchain]\naotCache = true\n", encoding="utf-8")
            self.assertEqual(project_dir, cli._aot_cache_project(args))  # noqa: SLF001
            pyproject.write_text("[tool.pyronaut.toolchain]\naot-cache = false\n", encoding="utf-8")
            self.assertIsNone(cli._aot_cache_project(args))  # noqa: SLF001

    def test_aot_launches_are_unchanged_unless_enabled(self):
        previous = cli._aot_cache_project_dir  # noqa: SLF001
        cli._aot_cache_project_dir = None  # noqa: SLF001
        try:
            with patch.object(cli._aot_cache, "prepare") as prepare:  # noqa: SLF001
                launch = cli._prepare_aot_launch(["java", "-cp", "a.jar", "Main"], {"A": "1"})  # noqa: SLF001
        finally:
            cli._aot_cache_project_dir = previous  # noqa: SLF001

        prepare.assert_not_called()
        self.assertEqual(["java", "-cp", "a.jar", "Main"], launch.command_line)
        self.assertEqual({"A": "1"}, launch.env)

    def test_training_processes_get_longer_to_stop(self):
        process = MagicMock()
        launch = cli._aot_cache.Launch(["java"], None)  # noqa: SLF001
        managed = cli._aot_cache.ManagedProcess(process, launch)  # noqa: SLF001

        self.assertTrue(cli._stop_managed_process(managed))  # noqa: SLF001
        process.wait.assert_called_once_with(cli._aot_cache.TRAINING_STOP_TIMEOUT_SECONDS)  # noqa: SLF001

        plain = MagicMock()
        self.assertTrue(cli._stop_managed_process(plain))  # noqa: SLF001
        plain.wait.assert_called_once_with(timeout=3)

    def test_interrupted_training_process_is_allowed_to_exit_on_its_own(self):
        process = MagicMock()
        managed = cli._aot_cache.ManagedProcess(process, cli._aot_cache.Launch(["java"], None))  # noqa: SLF001

        self.assertTrue(cli._stop_managed_process(managed, interrupted=True))  # noqa: SLF001

        process.wait.assert_called_once_with(cli._aot_cache.TRAINING_STOP_TIMEOUT_SECONDS)  # noqa: SLF001
        process.terminate.assert_not_called()

    def test_launcher_jvm_options_use_the_launcher_opts_variable_and_keep_user_options_last(self):
        env = cli._with_launcher_jvm_options(  # noqa: SLF001
            {"PYRONAUT_DEV_OPTS": "-XX:+UseSerialGC"},
            "/opt/pyronaut/tools/pyronaut-dev/bin/pyronaut-dev",
            ["-XX:+UseParallelGC", "-Dexample=true"],
        )

        self.assertEqual("-XX:+UseParallelGC -Dexample=true -XX:+UseSerialGC", env["PYRONAUT_DEV_OPTS"])
        self.assertEqual(
            "-Dexample=true",
            cli._with_launcher_jvm_options({}, "/opt/pyronaut/tools/pyronaut-test/bin/pyronaut-test.bat", ["-Dexample=true"])[  # noqa: SLF001
                "PYRONAUT_TEST_OPTS"
            ],
        )

    def test_direct_source_jvm_test_passes_short_lived_flags_to_launcher(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "test_controller.py"
            source.write_text("def test_ok():\n    pass\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append((command_line, env))
                return 0

            exit_code = cli.run(
                ["test", "--jvm", str(source)],
                runner_with_env=runner,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/java-home",
            )

        self.assertEqual(0, exit_code)
        command_line, env = executed[-1]
        self.assertEqual(str(self._fake_dev_delegate_root / "bin" / "pyronaut-dev"), command_line[0])
        self.assertEqual(list(cli._SHORT_LIVED_JVM_FLAGS), env["PYRONAUT_DEV_OPTS"].split())  # noqa: SLF001

    def test_direct_source_run_keeps_jit_enabled(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "controller.py"
            source.write_text("print('ok')\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append((command_line, env))
                return 0

            exit_code = cli.run(
                ["run", "--jvm", str(source)],
                runner_with_env=runner,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/java-home",
            )

        self.assertEqual(0, exit_code)
        self.assertNotIn("PYRONAUT_DEV_OPTS", executed[-1][1])

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
            self.assertEqual(3, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["--scenario", "run"], executed[0][3:])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual(["--pass", "main"], executed[1][3:])
            self.assertEqual(0, len(started))

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
                if "pyronaut-processor" in command_line[0] and len(executed) > 2:
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
                    ["dev", "--project-dir", str(project_dir)],
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
                ["dev", "--project-dir", str(project_dir)],
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
                    ["dev", "--project-dir", str(project_dir)],
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
                    ["dev", "--project-dir", str(project_dir)],
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
            self.assertEqual(0, len(started))
            self.assertEqual(3, len(executed))
            self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir"], executed[0][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[0][2]))
            self.assertEqual(["--scenario", "run"], executed[0][3:])
            self.assertEqual(["/tmp/pyronaut-processor", "--project-dir"], executed[1][:2])
            self.assertEqual(self._normalized_project_dir(resolved_project_dir), self._normalized_project_dir(executed[1][2]))
            self.assertEqual(["--pass", "main"], executed[1][3:])

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
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "main"], executed[1][0])
        self.assertIsInstance(executed[1][1], dict)
        self._assert_run_delegate(executed[2][0], "/tmp/demo")
        self.assertEqual("/tmp/graalvm-jdk-25", executed[2][1]["JAVA_HOME"])
        self.assertTrue(executed[2][1]["PATH"].startswith("/tmp/graalvm-jdk-25/bin"))

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
        self.assertEqual(0, len(executed))

    def test_launch_labels_describe_delegated_tools(self):
        self.assertEqual("Starting dependency installer", cli._launch_label(["/tmp/pyronaut-install", "--project-dir", "."]))  # noqa: SLF001
        self.assertEqual("Starting processor", cli._launch_label(["/opt/tools/pyronaut-processor.bat", "--project-dir", "."]))  # noqa: SLF001
        self.assertEqual("Starting test runner", cli._launch_label(["/tmp/pyronaut-dev", "-Djava.home=/jdk", "test", "--project-dir", "."]))  # noqa: SLF001
        self.assertEqual("Starting test runner", cli._launch_label(["/tmp/pyronaut-dev", "-Dpyronaut.dev.project.dir=/p", "test", "main.py"]))  # noqa: SLF001
        self.assertEqual("Starting application launcher", cli._launch_label(["/tmp/pyronaut-dev", "-Dx=y", "main.py"]))  # noqa: SLF001
        self.assertEqual("Starting configuration validator", cli._launch_label(["/tmp/pyronaut-dev", "validate-config", "--project-dir", "."]))  # noqa: SLF001
        self.assertEqual(
            "Starting test runner",
            cli._launch_label(["/jdk/bin/java", "-Xmx1g", "-cp", "a.jar", "io.micronaut.pyronaut.test.PyronautTestMain", "--project-dir", "."]),  # noqa: SLF001
        )
        self.assertIsNone(cli._launch_label(["/usr/bin/docker", "build", "."]))  # noqa: SLF001
        self.assertIsNone(cli._launch_label(["python3", "-m", "pip", "wheel"]))  # noqa: SLF001
        self.assertIsNone(cli._launch_label(["/jdk/bin/java", "-cp", "a.jar", "com.example.Other"]))  # noqa: SLF001
        self.assertIsNone(cli._launch_label([]))  # noqa: SLF001

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
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "build-demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            with redirect_stdout(stdout), redirect_stderr(stderr):
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
        self.assertIn("Wheel build complete", stderr.getvalue())
        self.assertIn("Install with:", stderr.getvalue())

    def test_build_jar_delegates_with_ordered_classpath_and_normalized_output(self):
        executed = []
        captured_classpath = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-jar-build":
                classpath_file = Path(command_line[command_line.index("--classpath-file") + 1])
                captured_classpath.extend(classpath_file.read_text(encoding="utf-8").splitlines())
                output = Path(command_line[command_line.index("--output") + 1])
                output.write_bytes(b"jar")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ, {"PYRONAUT_RUN_JAR": "/tmp/pyronaut-run.jar"}
        ):
            project_dir = Path(temp_dir) / "jar-demo"
            classes = project_dir / "__pyronaut__" / "classes"
            resources = project_dir / "config"
            classes.mkdir(parents=True)
            resources.mkdir()
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                "/tmp/application-runtime.jar\n", encoding="utf-8"
            )
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo app\"\nversion = \"1.2+build\"\n\n"
                "[tool.pyronaut.packaging]\nformat = \"fat-jar\"\n",
                encoding="utf-8",
            )
            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

            jar_command, jar_env = executed[3]
            self.assertEqual(0, exit_code)
            self.assertEqual("/tmp/pyronaut-jar-build", jar_command[0])
            self.assertEqual(str(classes.resolve()), jar_command[jar_command.index("--classes-dir") + 1])
            self.assertEqual(str(resources.resolve()), jar_command[jar_command.index("--resource-dir") + 1])
            self.assertEqual(
                str(project_dir.resolve() / "dist" / "demo-app-1.2-build.jar"),
                jar_command[jar_command.index("--output") + 1],
            )
            self.assertEqual(["/tmp/application-runtime.jar", "/tmp/pyronaut-run.jar"], captured_classpath)
            self.assertEqual("/tmp/graalvm-jdk-25", jar_env["JAVA_HOME"])

    def test_fat_jar_uses_external_build_classes_resources_and_runtime(self):
        captured = {}

        def runner_with_env(command_line, env):
            captured["command"] = command_line
            captured["classpath"] = Path(
                command_line[command_line.index("--classpath-file") + 1]
            ).read_text(encoding="utf-8").splitlines()
            Path(command_line[command_line.index("--output") + 1]).write_bytes(b"jar")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ, {"PYRONAUT_RUN_JAR": "/tmp/pyronaut-run.jar"}
        ):
            project_dir = Path(temp_dir).resolve()
            classes = project_dir / "target" / "pyronaut" / "classes"
            resources = project_dir / "src" / "main" / "resources"
            classes.mkdir(parents=True)
            resources.mkdir(parents=True)
            (project_dir / "pom.xml").write_text("<project/>", encoding="utf-8")
            (project_dir / "target" / "pyronaut" / "project-layout.properties").write_text(
                f"runtimeClasspath=/tmp/external-runtime.jar\nmainResources={resources}\n",
                encoding="utf-8",
            )
            exit_code = cli._run_fat_jar_build(  # noqa: SLF001 - external packaging integration
                project_dir=project_dir,
                project_name="external",
                project_version="1.0",
                runner=runner_with_env,
                resolver=self._resolver(),
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        command = captured["command"]
        self.assertEqual(0, exit_code)
        self.assertEqual(str(classes), command[command.index("--classes-dir") + 1])
        self.assertEqual(str(resources), command[command.index("--resource-dir") + 1])
        self.assertEqual([str(Path("/tmp/external-runtime.jar").resolve()), "/tmp/pyronaut-run.jar"], captured["classpath"])

    def test_jar_flag_rejects_packaging_shaping_options(self):
        for conflicting in ("--jvm", "--native", "--docker", "--native-base", "--native-base=default", "--main-class=example.Main"):
            with self.subTest(conflicting=conflicting), tempfile.TemporaryDirectory() as temp_dir:
                stderr = io.StringIO()
                with redirect_stderr(stderr):
                    exit_code = cli.run(
                        ["build", "--jar", conflicting, "--project-dir", temp_dir],
                        runner_with_env=lambda *_args: 0,
                        resolver=self._resolver(),
                        platform_name="linux",
                    )
                self.assertEqual(cli.USAGE_ERROR, exit_code)
                self.assertIn("--jar cannot be combined", stderr.getvalue())

    def test_removed_base_image_options_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            for option in ("--base-image", "--base-image=default", "--base-image-output=runtime/base"):
                with self.subTest(option=option), self.assertRaisesRegex(ValueError, "Unsupported base-image option"):
                    cli._resolve_packaging_format(Path(temp_dir), [option])  # noqa: SLF001
            for option in ("--native-base-output", "--native-base-output=runtime/base"):
                with self.subTest(option=option), self.assertRaisesRegex(ValueError, "Unsupported option --native-base-output"):
                    cli._resolve_packaging_format(Path(temp_dir), ["--native-base", option])  # noqa: SLF001

    def test_bare_native_base_selects_default_crema_packaging(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            self.assertEqual("default", cli._extract_build_native_base(["--native-base"]))  # noqa: SLF001
            self.assertEqual("default", cli._extract_build_native_base(["--native-base", "--native-base=default"]))  # noqa: SLF001
            self.assertEqual("wheel-crema", cli._resolve_packaging_format(project_dir, ["--native-base"]))  # noqa: SLF001
            self.assertEqual(
                "docker-crema", cli._resolve_packaging_format(project_dir, ["--docker", "--native-base"])  # noqa: SLF001
            )
            with self.assertRaisesRegex(ValueError, "Conflicting --native-base values"):
                cli._resolve_packaging_format(project_dir, ["--native-base", "--native-base=runtime/base"])  # noqa: SLF001
            with self.assertRaisesRegex(ValueError, "only supported for native builds"):
                cli._resolve_packaging_format(project_dir, ["--jvm", "--native-base"])  # noqa: SLF001

    def test_resolves_all_configured_packaging_formats_and_flag_precedence(self):
        expected = {
            "fat-jar": "fat-jar",
            "wheel-jvm": "wheel-jvm",
            "wheel-native": "wheel-native",
            "wheel-crema": "wheel-crema",
            "docker-jvm": "docker-jvm",
            "docker-native": "docker-native",
            "docker-crema": "docker-crema",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            for configured, resolved in expected.items():
                (project_dir / "pyproject.toml").write_text(
                    f"[tool.pyronaut.packaging]\nformat = {configured!r}\n", encoding="utf-8"
                )
                self.assertEqual(resolved, cli._resolve_packaging_format(project_dir, []))  # noqa: SLF001
                self.assertEqual("wheel-jvm", cli._resolve_packaging_format(project_dir, ["--jvm"]))  # noqa: SLF001
                self.assertEqual("docker-native", cli._resolve_packaging_format(project_dir, ["--native", "--docker"]))  # noqa: SLF001

            self.assertEqual(
                "wheel-crema",
                cli._resolve_packaging_format(project_dir, ["--native-base=runtime/base"]),  # noqa: SLF001
            )
            self.assertEqual(
                "docker-crema",
                cli._resolve_packaging_format(project_dir, ["--docker", "--native-base=https://example.test/base"]),  # noqa: SLF001
            )

            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.packaging]\nformat = \"jar\"\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(ValueError, "Invalid tool.pyronaut.packaging.format"):
                cli._resolve_packaging_format(project_dir, [])  # noqa: SLF001

            for configured in ("FAT-JAR", ""):
                (project_dir / "pyproject.toml").write_text(
                    f"[tool.pyronaut.packaging]\nformat = {configured!r}\n", encoding="utf-8"
                )
                with self.assertRaisesRegex(ValueError, "Invalid tool.pyronaut.packaging.format"):
                    cli._resolve_packaging_format(project_dir, ["--jvm"])  # noqa: SLF001

            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.build]\nmode = \"native\"\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(ValueError, "Unsupported configuration 'tool.pyronaut.build.mode'"):
                cli._resolve_packaging_format(project_dir, ["--jar"])  # noqa: SLF001

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
        usage = stdout.getvalue()
        self.assertIn("Usage: pyronaut build", usage)
        self.assertIn("--native-base[=<default|path|image|url>]", usage)
        self.assertIn("--include-native-binary <name|path>]...", usage)
        self.assertNotIn("--native-base-output", usage)
        self.assertNotIn("--base-image", usage)

    def test_direct_build_native_base_paths_are_resolved_from_source_project(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            staging = root / "staging"
            local_base = root / "runtime" / "pyronaut-run-python"
            args = [
                "App.py",
                "--native-base=runtime/pyronaut-run-python",
                "--include-native-binary",
                "bin/helper",
            ]

            rewritten = cli._direct_build_arguments(args, staging, root)  # noqa: SLF001
            docker_rewritten = cli._direct_build_arguments(  # noqa: SLF001
                ["App.py", "--docker", "--native-base=acme/runtime:1"], staging, root
            )

        self.assertIn(f"--native-base={local_base}", rewritten)
        include_index = rewritten.index("--include-native-binary")
        self.assertEqual(str(root / "bin" / "helper"), rewritten[include_index + 1])
        self.assertEqual(["--project-dir", str(staging)], rewritten[-2:])
        # With --docker a custom native base is an image name, not a path.
        self.assertIn("--native-base=acme/runtime:1", docker_rewritten)

    def test_direct_build_preserves_default_and_http_native_bases(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            staging = root / "staging"
            for configured in ("default", "https://example.test/pyronaut-run-python"):
                with self.subTest(configured=configured):
                    rewritten = cli._direct_build_arguments(  # noqa: SLF001
                        ["App.py", f"--native-base={configured}"],
                        staging,
                        root,
                    )
                    self.assertIn(f"--native-base={configured}", rewritten)
            bare = cli._direct_build_arguments(["App.py", "--native-base"], staging, root)  # noqa: SLF001
            self.assertIn("--native-base", bare)
            self.assertNotIn("--native-base-output", bare)

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
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            root_dir = Path(temp_dir)
            project_dir = root_dir / "docker-demo"
            runtime_jar = root_dir / ".m2" / "repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes" / "example").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "classes" / "example" / "Demo.class").write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes" / "application.toml").write_text("greeting = 'embedded'\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes" / "application.toml").write_text("greeting = 'hello'\n", encoding="utf-8")
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

            with redirect_stdout(stdout), redirect_stderr(stderr):
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
        self.assertIn("PYRONAUT_JVM_BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:25i4", docker_command)
        self.assertIn("-t", docker_command)
        self.assertIn("demo-app:1.2.3", docker_command)
        self.assertIn('ENTRYPOINT ["/app/__pyronaut__/tools/pyronaut-run/bin/pyronaut-run", "--project-dir", "/app"]', captured["dockerfile"])
        self.assertIn("WORKDIR /app\nEXPOSE 8080\n", captured["dockerfile"])
        self.assertIn("COPY app/__pyronaut__/tools/shared /app/__pyronaut__/tools/shared", captured["dockerfile"])
        self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", captured["manifest"])
        self.assertIn("app/__pyronaut__/m2-repository/example/runtime.jar", captured["context_files"])
        self.assertIn("app/__pyronaut__/tools/pyronaut-run/bin/pyronaut-run", captured["context_files"])
        self.assertIn("app/config/application.toml", captured["context_files"])
        self.assertIn("app/pyproject.toml", captured["context_files"])
        self.assertIn("Docker image build complete: demo-app:1.2.3", stderr.getvalue())

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
        stderr = io.StringIO()
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
            (project_dir / "config").mkdir()
            (project_dir / "config" / "application.toml").write_text("greeting = 'hello'\n", encoding="utf-8")
            metadata = project_dir / "config" / "META-INF" / "native-image" / "demo"
            metadata.mkdir(parents=True)
            (metadata / "resource-config.json").write_text("{}\n", encoding="utf-8")
            migration = project_dir / "config" / "db" / "migration"
            migration.mkdir(parents=True)
            (migration / "V1__schema.sql").write_text("CREATE TABLE demo (id INT);\n", encoding="utf-8")
            native_executable = self._write_fake_install_dist(root_dir, "pyronaut-native-build")

            def resolver(command_name):
                if command_name == "pyronaut-native-build":
                    return native_executable
                return f"/tmp/{command_name}"

            with redirect_stdout(stdout), redirect_stderr(stderr), patch.dict(os.environ, {"HTTP_PROXY": "http://proxy.example"}, clear=False):
                exit_code = cli.run(
                    [
                        "build",
                        "--native",
                        "--docker",
                        "--static",
                        "--project-dir",
                        str(project_dir),
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
        self.assertIn("COPY app/__pyronaut__/tools/shared /workspace/app/__pyronaut__/tools/shared", captured["dockerfile"])
        self.assertIn("HTTP_PROXY=http://proxy.example", docker_command)
        self.assertIn("PYRONAUT_NATIVE_BUILDER_IMAGE=example/static-builder:1", docker_command)
        self.assertIn("PYRONAUT_NATIVE_BASE_IMAGE=example/static-base:1", docker_command)
        self.assertIn("example/demo:1.2.3-native", docker_command)
        self.assertIn("FROM example/static-builder:1 AS builder", captured["dockerfile"])
        self.assertIn("FROM example/static-base:1", captured["dockerfile"])
        self.assertIn("--static --libc=musl", captured["dockerfile"])
        self.assertIn("--initialize-at-run-time=example.Foo", captured["dockerfile"])
        self.assertIn("COPY app/native-build-config/ /workspace/app/config/", captured["dockerfile"])
        self.assertIn("COPY app/config/ /app/config/", captured["dockerfile"])
        self.assertNotIn("app/__pyronaut__/classes/application.toml", captured["context_files"])
        self.assertIn("app/native-build-config/META-INF/native-image/demo/resource-config.json", captured["context_files"])
        self.assertIn("app/native-build-config/db/migration/V1__schema.sql", captured["context_files"])
        self.assertNotIn("app/native-build-config/application.toml", captured["context_files"])
        self.assertIn("COPY app/__pyronaut__/m2-repository/ /workspace/app/__pyronaut__/m2-repository/", captured["dockerfile"])
        self.assertNotIn("COPY app/__pyronaut__/m2-repository/example/runtime.jar", captured["dockerfile"])
        self.assertIn("app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build", captured["context_files"])
        self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", captured["manifest"])
        self.assertIn("Docker image build complete: example/demo:1.2.3-native", stderr.getvalue())

    def test_build_docker_custom_native_base_creates_named_base_then_application_layer(self):
        executed = []
        dockerfiles: list[str] = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if len(command_line) >= 2 and command_line[1] == "build":
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                dockerfiles.append(dockerfile.read_text(encoding="utf-8"))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            root_dir = Path(temp_dir)
            project_dir = root_dir / "crema-docker-demo"
            runtime_jar = project_dir / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n", encoding="utf-8"
            )
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo-app\"\nversion = \"1.2.3\"\n",
                encoding="utf-8",
            )
            native_executable = self._write_fake_install_dist(root_dir, "pyronaut-native-build")

            def resolver(command_name):
                return native_executable if command_name == "pyronaut-native-build" else f"/tmp/{command_name}"

            exit_code = cli.run(
                ["build", "--docker", "--native-base=acme/runtime:2", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=resolver,
                platform_name="linux",
            )
            pyproject = (project_dir / "pyproject.toml").read_text(encoding="utf-8")

        self.assertEqual(0, exit_code)
        docker_commands = [command for command, _ in executed if len(command) >= 2 and command[1] == "build"]
        self.assertEqual(2, len(docker_commands))
        self.assertIn("--target", docker_commands[0])
        self.assertIn("pyronaut-base", docker_commands[0])
        self.assertEqual("acme/runtime:2", docker_commands[0][docker_commands[0].index("-t") + 1])
        self.assertTrue(pyproject.startswith("[project]\nname = \"demo-app\"\nversion = \"1.2.3\"\n"))
        self.assertIn('[tool.pyronaut.packaging]\nformat = "docker-crema"\n', pyproject)
        self.assertIn('[tool.pyronaut.build.docker]\nbase-image = "acme/runtime:2"\n', pyproject)
        self.assertIn("demo-app:1.2.3-native", docker_commands[1])
        self.assertIn("--native-base", dockerfiles[0])
        self.assertIn("FROM pyronaut-base", dockerfiles[0])
        self.assertIn("COPY --from=builder /workspace/base/ /opt/pyronaut/bin/", dockerfiles[0])
        self.assertIn("COPY app/__pyronaut__/classes /app/__pyronaut__/classes", dockerfiles[0])

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
            self.assertEqual("FROM ${PYRONAUT_JVM_BASE_IMAGE}\nARG PYRONAUT_PROJECT_NAME\n", captured["dockerfile"])
            self.assertIn("PYRONAUT_JVM_BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:25i4", captured["docker_command"])

            captured.clear()
            executed.clear()
            exit_code_native = cli.run(
                ["build", "--native", "--docker", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=resolver,
                platform_name="linux",
            )

        self.assertEqual(0, exit_code_native)
        self.assertEqual("Dockerfile.native", captured["dockerfile_name"])
        self.assertEqual("FROM ${PYRONAUT_NATIVE_BUILDER_IMAGE} AS builder\nARG PYRONAUT_NATIVE_STATIC\n", captured["dockerfile"])
        self.assertIn("PYRONAUT_NATIVE_BUILDER_IMAGE=container-registry.oracle.com/graalvm/native-image:25i4", captured["docker_command"])

    def test_build_docker_reuses_configured_base_with_custom_runtime_dockerfile(self):
        captured: dict[str, object] = {}

        def runner_with_env(command_line, env):
            if len(command_line) >= 2 and command_line[1] == "build":
                context_dir = Path(command_line[-1])
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["docker_command"] = command_line
                captured["dockerfile_name"] = dockerfile.name
                captured["dockerfile"] = dockerfile.read_text(encoding="utf-8")
                captured["context_files"] = sorted(str(path.relative_to(context_dir)) for path in context_dir.rglob("*"))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(cli.shutil, "which", return_value="/usr/bin/docker"):
            project_dir = Path(temp_dir) / "custom-crema-docker-demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "docker").mkdir(parents=True, exist_ok=True)
            (project_dir / "docker" / "DockerfileNative").write_text(
                "ARG PYRONAUT_BASE_IMAGE\nFROM ${PYRONAUT_BASE_IMAGE}\nCOPY app/__pyronaut__/classes /app/__pyronaut__/classes\n",
                encoding="utf-8",
            )
            (project_dir / "pyproject.toml").write_text(
                "\n".join(
                    [
                        "[project]",
                        "name = \"demo-app\"",
                        "version = \"1.2.3\"",
                        "",
                        "[tool.pyronaut.packaging]",
                        "format = \"docker-crema\"",
                        "",
                        "[tool.pyronaut.build]",
                        "native-base = \"runtime/ignored-by-docker-override\"",
                        "",
                        "[tool.pyronaut.build.docker]",
                        "base-image = \"registry.example.com/acme/runtime:1\"",
                        "dockerfile-native = \"docker/DockerfileNative\"",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("DockerfileNative", captured["dockerfile_name"])
        self.assertIn("PYRONAUT_BASE_IMAGE=registry.example.com/acme/runtime:1", captured["docker_command"])
        self.assertEqual("ARG PYRONAUT_BASE_IMAGE\nFROM ${PYRONAUT_BASE_IMAGE}\nCOPY app/__pyronaut__/classes /app/__pyronaut__/classes\n", captured["dockerfile"])
        self.assertIn("app/__pyronaut__/classes", captured["context_files"])
        self.assertNotIn("app/__pyronaut__/tools/pyronaut-native-build", captured["context_files"])

    def test_cli_default_native_base_overrides_configured_docker_base_image(self):
        captured: dict[str, str] = {}

        def runner_with_env(command_line, env):
            if len(command_line) >= 2 and command_line[1] == "build":
                context_dir = Path(command_line[-1])
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["dockerfile"] = dockerfile.name
                captured["contents"] = dockerfile.read_text(encoding="utf-8")
                captured["command"] = " ".join(command_line)
                captured["context_files"] = "\n".join(
                    sorted(str(path.relative_to(context_dir)) for path in context_dir.rglob("*") if path.is_file())
                )
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            cli.shutil, "which", return_value="/usr/bin/docker"
        ):
            project_dir = Path(temp_dir) / "docker-default-precedence"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n\n"
                "[tool.pyronaut.packaging]\nformat = \"docker-crema\"\n\n"
                "[tool.pyronaut.build.docker]\nbase-image = \"registry.example.com/runtime:1\"\n",
                encoding="utf-8",
            )
            bundle = project_dir / "bundle" / "pyronaut-run"
            bundle.parent.mkdir(parents=True)
            bundle.write_bytes(b"native-runner")
            dev_bundle = bundle.parent / "pyronaut-dev"
            dev_bundle.write_bytes(b"unused-dev")
            (bundle.parent / "pyronaut-run-python").write_bytes(b"unused-python")
            (bundle.parent / "resources").mkdir()
            (bundle.parent / "resources" / "runtime.config").write_text("configured", encoding="utf-8")
            (bundle.parent / "resources" / "dev.config").write_text("dev", encoding="utf-8")
            extra_binary = project_dir / "extra-launcher"
            extra_binary.write_bytes(b"extra")
            (bundle.parent / "libsupport.so").write_bytes(b"library")
            (bundle.parent / "pyronaut-run.json").write_text(
                '{"bundle-files": ["libsupport.so", "pyronaut-run", "resources/runtime.config"]}\n',
                encoding="utf-8",
            )
            (bundle.parent / "pyronaut-dev.json").write_text(
                '{"bundle-files": ["pyronaut-dev", "resources/dev.config"]}\n',
                encoding="utf-8",
            )

            pyproject_before = (project_dir / "pyproject.toml").read_text(encoding="utf-8")

            for native_base_flag in ("--native-base=default", "--native-base"):
                captured.clear()
                with (
                    self.subTest(flag=native_base_flag),
                    patch.object(cli, "_bundled_default_native_base", return_value=bundle) as default_native_base,
                    patch.object(cli, "_ensure_native_image", return_value=dev_bundle) as ensure_native_image,
                ):
                    exit_code = cli.run(
                        [
                            "build", "--docker", native_base_flag,
                            "--include-native-binary=pyronaut-dev",
                            "--include-native-binary", "extra-launcher",
                            "--project-dir", str(project_dir),
                        ],
                        runner_with_env=runner_with_env,
                        resolver=self._resolver(),
                        platform_name="linux",
                    )

                    self.assertEqual(0, exit_code)
                    default_native_base.assert_called_once_with(project_dir.resolve(), platform_name="linux")
                    ensure_native_image.assert_called_once_with("pyronaut-dev", platform_name="linux")
                    self.assertEqual("DockerfileNativeDefault", captured["dockerfile"])
                    self.assertIn("COPY bundled-base/ /opt/pyronaut/bin/", captured["contents"])
                    self.assertNotIn("PYRONAUT_BASE_IMAGE=registry.example.com/runtime:1", captured["command"])
                    self.assertEqual(pyproject_before, (project_dir / "pyproject.toml").read_text(encoding="utf-8"))
        self.assertIn("bundled-base/resources/runtime.config", captured["context_files"])
        self.assertIn("bundled-base/libsupport.so", captured["context_files"])
        self.assertIn("bundled-base/pyronaut-dev", captured["context_files"])
        self.assertIn("bundled-base/resources/dev.config", captured["context_files"])
        self.assertIn("bundled-base/extra-launcher", captured["context_files"])
        self.assertNotIn("bundled-base/pyronaut-run-python", captured["context_files"])

    def test_closed_world_native_format_rejects_configured_reusable_base(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.packaging]\nformat = \"wheel-native\"\n\n"
                "[tool.pyronaut.build]\nnative-base = \"runtime/base\"\n",
                encoding="utf-8",
            )
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["build", "--project-dir", str(project_dir)],
                    runner_with_env=lambda *_args: 0,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("cannot use a configured native base", stderr.getvalue())

    def test_docker_crema_stages_configured_native_base(self):
        captured: dict[str, object] = {}

        def runner_with_env(command_line, env):
            if len(command_line) >= 2 and command_line[1] == "build":
                context_dir = Path(command_line[-1])
                dockerfile = Path(command_line[command_line.index("-f") + 1])
                captured["dockerfile"] = dockerfile.name
                captured["contents"] = dockerfile.read_text(encoding="utf-8")
                captured["launcher"] = (context_dir / "bundled-base" / "pyronaut-run").read_bytes()
                captured["resource"] = (context_dir / "bundled-base" / "resources" / "python-home.txt").read_text(encoding="utf-8")
                captured["library"] = (context_dir / "bundled-base" / "python.dll").read_bytes()
            return 0

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            cli.shutil, "which", return_value="/usr/bin/docker"
        ):
            project_dir = Path(temp_dir) / "docker-crema-default"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n\n"
                "[tool.pyronaut.packaging]\nformat = \"docker-crema\"\n\n"
                "[tool.pyronaut.build]\nnative-base = \"runtime/local-wheel-base\"\n",
                encoding="utf-8",
            )
            configured = project_dir / "runtime" / "local-wheel-base"
            configured.parent.mkdir(parents=True)
            configured.write_bytes(b"native-runner")
            (configured.parent / "resources").mkdir()
            (configured.parent / "resources" / "python-home.txt").write_text("python-home", encoding="utf-8")
            (configured.parent / "python.dll").write_bytes(b"library")

            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual("DockerfileNativeBase", captured["dockerfile"])
        self.assertIn("COPY bundled-base/ /opt/pyronaut/bin/", captured["contents"])
        self.assertEqual(b"native-runner", captured["launcher"])
        self.assertEqual("python-home", captured["resource"])
        self.assertEqual(b"library", captured["library"])

    def test_prepare_jvm_build_wheel_staging_rewrites_manifest_and_generates_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "stage-demo"
            runtime_jar = Path(temp_dir) / ".m2" / "repository" / "example" / "runtime.jar"
            control_panel_jar = Path(temp_dir) / ".m2" / "repository" / "io" / "micronaut" / "controlpanel" / "micronaut-control-panel-ui.jar"
            runtime_jar.parent.mkdir(parents=True, exist_ok=True)
            control_panel_jar.parent.mkdir(parents=True, exist_ok=True)
            runtime_jar.write_text("", encoding="utf-8")
            control_panel_jar.write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            (project_dir / "config").mkdir(parents=True, exist_ok=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(str(runtime_jar.resolve()) + "\n", encoding="utf-8")
            (project_dir / "__pyronaut__" / "resolved-development-runtime-dependencies").write_text(
                str(runtime_jar.resolve()) + "\n" + str(control_panel_jar.resolve()) + "\n",
                encoding="utf-8",
            )
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
            self.assertTrue((launcher_pkg / "app" / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar").exists())
            manifest = (launcher_pkg / "app" / "__pyronaut__" / "resolved-runtime-dependencies").read_text(encoding="utf-8")
            self.assertEqual("__pyronaut__/m2-repository/example/runtime.jar\n", manifest)
            self.assertNotIn("micronaut-control-panel-ui", manifest)
            self.assertFalse(
                (launcher_pkg / "app" / "__pyronaut__" / "m2-repository" / "io" / "micronaut" / "controlpanel" / "micronaut-control-panel-ui.jar").exists()
            )
            launcher_code = (launcher_pkg / "launcher.py").read_text(encoding="utf-8")
            self.assertIn("resolved-runtime-dependencies", launcher_code)
            self.assertIn("io.micronaut.pyronaut.run.PyronautRunMain", launcher_code)

    def test_prepare_default_native_wheel_stages_bundle_support_beside_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = root / "native-wheel"
            staging_dir = root / "stage"
            bundle_dir = root / "bundle"
            repository = root / ".m2" / "repository" / "example"
            provided_jar = repository / "provided" / "1.0" / "provided-1.0.jar"
            application_jar = repository / "application" / "1.0" / "application-1.0.jar"
            provided_jar.parent.mkdir(parents=True)
            application_jar.parent.mkdir(parents=True)
            provided_jar.write_bytes(b"provided")
            application_jar.write_bytes(b"application")
            (project_dir / "__pyronaut__" / "native").mkdir(parents=True)
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True)
            (project_dir / "__pyronaut__" / "native" / "demo").write_bytes(b"launcher")
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                f"{provided_jar}\n{application_jar}\n", encoding="utf-8"
            )
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n", encoding="utf-8"
            )
            (bundle_dir / "resources").mkdir(parents=True)
            (bundle_dir / "resources" / "runtime.config").write_text("configured", encoding="utf-8")
            (bundle_dir / "resources" / "demo").mkdir()
            (bundle_dir / "resources" / "demo" / "native-provided-classpath.txt").write_text(
                "example:provided\n", encoding="utf-8"
            )
            (bundle_dir / "libpython.so").write_bytes(b"library")

            cli._prepare_build_wheel_staging(  # noqa: SLF001
                project_dir=project_dir,
                staging_dir=staging_dir,
                project_name="demo",
                project_version="1.0",
                mode="native",
                main_class="",
                native_bundle_dir=bundle_dir,
                native_launcher_executable=str(bundle_dir / "demo"),
            )

            native_dir = staging_dir / "demo_launcher" / "app" / "__pyronaut__" / "native"
            self.assertEqual(b"launcher", (native_dir / "demo").read_bytes())
            self.assertEqual("configured", (native_dir / "resources" / "runtime.config").read_text(encoding="utf-8"))
            self.assertEqual(b"library", (native_dir / "libpython.so").read_bytes())
            self.assertFalse((staging_dir / "demo_launcher" / "app" / "resources").exists())
            manifest = (
                staging_dir / "demo_launcher" / "app" / "__pyronaut__" / "resolved-runtime-dependencies"
            ).read_text(encoding="utf-8")
            self.assertNotIn("provided-1.0.jar", manifest)
            self.assertIn("application-1.0.jar", manifest)

    def test_prepare_closed_world_native_wheel_stages_generated_bundle_support(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = root / "native-wheel"
            native_dir = project_dir / "__pyronaut__" / "native"
            native_dir.mkdir(parents=True)
            (native_dir / "demo").write_bytes(b"launcher")
            (native_dir / "resources" / "python").mkdir(parents=True)
            (native_dir / "resources" / "python" / "stdlib.txt").write_text("stdlib", encoding="utf-8")
            (native_dir / "libpython.dylib").write_bytes(b"library")
            (project_dir / "__pyronaut__" / "classes").mkdir()
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n", encoding="utf-8"
            )
            staging_dir = root / "stage"

            cli._prepare_build_wheel_staging(  # noqa: SLF001
                project_dir=project_dir,
                staging_dir=staging_dir,
                project_name="demo",
                project_version="1.0",
                mode="native",
                main_class="",
            )

            staged_native = staging_dir / "demo_launcher" / "app" / "__pyronaut__" / "native"
            self.assertEqual("stdlib", (staged_native / "resources" / "python" / "stdlib.txt").read_text(encoding="utf-8"))
            self.assertEqual(b"library", (staged_native / "libpython.dylib").read_bytes())

    def test_prepare_native_docker_context_stages_distribution_and_rewrites_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root_dir = Path(temp_dir)
            project_dir = root_dir / "native-context-demo"
            context_dir = root_dir / "context"
            runtime_jar = root_dir / ".m2" / "repository" / "example" / "runtime.jar"
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
            self.assertTrue((context_dir / "app" / "__pyronaut__" / "m2-repository" / "example" / "runtime.jar").exists())
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
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-demo"
            cache_dir = project_dir / "__pyronaut__"
            classes_dir = cache_dir / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            cache_dir.mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache_dir / "native" / "native-demo").parent.mkdir(parents=True, exist_ok=True)

            with redirect_stdout(stdout), redirect_stderr(stderr):
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
        self.assertNotIn("--main-class", executed[3][0])
        self.assertIn("--output", executed[3][0])
        self.assertIn(str(project_dir.resolve() / "__pyronaut__" / "native" / "native-demo"), executed[3][0])
        self.assertEqual("/tmp/graalvm-jdk-25", executed[3][1]["JAVA_HOME"])
        self.assertEqual("-m", executed[4][0][1])
        self.assertEqual("pip", executed[4][0][2])
        self.assertEqual("wheel", executed[4][0][3])
        self.assertIn("Native wheel build complete", stderr.getvalue())

    def test_build_native_rejects_main_class_override(self):
        stderr = io.StringIO()

        with tempfile.TemporaryDirectory() as temp_dir, redirect_stderr(stderr):
            exit_code = cli.run(
                ["build", "--native", "--project-dir", temp_dir, "--main-class", "example.Main"],
                runner_with_env=lambda _command, _env: 0,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("PyronautRunMain is always used", stderr.getvalue())

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

    def test_packaging_format_from_pyproject_defaults_to_native_wheel(self):
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
                "[tool.pyronaut.packaging]\nformat = \"wheel-native\"\n",
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

    def test_build_output_default_is_independent_from_toolchain_type(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-toolchain-default"
            project_dir.mkdir(parents=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )

            self.assertEqual("jvm", cli._resolve_build_mode(project_dir, []))  # noqa: SLF001 - precedence coverage
            self.assertEqual("jvm", cli._resolve_build_mode(project_dir, ["--jvm"]))  # noqa: SLF001 - precedence coverage

    def test_custom_native_base_builds_base_records_pyproject_and_packages_wheel(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("base", encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "python-base"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text(
                "/tmp/micronaut-context-python.jar\n", encoding="utf-8"
            )
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"python-base\"\nversion = \"1.0\"\n\n"
                "[tool.pyronaut.build]\n# shared runtime\nverbose = false\n\n"
                "[tool.pyronaut.build.docker]\nimage-name = \"acme/demo\"\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["build", "--native-base=runtime/python-base", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )
            pyproject = (project_dir / "pyproject.toml").read_text(encoding="utf-8")

        self.assertEqual(0, exit_code)
        native_commands = [command for command, _ in executed if command and command[0] == "/tmp/pyronaut-native-build"]
        self.assertEqual(1, len(native_commands))
        native_command = native_commands[0]
        self.assertIn("--native-base", native_command)
        self.assertIn("--include-python", native_command)
        self.assertEqual(
            str((project_dir / "runtime" / "python-base").resolve()),
            native_command[native_command.index("--output") + 1],
        )
        self.assertTrue(any("wheel" in command for command, _ in executed if command[1:3] == ["-m", "pip"]))
        self.assertEqual(
            "[project]\nname = \"python-base\"\nversion = \"1.0\"\n\n"
            "[tool.pyronaut.build]\n# shared runtime\nverbose = false\nnative-base = \"runtime/python-base\"\n\n"
            "[tool.pyronaut.build.docker]\nimage-name = \"acme/demo\"\n\n"
            "[tool.pyronaut.packaging]\nformat = \"wheel-crema\"\n",
            pyproject,
        )

    def test_java_source_tree_wins_over_transitive_python_runtime(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "java-project"
            (project_dir / "src-java").mkdir(parents=True)
            (project_dir / "src-java" / "App.java").write_text("class App {}", encoding="utf-8")
            (project_dir / "__pyronaut__").mkdir()
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                "micronaut-context-python-5.2.3.jar\n", encoding="utf-8"
            )
            (project_dir / "__pyronaut__" / "classes").mkdir()
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"java-project\"\n", encoding="utf-8"
            )

            self.assertFalse(cli._is_python_runtime_project(project_dir))  # noqa: SLF001
            self.assertNotIn(
                "micronaut-context-python-5.2.3.jar",
                cli._build_native_application_classpath_entries("run", project_dir),  # noqa: SLF001
            )

    def test_native_wheel_reuses_configured_local_native_base(self):
        executed = []
        captured: dict[str, bytes] = {}

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("base", encoding="utf-8")
                (output.parent / "resources" / "python").mkdir(parents=True)
                (output.parent / "resources" / "python" / "stdlib.txt").write_text("stdlib", encoding="utf-8")
                (output.parent / "libpython.so").write_bytes(b"library")
            if len(command_line) >= 3 and command_line[1:3] == ["-m", "pip"] and "wheel" in command_line:
                staging_dir = Path(command_line[-1])
                staged_native = staging_dir / "demo_launcher" / "app" / "__pyronaut__" / "native"
                captured["resource"] = (staged_native / "resources" / "python" / "stdlib.txt").read_bytes()
                captured["library"] = (staged_native / "libpython.so").read_bytes()
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "base-reuse"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n",
                encoding="utf-8",
            )

            base_exit = cli.run(
                ["build", "--native-base=runtime/base", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )
            wheel_exit = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, base_exit)
        self.assertEqual(0, wheel_exit)
        native_commands = [command for command, _ in executed if command and command[0] == "/tmp/pyronaut-native-build"]
        self.assertEqual(1, len(native_commands))
        self.assertEqual(b"stdlib", captured["resource"])
        self.assertEqual(b"library", captured["library"])

    def test_cli_native_base_overrides_configured_base_for_wheel(self):
        captured: dict[str, bytes] = {}

        def runner_with_env(command_line, env):
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                Path(command_line[command_line.index("--output") + 1]).write_bytes(b"cli")
            if len(command_line) >= 3 and command_line[1:3] == ["-m", "pip"] and "wheel" in command_line:
                staging_dir = Path(command_line[-1])
                captured["launcher"] = (
                    staging_dir / "demo_launcher" / "app" / "__pyronaut__" / "native" / "demo"
                ).read_bytes()
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "wheel-native-base-precedence"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("\n", encoding="utf-8")
            (project_dir / "runtime").mkdir()
            (project_dir / "runtime" / "configured-base").write_bytes(b"configured")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n\n"
                "[tool.pyronaut.packaging]\nformat = \"wheel-crema\"\n\n"
                "[tool.pyronaut.build]\nnative-base = \"runtime/configured-base\"\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["build", "--native-base=runtime/cli-base", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )
            pyproject = (project_dir / "pyproject.toml").read_text(encoding="utf-8")

        self.assertEqual(0, exit_code)
        self.assertEqual(b"cli", captured["launcher"])
        self.assertIn('[tool.pyronaut.build]\nnative-base = "runtime/cli-base"\n', pyproject)
        self.assertNotIn("configured-base", pyproject)
        self.assertEqual(1, pyproject.count("[tool.pyronaut.packaging]"))

    def test_native_build_uses_bundled_default_base_and_passes_user_packages(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line and command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("binary", encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "default-base"
            classes = project_dir / "__pyronaut__" / "classes" / "example" / "app"
            classes.mkdir(parents=True, exist_ok=True)
            (classes / "Application.class").write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"1.0\"\n\n"
                "[tool.pyronaut.packaging]\nformat = \"wheel-crema\"\n",
                encoding="utf-8",
            )
            base = project_dir / "bundled-pyronaut-run"
            base.write_text("binary", encoding="utf-8")
            for native_base_flag in ("--native-base=default", "--native-base"):
                executed.clear()
                with (
                    self.subTest(flag=native_base_flag),
                    patch.object(cli, "_bundled_default_native_base", return_value=base) as default_native_base,
                ):
                    exit_code = cli.run(
                        ["build", native_base_flag, "--project-dir", str(project_dir)],
                        runner_with_env=runner_with_env,
                        resolver=self._resolver(),
                        platform_name="darwin",
                        java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                    )

                    self.assertEqual(0, exit_code)
                    default_native_base.assert_called_once_with(project_dir.resolve(), platform_name="darwin")
                    native_command = next(command for command, _ in executed if command[0] == "/tmp/pyronaut-native-build")
                    self.assertIn("--default-native-base", native_command)
                    self.assertIn("--default-native-base-path", native_command)
                    self.assertEqual(str(base), native_command[native_command.index("--default-native-base-path") + 1])
                    self.assertNotIn("native-base", (project_dir / "pyproject.toml").read_text(encoding="utf-8"))

    def test_set_toml_string_updates_existing_tables_and_appends_missing_ones(self):
        set_toml = cli._set_toml_string  # noqa: SLF001
        self.assertEqual('[a.b]\nkey = "v"\n', set_toml("", "a.b", "key", "v"))
        self.assertEqual('x = 1\n\n[a.b]\nkey = "v"\n', set_toml("x = 1", "a.b", "key", "v"))
        self.assertEqual(
            '[a.b]\nkey = "new"\nother = 1\n',
            set_toml('[a.b]\n  "key" = "old" # comment\nother = 1\n', "a.b", "key", "new"),
        )
        self.assertEqual(
            '[a.b]\nother = 1\nkey = "v"\n\n[a.b.c]\nkey = "keep"\n',
            set_toml('[a.b]\nother = 1\n\n[a.b.c]\nkey = "keep"\n', "a.b", "key", "v"),
        )

    def test_record_native_base_configuration_leaves_unsupported_layouts_untouched(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            # Dotted keys define tool.pyronaut.packaging implicitly; appending
            # a [tool.pyronaut.packaging] table would be invalid TOML.
            original = '[tool.pyronaut]\npackaging.format = "wheel-jvm"\n'
            (project_dir / "pyproject.toml").write_text(original, encoding="utf-8")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                cli._record_native_base_configuration(  # noqa: SLF001
                    project_dir, "wheel-crema", ("build", "native-base"), "runtime/base"
                )
            self.assertEqual(original, (project_dir / "pyproject.toml").read_text(encoding="utf-8"))

    def test_bundled_default_dockerfile_includes_pyproject(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            dockerfile = Path(temp_dir) / "DockerfileNativeDefault"
            cli._write_bundled_application_dockerfile(  # noqa: SLF001
                target=dockerfile,
                runtime_image="example/runtime:1",
                runner_name="pyronaut-run-python",
                runtime_copies=[
                    "COPY app/__pyronaut__/resolved-runtime-dependencies /app/__pyronaut__/resolved-runtime-dependencies",
                    "COPY app/__pyronaut__/m2-repository/ /app/__pyronaut__/m2-repository/",
                ],
                resource_copies=["COPY app/views/ /app/views/"],
            )

            content = dockerfile.read_text(encoding="utf-8")
            self.assertIn("COPY bundled-base/ /opt/pyronaut/bin/", content)
            self.assertIn("WORKDIR /app\nEXPOSE 8080\n", content)
            self.assertIn("COPY app/pyproject.toml /app/pyproject.toml", content)
            self.assertIn("COPY app/__pyronaut__/m2-repository/ /app/__pyronaut__/m2-repository/", content)
            self.assertIn("COPY app/views/ /app/views/", content)

    def test_crema_application_dockerfile_includes_additional_resources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            dockerfile = Path(temp_dir) / "DockerfileNativeCrema"
            cli._write_crema_application_dockerfile(  # noqa: SLF001
                target=dockerfile,
                base_image="example/base:1",
                runner_name="pyronaut-run-python",
                resource_copies=["COPY app/views/ /app/views/"],
            )

            content = dockerfile.read_text(encoding="utf-8")
            self.assertIn("COPY app/views/ /app/views/", content)
            self.assertIn("WORKDIR /app\nEXPOSE 8080\n", content)

    def test_crema_base_dockerfile_includes_additional_resources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            dockerfile = Path(temp_dir) / "DockerfileNativeBase"
            cli._write_crema_base_dockerfile(  # noqa: SLF001
                target=dockerfile,
                builder_image="example/builder:1",
                runtime_image="example/runtime:1",
                runner_name="pyronaut-run-python",
                include_python=True,
                verbose=False,
                static_native=False,
                passthrough_args=[],
                resource_copies=["COPY app/views/ /app/views/"],
            )

            content = dockerfile.read_text(encoding="utf-8")
            self.assertIn("COPY app/views/ /app/views/", content)
            self.assertIn("WORKDIR /app\nEXPOSE 8080\n", content)

    def test_native_dockerfile_layers_language_resources_separately(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            dockerfile = Path(temp_dir) / "DockerfileNative"
            cli._write_native_dockerfile(  # noqa: SLF001
                target=dockerfile,
                builder_image="example/builder:1",
                runtime_image="example/runtime:1",
                project_name="demo",
                main_class="example.Main",
                include_python=True,
                verbose=False,
                static_native=False,
                passthrough_args=[],
                runtime_copies=[],
                resource_copies=[],
            )

            content = dockerfile.read_text(encoding="utf-8")
            self.assertIn("mv /workspace/app/__pyronaut__/native/resources /workspace/native-language-resources", content)
            self.assertIn("COPY --from=builder /workspace/native-language-resources/ /app/resources/", content)
            self.assertIn("WORKDIR /app\nEXPOSE 8080\n", content)

    def test_bundled_docker_context_stages_runtime_dependencies(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "project"
            context_dir = Path(temp_dir) / "context"
            classes_dir = project_dir / "__pyronaut__" / "classes"
            classes_dir.mkdir(parents=True)
            (classes_dir / "Application.class").write_text("", encoding="utf-8")
            dependency = project_dir / "ojdbc11.jar"
            dependency.write_text("jar", encoding="utf-8")
            manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
            manifest.write_text(str(dependency) + "\n", encoding="utf-8")
            launcher = project_dir / "pyronaut-run-python"
            launcher.write_text("binary", encoding="utf-8")

            cli._prepare_bundled_docker_context(  # noqa: SLF001
                project_dir=project_dir,
                context_dir=context_dir,
                launcher_executable=str(launcher),
            )

            staged_manifest = context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies"
            self.assertIn("__pyronaut__/m2-repository/ojdbc11.jar", staged_manifest.read_text(encoding="utf-8"))
            self.assertTrue((context_dir / "app" / "__pyronaut__" / "m2-repository" / "ojdbc11.jar").is_file())

    def test_native_build_passes_processed_user_packages_to_native_builder(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            if command_line[0] == "/tmp/pyronaut-native-build":
                output = Path(command_line[command_line.index("--output") + 1])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("binary", encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "user-packages"
            classes = project_dir / "__pyronaut__" / "classes" / "example" / "app"
            classes.mkdir(parents=True, exist_ok=True)
            (classes / "Application.class").write_text("", encoding="utf-8")
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.packaging]\nformat = \"wheel-native\"\n", encoding="utf-8"
            )
            exit_code = cli.run(
                ["build", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        native_command = next(command for command, _ in executed if command[0] == "/tmp/pyronaut-native-build")
        self.assertIn("--user-package", native_command)
        self.assertIn("example.app", native_command)

    def test_build_direct_source_creates_disk_backed_staging_project(self):
        captured = {}

        def fake_delegate(command, args, runner, resolver, **kwargs):
            self.assertEqual("install", command)
            project_dir = Path(args[args.index("--project-dir") + 1])
            cache = project_dir / "__pyronaut__"
            cache.mkdir(parents=True, exist_ok=True)
            (cache / "resolved-build-dependencies").write_text("/tmp/build.jar\n", encoding="utf-8")
            (cache / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache / "resolved-test-dependencies").write_text("/tmp/test.jar\n", encoding="utf-8")
            return 0

        def fake_build(**kwargs):
            captured.update(kwargs)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            source = project_dir / "App.java"
            source.write_text("package example.app; class App {}\n", encoding="utf-8")
            with patch.object(cli, "_delegate", side_effect=fake_delegate), patch.object(cli, "_run_build", side_effect=fake_build):
                exit_code = cli.run(
                    ["build", "App.java", "--native", "--name", "hello", "--version", "1.2.3", "--project-dir", str(project_dir)],
                    runner_with_env=lambda command, env: 0,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/graalvm-jdk-25",
                )

            staging_project = Path(captured["args"][captured["args"].index("--project-dir") + 1])
            self.assertEqual(0, exit_code)
            self.assertEqual(("hello", "1.2.3"), captured["project_metadata"])
            self.assertFalse(captured["preflight_install"])
            self.assertTrue((staging_project / "src-java" / "App.java").is_file())
            self.assertTrue((staging_project / "__pyronaut__" / "resolved-runtime-dependencies").is_file())
            self.assertTrue((staging_project / "__pyronaut__" / "resolved-test-dependencies").is_file())

    def test_build_direct_source_jar_is_written_to_requested_project_dist(self):
        install_args = []

        def fake_delegate(command, args, runner, resolver, **kwargs):
            install_args.append(list(args))
            project_dir = Path(args[args.index("--project-dir") + 1])
            cache = project_dir / "__pyronaut__"
            (cache / "classes").mkdir(parents=True, exist_ok=True)
            for name in ("resolved-build-dependencies", "resolved-runtime-dependencies", "resolved-test-dependencies"):
                (cache / name).write_text("/tmp/runtime.jar\n", encoding="utf-8")
            return 0

        def fake_build(**kwargs):
            self.assertIn("--jar", kwargs["args"])
            dist = kwargs["dist_dir"]
            dist.mkdir()
            (dist / "hello-1.2.3.jar").write_bytes(b"jar")
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "App.java").write_text("package example; class App {}\n", encoding="utf-8")
            with patch.object(cli, "_delegate", side_effect=fake_delegate), patch.object(cli, "_run_build", side_effect=fake_build):
                exit_code = cli.run(
                    ["build", "App.java", "--jar", "--name", "hello", "--version", "1.2.3", "--project-dir", str(project_dir)],
                    runner_with_env=lambda *_args: 0,
                    resolver=self._resolver(),
                    platform_name="linux",
                )
            self.assertEqual(0, exit_code)
            self.assertEqual(b"jar", (project_dir / "dist" / "hello-1.2.3.jar").read_bytes())
            self.assertIn("--no-ide-support", install_args[0])

    def test_build_packaging_format_defaults_to_fat_jar_for_java_sources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = 'demo'\n\n[tool.pyronaut.sources]\njava = 'src-java'\n", encoding="utf-8"
            )
            (project_dir / "src-java" / "example").mkdir(parents=True)
            (project_dir / "src-java" / "example" / "App.java").write_text("package example; class App {}\n", encoding="utf-8")

            self.assertEqual("fat-jar", cli._resolve_packaging_format(project_dir, []))
            self.assertEqual("wheel-jvm", cli._resolve_packaging_format(project_dir, ["--jvm"]))
            # A custom main class cannot be used with the FAT JAR launcher, so
            # the implied default falls back to a JVM wheel.
            self.assertEqual("wheel-jvm", cli._resolve_packaging_format(project_dir, ["--main-class", "example.App"]))

            (project_dir / "pyproject.toml").write_text(
                "[project]\nname = 'demo'\n\n[tool.pyronaut.sources]\njava = 'src-java'\n\n"
                "[tool.pyronaut.packaging]\nformat = 'wheel-native'\n",
                encoding="utf-8",
            )
            self.assertEqual("wheel-native", cli._resolve_packaging_format(project_dir, []))

    def test_build_packaging_format_defaults_to_wheel_for_python_sources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text("[project]\nname = 'demo'\n", encoding="utf-8")
            (project_dir / "src").mkdir()
            (project_dir / "src" / "main.py").write_text("print('hi')\n", encoding="utf-8")

            self.assertEqual("wheel-jvm", cli._resolve_packaging_format(project_dir, []))

    def test_build_packaging_format_defaults_to_fat_jar_for_gradle_projects(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "build.gradle.kts").write_text("plugins { java }\n", encoding="utf-8")

            self.assertEqual("fat-jar", cli._resolve_packaging_format(project_dir, []))

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
        self.assertIn("Unsupported configuration 'tool.pyronaut.build.mode'", stderr.getvalue())

    def test_pyproject_configuration_uses_tomli_when_tomllib_is_unavailable(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = 'native'\n",
                encoding="utf-8",
            )
            tomli = Mock(load=Mock(return_value={
                "tool": {"pyronaut": {"toolchain": {"type": "native"}}}
            }))
            with patch.dict(__import__("sys").modules, {"tomllib": None, "tomli": tomli}):
                self.assertEqual("native", cli._read_pyproject_toolchain_type(project_dir))
            tomli.load.assert_called_once()

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
                patch.object(cli, "_matches_requested_graalvm_home", lambda home, spec, **_kwargs: str(home) == str(expected), create=True), \
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

    def test_release_tagged_toolchain_rejects_untagged_local_graalvm(self):
        metadata = cli._GraalVmMetadata("25.0.3", 25, "ee")
        spec = cli._ToolchainSpec("ee", None, 25, "jdk-25e1-25.0.3-ea.32", None, True)
        with patch.object(cli, "_read_graalvm_metadata", lambda _home: metadata):
            matched = cli._matches_requested_graalvm_home(Path("/Users/me/.sdkman/candidates/java/25.0.3-graal"), spec)

        self.assertFalse(matched)

    def test_release_tagged_toolchain_accepts_tagged_pyronaut_cache(self):
        metadata = cli._GraalVmMetadata("25.0.3", 25, "ee")
        spec = cli._ToolchainSpec("ee", None, 25, "jdk-25e1-25.0.3-ea.32", None, True)
        home = Path("/Users/me/.pyronaut/sdks/jdk-25e1-25.0.3-ea.32/Contents/Home")
        with patch.object(cli, "_read_graalvm_metadata", lambda _home: metadata):
            matched = cli._matches_requested_graalvm_home(home, spec)

        self.assertTrue(matched)

    def test_read_pyproject_toolchain_spec_supports_dev_builds(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"

[tool.pyronaut.core]
version = "5.1.0-SNAPSHOT"

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

    def test_detect_graalvm_distribution_recognizes_community_dev_build(self):
        metadata = cli._detect_graalvm_distribution(
            'openjdk version "25.0.4.1"\nOpenJDK Runtime Environment GraalVM CE 25.4.4.1.1-dev+0.1',
            Path("/tmp/graalvm-community-25.4.4.1.1-dev+0.1"),
            "25.0.4.1",
        )
        self.assertEqual("dev", metadata)

    def test_default_graalvm_download_distribution_is_ee(self):
        spec = cli._ToolchainSpec(None, None, 25)
        with patch.object(cli.platform, "system", return_value="Linux"), \
                patch.object(cli.platform, "machine", return_value="aarch64"):
            url = cli._resolve_graalvm_archive_url(spec)
        self.assertIn("gds.oracle.com/download/graal/25i4/latest/", url)
        self.assertIn("graalvm-jdk-25i4-25_linux-aarch64_bin.tar.gz", url)

    def test_read_pyproject_toolchain_spec_uses_packaged_default_when_not_explicit(self):
        packaged = cli._ToolchainSpec("ee", None, 25, "jdk-25e1-25.0.3-ea.32", None, True)
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"

[tool.pyronaut.toolchain]
type = "native"
                """.strip(),
                encoding="utf-8",
            )

            with patch.object(cli, "_packaged_toolchain_spec", lambda: packaged):
                spec = cli._read_pyproject_toolchain_spec(project_dir)

        self.assertEqual(packaged, spec)

    def test_read_pyproject_toolchain_spec_explicit_project_config_overrides_packaged_default(self):
        packaged = cli._ToolchainSpec("ee", None, 25, "jdk-25e1-25.0.3-ea.32", None, True)
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            (project_dir / "pyproject.toml").write_text(
                """
[project]
name = "demo"

[tool.pyronaut.toolchain]
type = "native"
distribution = "ce"
java-version = 25
                """.strip(),
                encoding="utf-8",
            )

            with patch.object(cli, "_packaged_toolchain_spec", lambda: packaged):
                spec = cli._read_pyproject_toolchain_spec(project_dir)

        self.assertEqual("ce", spec.distribution)
        self.assertIsNone(spec.release_tag)
        self.assertTrue(spec.explicit)

    def test_resolve_dev_build_archive_url_uses_ce_dev_builds(self):
        requested_urls = []
        payload = {
            "assets": [
                {
                    "name": "graalvm-community-dev-linux-amd64.tar.gz",
                    "browser_download_url": "https://example.invalid/ce-dev.tar.gz",
                }
            ]
        }

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(payload).encode("utf-8")

        def urlopen(request):
            requested_urls.append(request.full_url)
            return Response()

        spec = cli._ToolchainSpec("dev", None, 25, "jdk-25.1.0-dev-20260429_0111", None, True)
        with patch.object(cli.urllib.request, "urlopen", urlopen):
            url = cli._resolve_dev_build_archive_url("linux", "x64", "tar.gz", spec)

        self.assertEqual("https://example.invalid/ce-dev.tar.gz", url)
        self.assertEqual(
            "https://api.github.com/repos/graalvm/graalvm-ce-dev-builds/releases/tags/25.1.0-dev-20260429_0111",
            requested_urls[0],
        )

    def test_resolve_dev_build_archive_url_uses_oracle_ea_builds(self):
        requested_urls = []
        payload = {
            "assets": [
                {
                    "name": "graalvm-jdk-25e1-25.0.3-ea.31_linux-x64_bin.tar.gz",
                    "browser_download_url": "https://example.invalid/oracle-ea.tar.gz",
                }
            ]
        }

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(payload).encode("utf-8")

        def urlopen(request):
            requested_urls.append(request.full_url)
            return Response()

        spec = cli._ToolchainSpec("dev", None, 25, "jdk-25e1-25.0.3-ea.31", None, True)
        with patch.object(cli.urllib.request, "urlopen", urlopen):
            url = cli._resolve_dev_build_archive_url("linux", "x64", "tar.gz", spec)

        self.assertEqual("https://example.invalid/oracle-ea.tar.gz", url)
        self.assertEqual(
            "https://api.github.com/repos/graalvm/oracle-graalvm-ea-builds/releases/tags/jdk-25e1-25.0.3-ea.31",
            requested_urls[0],
        )

    def test_resolve_graalvm_archive_url_uses_release_tag_for_packaged_oracle_default(self):
        requested_urls = []
        payload = {
            "assets": [
                {
                    "name": "graalvm-jdk-25e1-25.0.3-ea.32_linux-x64_bin.tar.gz",
                    "browser_download_url": "https://example.invalid/oracle-ea.tar.gz",
                }
            ]
        }

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(payload).encode("utf-8")

        def urlopen(request):
            requested_urls.append(request.full_url)
            return Response()

        spec = cli._ToolchainSpec("ee", None, 25, "jdk-25e1-25.0.3-ea.32", None, True)
        with patch.object(cli.urllib.request, "urlopen", urlopen), \
                patch.object(cli.platform, "system", lambda: "Linux"), \
                patch.object(cli.platform, "machine", lambda: "x86_64"):
            url = cli._resolve_graalvm_archive_url(spec)

        self.assertEqual("https://example.invalid/oracle-ea.tar.gz", url)
        self.assertEqual(
            "https://api.github.com/repos/graalvm/oracle-graalvm-ea-builds/releases/tags/jdk-25e1-25.0.3-ea.32",
            requested_urls[0],
        )

    def test_ensure_native_image_downloads_versioned_bundle_with_progress_and_reuses_cache(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            settings_dir = home / ".pyronaut"
            settings_dir.mkdir()
            (settings_dir / "settings.toml").write_text(
                "[native-images]\n"
                "base-url = \"https://example.invalid/bundles/\"\n"
                "version = \"0.0.1.dev18704471\"\n",
                encoding="utf-8",
            )
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode="w:gz") as archive:
                content = b"native-pyronaut-dev"
                info = tarfile.TarInfo("pyronaut-dev")
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
                resource = b"generated-resource"
                resource_info = tarfile.TarInfo("resources/application.properties")
                resource_info.size = len(resource)
                archive.addfile(resource_info, io.BytesIO(resource))
                manifest = b"io.micronaut:example\n"
                manifest_info = tarfile.TarInfo("native-provided-classpath.txt")
                manifest_info.size = len(manifest)
                archive.addfile(manifest_info, io.BytesIO(manifest))
            archive_bytes = payload.getvalue()
            calls = []
            requested_urls = []

            class Response:
                headers = {"Content-Length": str(len(archive_bytes))}

                def __enter__(self):
                    return self

                def __exit__(self, _exc_type, _exc, _tb):
                    return False

                def read(self, size):
                    if not archive_bytes:
                        return b""
                    value = archive_bytes[:size]
                    self.__class__.remaining = archive_bytes[size:]
                    return value

            Response.remaining = archive_bytes

            class Opener:
                def open(self, _url):
                    calls.append(True)
                    requested_urls.append(_url)
                    Response.remaining = archive_bytes
                    response = Response()
                    response.read = lambda size: self._read(response, size)
                    return response

                @staticmethod
                def _read(response, size):
                    value = Response.remaining[:size]
                    Response.remaining = Response.remaining[size:]
                    return value

            stderr = io.StringIO()
            with patch.object(cli.Path, "home", return_value=home), \
                    patch.object(cli.urllib.request, "build_opener", return_value=Opener()), \
                    patch.object(cli, "_download_proxy", return_value=(None, None)), \
                    redirect_stderr(stderr):
                first = cli._ensure_native_image(
                    "pyronaut-dev",
                    platform_name="linux",
                    machine_name="aarch64",
                )
                with patch.object(Path, "chmod", side_effect=AssertionError("cache hit touched executable")):
                    second = cli._ensure_native_image(
                        "pyronaut-dev",
                        platform_name="linux",
                        machine_name="aarch64",
                    )

            self.assertEqual(first, second)
            self.assertEqual(
                home / ".pyronaut" / "bin" / "0.0.1.dev18704471" / "linux-aarch64" / "pyronaut-dev",
                first,
            )
            self.assertEqual(b"native-pyronaut-dev", first.read_bytes())
            self.assertEqual(b"generated-resource", (first.parent / "resources/application.properties").read_bytes())
            self.assertEqual(b"io.micronaut:example\n", (first.parent / "native-provided-classpath.txt").read_bytes())
            metadata = cli.json.loads(first.with_name("pyronaut-dev.json").read_text(encoding="utf-8"))
            self.assertEqual(
                [
                    "pyronaut-dev",
                    "resources/application.properties",
                    "resources/pyronaut-dev/native-provided-classpath.txt",
                ],
                metadata["bundle-files"],
            )
            self.assertTrue(first.stat().st_mode & 0o111)
            self.assertEqual(1, len(calls))
            self.assertEqual(
                "https://example.invalid/bundles/pyronaut-dev-linux-aarch64-0.0.1.dev18704471.tar.gz",
                requested_urls[0],
            )
            self.assertIn("0%", stderr.getvalue())
            self.assertIn("100%", stderr.getvalue())

    def test_github_release_url_resolves_public_and_private_assets(self):
        archive_name = "pyronaut-dev-linux-amd64-0.0.1.tar.gz"
        release = {"tag_name": "v0.0.1", "draft": True, "assets": [{"name": archive_name, "url": "https://api.github.com/assets/7"}]}

        class Response:
            def __init__(self, value):
                self.value = value

            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(self.value).encode()

        class Opener:
            def __init__(self):
                self.requests = []

            def open(self, request):
                self.requests.append(request)
                return Response(release)

        token_names = ("GH_TOKEN", "GITHUB_TOKEN", "GITHUB_API_TOKEN", "PYRONAUT_RELEASE_TOKEN")
        for token_name, token in ((None, None), ("PYRONAUT_RELEASE_TOKEN", "read-only-token")):
            opener = Opener()
            environment = {name: "" for name in token_names}
            if token_name is not None:
                environment[token_name] = token
            with patch.dict(os.environ, environment, clear=False), \
                    patch.object(cli, "_download_proxy", return_value=(None, None)), \
                    patch.object(cli.urllib.request, "build_opener", return_value=opener):
                result = cli._github_release_asset(
                    "https://github.com/micronaut-projects/pyronaut/releases",
                    "0.0.1",
                    archive_name,
                    allow_draft=True,
                )

            self.assertEqual("https://api.github.com/assets/7", result[0])
            request_headers = dict(opener.requests[0].header_items())
            if token:
                self.assertEqual("Bearer read-only-token", request_headers["Authorization"])
            else:
                self.assertNotIn("Authorization", request_headers)

    def test_github_release_url_uses_explicit_release_tag_without_normalizing_it(self):
        archive_name = "pyronaut-dev-linux-amd64-0.0.1-SNAPSHOT.tar.gz"
        release_tag = "untagged-335b1cec0d28f835a836"
        release = {"tag_name": release_tag, "draft": True, "assets": [{"name": archive_name, "url": "asset"}]}

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(release).encode()

        class Opener:
            def __init__(self):
                self.requests = []

            def open(self, request):
                self.requests.append(request)
                return Response()

        opener = Opener()
        with patch.dict(
            os.environ,
            {name: "" for name in ("GH_TOKEN", "GITHUB_TOKEN", "GITHUB_API_TOKEN", "PYRONAUT_RELEASE_TOKEN")},
            clear=False,
        ), patch.object(cli, "_download_proxy", return_value=(None, None)), patch.object(
            cli.urllib.request, "build_opener", return_value=opener
        ):
            result = cli._github_release_asset(
                "https://github.com/micronaut-projects/pyronaut/releases",
                "0.0.1-SNAPSHOT",
                archive_name,
                allow_draft=True,
                release_tag=release_tag,
            )

        self.assertEqual("asset", result[0])
        self.assertEqual(
            "https://api.github.com/repos/micronaut-projects/pyronaut/releases/tags/untagged-335b1cec0d28f835a836",
            opener.requests[0].full_url,
        )

    def test_github_release_url_derives_tag_from_native_image_version(self):
        archive_name = "pyronaut-dev-linux-amd64-0.0.1-SNAPSHOT.tar.gz"
        release = {
            "tag_name": "v0.0.1-SNAPSHOT",
            "draft": False,
            "assets": [{"name": archive_name, "url": "asset"}],
        }

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(release).encode()

        class Opener:
            def __init__(self):
                self.requests = []

            def open(self, request):
                self.requests.append(request)
                return Response()

        opener = Opener()
        with patch.dict(
            os.environ,
            {name: "" for name in ("GH_TOKEN", "GITHUB_TOKEN", "GITHUB_API_TOKEN", "PYRONAUT_RELEASE_TOKEN")},
            clear=False,
        ), patch.object(cli, "_download_proxy", return_value=(None, None)), patch.object(
            cli.urllib.request, "build_opener", return_value=opener
        ):
            result = cli._github_release_asset(
                "https://github.com/micronaut-projects/pyronaut/releases",
                "0.0.1-SNAPSHOT",
                archive_name,
            )

        self.assertEqual("asset", result[0])
        self.assertEqual(
            "https://api.github.com/repos/micronaut-projects/pyronaut/releases/tags/v0.0.1-SNAPSHOT",
            opener.requests[0].full_url,
        )

    def test_native_image_configuration_reads_explicit_release_tag(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            settings_dir = home / ".pyronaut"
            settings_dir.mkdir()
            (settings_dir / "settings.toml").write_text(
                "[native-images]\n"
                'base-url = "https://github.com/micronaut-projects/pyronaut/releases"\n'
                'version = "0.0.1-SNAPSHOT"\n'
                'release-tag = "untagged-335b1cec0d28f835a836"\n',
                encoding="utf-8",
            )

            with patch.object(cli.Path, "home", return_value=home):
                configuration = cli._native_image_configuration()

        self.assertEqual(
            (
                "https://github.com/micronaut-projects/pyronaut/releases/",
                "0.0.1-SNAPSHOT",
                "untagged-335b1cec0d28f835a836",
            ),
            configuration,
        )

    def test_default_native_image_base_url_uses_github_releases(self):
        self.assertEqual(
            "https://github.com/micronaut-projects/pyronaut/releases",
            cli._NATIVE_IMAGE_BASE_URL,
        )

    def test_native_image_configuration_defaults_to_github_releases(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.object(cli.Path, "home", return_value=Path(temp_dir)), patch.object(
                cli, "_installed_pyronaut_version", return_value="0.0.1.dev0"
            ):
                configuration = cli._native_image_configuration()

        self.assertEqual(
            (
                "https://github.com/micronaut-projects/pyronaut/releases/",
                "0.0.1-SNAPSHOT",
                None,
            ),
            configuration,
        )

    def test_native_image_cache_separates_release_tags(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            settings_dir = home / ".pyronaut"
            settings_dir.mkdir()
            settings_path = settings_dir / "settings.toml"
            settings_path.write_text(
                "[native-images]\n"
                'base-url = "https://github.com/micronaut-projects/pyronaut/releases"\n'
                'version = "0.0.1-SNAPSHOT"\n'
                'release-tag = "tag-a"\n',
                encoding="utf-8",
            )
            downloaded_urls = []
            resolved_tags = []

            def resolve_asset(_base_url, _version, _archive_name, *, allow_draft, release_tag):
                self.assertTrue(allow_draft)
                resolved_tags.append(release_tag)
                return f"https://example.invalid/{release_tag}.tar.gz", {}

            def download_archive(_url, destination, _image_name, headers=None):
                downloaded_urls.append(_url)
                with tarfile.open(destination, "w:gz") as archive:
                    content = b"native-image"
                    info = tarfile.TarInfo("pyronaut-dev")
                    info.size = len(content)
                    archive.addfile(info, io.BytesIO(content))

            with patch.object(cli.Path, "home", return_value=home), \
                    patch.object(cli, "_github_release_asset", side_effect=resolve_asset), \
                    patch.object(cli, "_download_native_image_archive", side_effect=download_archive), \
                    patch.object(cli, "_allow_draft_release", True):
                first = cli._ensure_native_image(
                    "pyronaut-dev",
                    platform_name="linux",
                    machine_name="x86_64",
                )
                settings_path.write_text(
                    "[native-images]\n"
                    'base-url = "https://github.com/micronaut-projects/pyronaut/releases"\n'
                    'version = "0.0.1-SNAPSHOT"\n'
                    'release-tag = "tag-b"\n',
                    encoding="utf-8",
                )
                second = cli._ensure_native_image(
                    "pyronaut-dev",
                    platform_name="linux",
                    machine_name="x86_64",
                )

            self.assertEqual(first, second)
            self.assertEqual(["tag-a", "tag-b"], resolved_tags)
            self.assertEqual(
                ["https://example.invalid/tag-a.tar.gz", "https://example.invalid/tag-b.tar.gz"],
                downloaded_urls,
            )
            metadata = cli.json.loads(first.with_name("pyronaut-dev.json").read_text(encoding="utf-8"))
            self.assertEqual("tag-b", metadata["release-tag"])

    def test_github_release_url_ignores_draft_without_explicit_flag(self):
        archive_name = "pyronaut-dev-linux-amd64-0.0.1.tar.gz"
        responses = [
            {"tag_name": "v0.0.1", "draft": True, "assets": [{"name": archive_name, "url": "draft"}]},
            [{"tag_name": "v0.0.1", "draft": True, "assets": [{"name": archive_name, "url": "draft"}]}],
        ]

        class Response:
            def __init__(self, value):
                self.value = value

            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return cli.json.dumps(self.value).encode()

        class Opener:
            def open(self, _request):
                return Response(responses.pop(0))

        with patch.object(cli, "_download_proxy", return_value=(None, None)), \
                patch.object(cli.urllib.request, "build_opener", return_value=Opener()):
            with self.assertRaisesRegex(RuntimeError, "release 'v0.0.1' was not found"):
                cli._github_release_asset(
                    "https://github.com/micronaut-projects/pyronaut/releases",
                    "0.0.1",
                    archive_name,
                )

    def test_native_image_distribution_version_maps_wheel_snapshot(self):
        self.assertEqual("0.0.1-SNAPSHOT", cli._native_image_distribution_version("0.0.1.dev0"))
        self.assertEqual("0.0.1", cli._native_image_distribution_version("0.0.1"))

    def test_github_release_url_reports_missing_version_or_asset(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self):
                return b"[]"

        class Opener:
            def open(self, _request):
                return Response()

        with patch.object(cli, "_download_proxy", return_value=(None, None)), \
                patch.object(cli.urllib.request, "build_opener", return_value=Opener()):
            with self.assertRaisesRegex(RuntimeError, "release 'v0.0.1' was not found"):
                cli._github_release_asset(
                    "https://github.com/micronaut-projects/pyronaut/releases",
                    "0.0.1",
                    "missing.tar.gz",
                )

        release = {"tag_name": "v0.0.1", "assets": []}
        with patch.object(cli, "_download_proxy", return_value=(None, None)), \
                patch.object(cli.urllib.request, "build_opener", return_value=type("Opener", (), {
                    "open": lambda _self, _request: type("Response", (), {
                        "__enter__": lambda self: self,
                        "__exit__": lambda self, *_args: False,
                        "read": lambda self: cli.json.dumps(release).encode(),
                    })(),
                })()):
            with self.assertRaisesRegex(RuntimeError, "has no asset named 'missing.tar.gz'"):
                cli._github_release_asset(
                    "https://github.com/micronaut-projects/pyronaut/releases",
                    "0.0.1",
                    "missing.tar.gz",
                )

    def test_native_image_platform_maps_supported_operating_systems_and_architectures(self):
        self.assertEqual(("linux", "amd64"), cli._native_image_platform(platform_name="Linux", machine_name="x86_64"))
        self.assertEqual(("linux", "aarch64"), cli._native_image_platform(platform_name="linux", machine_name="arm64"))
        self.assertEqual(("macos", "aarch64"), cli._native_image_platform(platform_name="Darwin", machine_name="aarch64"))
        with self.assertRaises(RuntimeError):
            cli._native_image_platform(platform_name="Windows", machine_name="x86_64")

    def test_create_sdk_platform_maps_published_native_archives(self):
        self.assertEqual(("darwin", "amd64", "mn"), cli._create_sdk_platform("darwin", "x86_64"))
        self.assertEqual(("darwin", "aarch64", "mn"), cli._create_sdk_platform("darwin", "arm64"))
        self.assertEqual(("linux", "amd64", "mn"), cli._create_sdk_platform("linux", "amd64"))
        self.assertEqual(("win", "amd64", "mn.exe"), cli._create_sdk_platform("win32", "AMD64"))
        with self.assertRaises(cli._CreatePreconditionError):
            cli._create_sdk_platform("linux", "aarch64")

    def test_create_sdk_prefers_exact_sdkman_candidate_and_ignores_mismatched_current(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            exact = home / ".sdkman" / "candidates" / "micronaut" / "5.2.0" / "bin" / "mn"
            exact.parent.mkdir(parents=True)
            exact.write_text("#!/bin/sh\necho 'Micronaut Version: 5.2.0'\n", encoding="utf-8")
            exact.chmod(0o755)
            other = exact.parent.parent.parent / "5.1.3"
            other_bin = other / "bin" / "mn"
            other_bin.parent.mkdir(parents=True)
            other_bin.write_text("#!/bin/sh\necho 'Micronaut Version: 5.1.3'\n", encoding="utf-8")
            other_bin.chmod(0o755)
            (exact.parent.parent.parent / "current").symlink_to(other)
            with patch.object(cli.Path, "home", return_value=home), patch.object(cli.platform, "machine", return_value="x86_64"), \
                    patch.dict(os.environ, {"SDKMAN_DIR": ""}, clear=False):
                self.assertEqual(exact, cli._ensure_micronaut_launch("5.2.0", platform_name="darwin"))

    def test_create_sdk_reuses_pyronaut_cache_and_does_not_download_snapshot(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            cached = home / ".pyronaut" / "sdks" / "micronaut" / "5.2.0-SNAPSHOT" / "bin" / "mn"
            cached.parent.mkdir(parents=True)
            cached.write_text("#!/bin/sh\necho 'Micronaut Version: 5.2.0-SNAPSHOT'\n", encoding="utf-8")
            cached.chmod(0o755)
            with patch.object(cli.Path, "home", return_value=home), patch.object(cli.platform, "machine", return_value="x86_64"), \
                    patch.dict(os.environ, {"SDKMAN_DIR": ""}, clear=False), \
                    patch.object(cli, "_download_url_with_progress", side_effect=AssertionError("downloaded cached SDK")):
                self.assertEqual(cached, cli._ensure_micronaut_launch("5.2.0-SNAPSHOT", platform_name="linux"))

            missing = home / ".pyronaut" / "sdks" / "micronaut" / "5.2.1"
            with patch.object(cli.Path, "home", return_value=home), patch.object(cli.platform, "machine", return_value="x86_64"), \
                    patch.dict(os.environ, {"SDKMAN_DIR": ""}, clear=False):
                with self.assertRaisesRegex(cli._CreatePreconditionError, "not a published release"):
                    cli._ensure_micronaut_launch("5.2.1-SNAPSHOT", platform_name="linux")

    def test_create_sdk_downloads_expected_release_archive_with_progress_and_installs_atomically(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            downloaded = []

            def download(url, destination, label, headers=None):
                downloaded.append((url, label, headers))
                with zipfile.ZipFile(destination, "w") as archive:
                    archive.writestr(
                        "mn-linux-amd64-v5.2.0/bin/mn",
                        "#!/bin/sh\necho 'Micronaut Version: 5.2.0'\n",
                    )
                    archive.writestr("mn-linux-amd64-v5.2.0/LICENSE", "license")

            with patch.object(cli.Path, "home", return_value=home), patch.object(cli.platform, "machine", return_value="x86_64"), \
                    patch.object(cli, "_download_url_with_progress", side_effect=download), redirect_stderr(io.StringIO()) as stderr:
                executable = cli._ensure_micronaut_launch("5.2.0", platform_name="linux")

            self.assertEqual(1, len(downloaded))
            self.assertEqual(
                "https://github.com/micronaut-projects/micronaut-starter/releases/download/v5.2.0/mn-linux-amd64-v5.2.0.zip",
                downloaded[0][0],
            )
            self.assertEqual("Downloading Micronaut Launch SDK", downloaded[0][1])
            self.assertEqual("Micronaut Version: 5.2.0", subprocess.check_output([str(executable), "--version"], text=True).strip())
            self.assertTrue(executable.stat().st_mode & 0o111)
            self.assertTrue((executable.parent.parent / "LICENSE").is_file())
            self.assertIn("Project Creation Tooling is being installed", stderr.getvalue())

    def test_ensure_native_image_uses_bundle_from_local_checkout(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            checkout = root / "pyronaut"
            distribution = checkout / "pyronaut-dev" / "build" / "distributions"
            distribution.mkdir(parents=True)
            archive_path = distribution / "pyronaut-dev-macos-aarch64-0.0.1-SNAPSHOT.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                content = b"local-native-image"
                info = tarfile.TarInfo("pyronaut-dev")
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
                resource = b"local-resource"
                resource_info = tarfile.TarInfo("resources/application.properties")
                resource_info.size = len(resource)
                archive.addfile(resource_info, io.BytesIO(resource))
            settings_dir = root / ".pyronaut"
            settings_dir.mkdir()
            (settings_dir / "settings.toml").write_text(
                f'[native-images]\nbase-url = "{checkout}"\nversion = "0.0.1-SNAPSHOT"\n',
                encoding="utf-8",
            )
            with patch.object(cli.Path, "home", return_value=root), \
                    patch.object(cli, "_download_native_image_archive", side_effect=AssertionError("downloaded local bundle")):
                executable = cli._ensure_native_image(
                    "pyronaut-dev",
                    platform_name="darwin",
                    machine_name="arm64",
                )

            self.assertEqual(b"local-native-image", executable.read_bytes())
            self.assertEqual(b"local-resource", (executable.parent / "resources/application.properties").read_bytes())
            metadata = cli.json.loads(executable.with_name("pyronaut-dev.json").read_text(encoding="utf-8"))
            self.assertEqual(archive_path.resolve().as_uri(), metadata["url"])

    def test_native_image_download_uses_pyronaut_proxy_settings_and_bypass(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            settings_dir = home / ".pyronaut"
            settings_dir.mkdir()
            (settings_dir / "settings.toml").write_text(
                '[proxy]\nurl = "http://proxy.example:3128"\nnonProxyHosts = "localhost|*.internal"\n',
                encoding="utf-8",
            )
            proxy_names = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "NO_PROXY", "no_proxy")
            with patch.object(cli.Path, "home", return_value=home), patch.dict(
                os.environ, {name: "" for name in proxy_names}, clear=False
            ):
                self.assertEqual(
                    ("http://proxy.example:3128", "localhost|*.internal"),
                    cli._download_proxy("https://downloads.example/native.tar.gz"),
                )
                self.assertTrue(cli._proxy_bypasses_host("localhost", "localhost|*.internal"))
                self.assertTrue(cli._proxy_bypasses_host("service.internal", "localhost|*.internal"))

    def test_maven_proxy_fallback_supports_default_xml_namespace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            maven_dir = home / ".m2"
            maven_dir.mkdir()
            (maven_dir / "settings.xml").write_text(
                """<?xml version="1.0"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <proxies><proxy><active>true</active><protocol>http</protocol>
    <host>proxy.example</host><port>3128</port>
    <nonProxyHosts>*.oracle.com|localhost</nonProxyHosts>
  </proxy></proxies>
</settings>
""",
                encoding="utf-8",
            )
            proxy_names = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "NO_PROXY", "no_proxy")
            with patch.object(cli.Path, "home", return_value=home), patch.dict(
                os.environ, {name: "" for name in proxy_names}, clear=False
            ):
                self.assertEqual(
                    ("http://proxy.example:3128", "*.oracle.com|localhost"),
                    cli._download_proxy("https://downloads.example/native.tar.gz"),
                )

    def test_large_download_retries_an_interrupted_stream(self):
        payload = b"retryable-download"
        attempts = []

        class Response:
            headers = {"Content-Length": str(len(payload))}

            def __init__(self, interrupted):
                self.interrupted = interrupted
                self.remaining = payload

            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self, _size):
                if self.interrupted:
                    self.interrupted = False
                    raise OSError("connection closed while reading")
                value, self.remaining = self.remaining, b""
                return value

        class Opener:
            def open(self, _request):
                attempts.append(True)
                return Response(len(attempts) == 1)

        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "download.tar.gz"
            stderr = io.StringIO()
            with patch.object(cli.urllib.request, "build_opener", return_value=Opener()), \
                    patch.object(cli, "_download_proxy", return_value=(None, None)), \
                    patch.object(cli.time, "sleep"), \
                    redirect_stderr(stderr):
                cli._download_url_with_progress(
                    "https://example.invalid/download.tar.gz",
                    destination,
                    "Downloading test asset",
                )

            self.assertEqual(payload, destination.read_bytes())
            self.assertEqual(2, len(attempts))
            self.assertIn("download interrupted; retrying", stderr.getvalue())
            self.assertNotIn("\r", stderr.getvalue())

    def test_graalvm_sdk_download_uses_shared_progress_reporting(self):
        payload = b"graalvm-sdk"

        class Response:
            headers = {"Content-Length": str(len(payload))}

            def __enter__(self):
                return self

            def __exit__(self, _exc_type, _exc, _tb):
                return False

            def read(self, _size):
                value, self.remaining = self.remaining, b""
                return value

        class Opener:
            def open(self, _url):
                response = Response()
                response.remaining = payload
                return response

        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "graalvm.tar.gz"
            stderr = io.StringIO()
            with patch.object(cli.urllib.request, "build_opener", return_value=Opener()), \
                    patch.object(cli, "_download_proxy", return_value=(None, None)), \
                    redirect_stderr(stderr):
                cli._download_graalvm_archive("https://example.invalid/graalvm.tar.gz", destination)

            self.assertEqual(payload, destination.read_bytes())
            self.assertIn("Downloading GraalVM JDK... 0%", stderr.getvalue())
            self.assertIn("Downloading GraalVM JDK... 100%", stderr.getvalue())
            self.assertNotIn("\r", stderr.getvalue())

    def test_default_native_base_uses_host_bundle_selection(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            requested = []

            def ensure(image_name, **kwargs):
                requested.append((image_name, kwargs))
                return project_dir / image_name

            with patch.object(cli, "_ensure_native_image", side_effect=ensure):
                cli._bundled_default_native_base(project_dir)

            self.assertEqual(
                [("pyronaut-run", {"platform_name": None})],
                requested,
            )

    def test_default_native_base_selects_python_host_bundle(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            requested = []

            def ensure(image_name, **kwargs):
                requested.append((image_name, kwargs))
                return project_dir / image_name

            with patch.object(cli, "_is_python_runtime_project", return_value=True), \
                    patch.object(cli, "_ensure_native_image", side_effect=ensure):
                cli._bundled_default_native_base(project_dir)

            self.assertEqual(
                [("pyronaut-run-python", {"platform_name": None})],
                requested,
            )

    def test_custom_native_base_url_uses_download_progress_helper_and_honors_offline(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            target = project_dir / "staged" / "pyronaut-run"
            (target.parent / "resources").mkdir(parents=True)
            (target.parent / "resources" / "stale.txt").write_text("stale", encoding="utf-8")
            (target.parent / "stale.dll").write_bytes(b"stale")

            def download(url, destination, label):
                self.assertEqual("https://example.test/pyronaut-run", url)
                self.assertEqual("Downloading native base", label)
                destination.write_bytes(b"native")

            with patch.object(cli, "_download_url_with_progress", side_effect=download):
                cli._stage_native_base_executable(  # noqa: SLF001
                    "https://example.test/pyronaut-run",
                    project_dir=project_dir,
                    target=target,
                    offline=False,
                )

            self.assertEqual(b"native", target.read_bytes())
            self.assertTrue(target.stat().st_mode & 0o100)
            self.assertFalse((target.parent / "resources").exists())
            self.assertFalse((target.parent / "stale.dll").exists())
            with self.assertRaisesRegex(RuntimeError, "offline"):
                cli._stage_native_base_executable(  # noqa: SLF001
                    "https://example.test/pyronaut-run",
                    project_dir=project_dir,
                    target=target,
                    offline=True,
                )

    def test_local_native_base_in_output_directory_keeps_existing_bundle_support(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir)
            staged = project_dir / "__pyronaut__" / "native"
            staged.mkdir(parents=True)
            source = staged / "pyronaut-run-python"
            source.write_bytes(b"native")
            (staged / "resources" / "python").mkdir(parents=True)
            (staged / "resources" / "python" / "stdlib.txt").write_text("stdlib", encoding="utf-8")
            (staged / "libpython.dylib").write_bytes(b"library")

            cli._stage_native_base_executable(  # noqa: SLF001
                str(source),
                project_dir=project_dir,
                target=staged / "demo",
                offline=False,
            )

            self.assertEqual(b"native", (staged / "demo").read_bytes())
            self.assertEqual(
                "stdlib", (staged / "resources" / "python" / "stdlib.txt").read_text(encoding="utf-8")
            )
            self.assertEqual(b"library", (staged / "libpython.dylib").read_bytes())

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
                ["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir), "--pass", "main"],
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
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-run"),
                "--test-executable",
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-test"),
            ],
            executed[1][0],
        )
        self._assert_test_resources_stop(executed[2][0], str(expected_project_dir))
        self.assertIsInstance(executed[1][1], dict)
        assert isinstance(executed[1][1], dict)
        self.assertIn("JAVA_HOME", executed[1][1])
        self.assertEqual("http://localhost:18900", executed[1][1].get("MICRONAUT_TEST_RESOURCES_SERVER_URI"))

    def test_tui_interactive_activates_project_virtualenv(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "tui-with-venv"
            project_dir.mkdir(parents=True, exist_ok=True)
            self._write_test_resources_enabled(project_dir, enabled=False)
            expected_project_dir = project_dir.resolve()
            venv_bin = project_dir / ".venv" / "bin"
            venv_bin.mkdir(parents=True, exist_ok=True)
            (venv_bin / "python").write_text("", encoding="utf-8")

            with patch.dict(os.environ, {"PATH": "/usr/bin", "PYTHONHOME": "/tmp/python-home"}, clear=False):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        _, env = executed[0]
        self.assertIsInstance(env, dict)
        assert isinstance(env, dict)
        self.assertEqual(str(expected_project_dir / ".venv"), env.get("VIRTUAL_ENV"))
        self.assertEqual(
            str(expected_project_dir / ".venv" / "bin" / "python"),
            env.get("PYRONAUT_PYTHON_EXECUTABLE"),
        )
        self.assertNotIn("PYTHONHOME", env)
        self.assertTrue(env.get("PATH", "").startswith(str(expected_project_dir / ".venv" / "bin") + os.pathsep))

    def test_tui_interactive_uses_native_run_toolchain(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "native-tui"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"native\"\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["--tui", "--project-dir", str(project_dir)],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        tui_command = executed[0]
        self.assertIn("--native-dev-executable", tui_command)
        native_command_indices = [
            index for index, value in enumerate(tui_command) if value == "--native-command"
        ]
        native_commands = [tui_command[index + 1] for index in native_command_indices]
        self.assertIn("run", native_commands)

    def test_tui_direct_source_run_does_not_require_pyproject(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")
            with patch("os.getcwd", return_value=temp_dir):
                exit_code = cli.run(
                    ["--tui", str(source)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        command = next(command for command in executed if "--direct-dev-executable" in command)
        self.assertIn("--direct-dev-executable", command)
        direct_dev_index = command.index("--direct-dev-executable") + 1
        self.assertTrue(command[direct_dev_index].endswith("/bin/pyronaut-dev"))
        self.assertIn("--direct-arg", command)
        self.assertIn(str(source), command)
        self.assertNotIn("--validate-executable", command)
        self.assertNotIn("--process-executable", command)

    def test_tui_direct_source_preserves_direct_options_and_report_path(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "App.java"
            report_path = Path(temp_dir) / "reports"
            source.write_text("class App {}\n", encoding="utf-8")
            with patch("os.getcwd", return_value=temp_dir):
                exit_code = cli.run(
                    [
                        str(source),
                        "--tui",
                        "--report",
                        str(report_path),
                        "--port",
                        "8181",
                        "--property",
                        "a.b=c",
                    ],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        command = next(command for command in executed if "--direct-dev-executable" in command)
        self.assertEqual(str(report_path.resolve()), command[command.index("--report-dir") + 1])
        direct_args = [command[index + 1] for index, value in enumerate(command) if value == "--direct-arg"]
        self.assertTrue(direct_args[-5:] == [str(source), "--port", "8181", "--property", "a.b=c"])

    def test_tui_passes_orchestrator_java_home_and_toolchain_to_direct_launcher(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")
            with patch("os.getcwd", return_value=temp_dir):
                exit_code = cli.run(
                    [str(source), "--tui"],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                    java_home_provider=lambda: "/tmp/selected-graalvm",
                )

        self.assertEqual(0, exit_code)
        command, env = next(item for item in executed if "--direct-dev-executable" in item[0])
        self.assertEqual("/tmp/selected-graalvm", command[command.index("--java-home") + 1])
        self.assertEqual("jvm", command[command.index("--toolchain-type") + 1])
        self.assertEqual("/tmp/selected-graalvm", env["JAVA_HOME"])

    def test_tui_direct_source_test_preserves_explicit_test_sources(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "App.java"
            test_source = Path(temp_dir) / "AppTest.java"
            source.write_text("class App {}\n", encoding="utf-8")
            test_source.write_text("class AppTest {}\n", encoding="utf-8")
            with patch("os.getcwd", return_value=temp_dir):
                exit_code = cli.run(
                    ["--tui", "--test", str(source), "--", str(test_source)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        command = next(command for command in executed if "--direct-dev-executable" in command)
        direct_args = [command[index + 1] for index, value in enumerate(command) if value == "--direct-arg"]
        self.assertEqual([str(source), "--", str(test_source)], direct_args[-3:])

    def test_tui_direct_source_test_omits_separator_for_implicit_discovery(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "App.java"
            source.write_text("class App {}\n", encoding="utf-8")
            with patch("os.getcwd", return_value=temp_dir):
                exit_code = cli.run(
                    ["--tui", "--test", str(source)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        command = next(command for command in executed if "--direct-dev-executable" in command)
        direct_args = [command[index + 1] for index, value in enumerate(command) if value == "--direct-arg"]
        self.assertEqual([str(source)], direct_args[-1:])

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
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-run"),
                "--test-executable",
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-test"),
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
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-run"),
                "--test-executable",
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-test"),
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
        self.assertEqual(1, len(executed))
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
                str(self._fake_dev_delegate_root / "bin" / "pyronaut-run"),
                "--test-executable",
                str(native_test),
            ],
            executed[0][0],
        )

    def test_tui_interactive_uses_native_dev_for_global_native_toolchain(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\n"
                "type = \"native\"\n"
                "[tool.pyronaut.test-resources]\n"
                "enabled = false\n",
                encoding="utf-8",
            )
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)

            with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        self.assertIn("--native-dev-executable", executed[0][0])
        self.assertIn(str(native_dev), executed[0][0])
        native_command_names = [
            executed[0][0][index + 1]
            for index, token in enumerate(executed[0][0])
            if token == "--native-command"
        ]
        self.assertEqual(["validate-config", "install", "process", "run", "test"], native_command_names)

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
        self.assertEqual(3, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(expected_project_dir), "--scenario", "test"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir), "--pass", "all"], executed[1])
        self._assert_test_delegate(executed[2], str(expected_project_dir), expect_project_jars=False)
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
        self.assertEqual(3, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "main"], executed[1])
        self._assert_run_delegate(executed[2], "/tmp/demo")

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
        self.assertEqual(5, len(executed))
        self._assert_test_resources_start(executed[0], "/tmp/demo")
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "test"],
            executed[1],
        )
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "all"], executed[2])
        self._assert_test_delegate(executed[3], "/tmp/demo")
        self._assert_test_resources_stop(executed[4], "/tmp/demo")

    def test_disable_test_resources_flag_skips_server_and_delegate_forwarding(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)

            exit_code = cli.run(
                ["test", "--project-dir", str(project_dir), "--disable-test-resources"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(3, len(executed))
        self.assertEqual(
            ["/tmp/pyronaut-validate-config", "--project-dir", str(project_dir), "--scenario", "test"],
            executed[0],
        )
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", str(project_dir), "--pass", "all"], executed[1])
        self._assert_test_delegate(executed[2], str(project_dir))
        self.assertNotIn("--disable-test-resources", executed[2])
        self.assertFalse(any("pyronaut-test-resources-server" in command[0] for command in executed))
        # The stripped flag still disables the client explicitly, even though
        # pyproject.toml enables Test Resources.
        self._assert_test_resources_client_disabled(executed[2])

    def test_disable_test_resources_flag_disables_client_for_project_dev(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_dir = root / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            (project_dir / "__pyronaut__" / "resolved-runtime-dependencies").write_text(
                "\n".join(["/tmp/runtime.jar", *(str(root / name) for name in self._TEST_RESOURCES_CLIENT_JARS)]) + "\n",
                encoding="utf-8",
            )
            (project_dir / "pyproject.toml").write_text(
                "[tool.pyronaut.toolchain]\ntype = \"jvm\"\n\n[tool.pyronaut.test-resources]\nenabled = true\n",
                encoding="utf-8",
            )

            exit_code = cli.run(
                ["dev", "--project-dir", str(project_dir), "--disable-test-resources"],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertFalse(any("pyronaut-test-resources-server" in command[0] for command in executed))
        dev_command = executed[-1]
        self.assertIn(self._RUN_MAIN, dev_command)
        self.assertNotIn("--disable-test-resources", dev_command)
        self._assert_test_resources_client_disabled(dev_command)

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
        self.assertIn("Configuration validation skipped (--no-validate)", stderr.getvalue())

    def test_test_resources_disabled_env_skips_server_orchestration_quietly(self):
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
        self.assertEqual(3, len(executed))
        self.assertEqual(["/tmp/pyronaut-validate-config", "--project-dir", "/tmp/demo", "--scenario", "run"], executed[0])
        self.assertEqual(["/tmp/pyronaut-processor", "--project-dir", "/tmp/demo", "--pass", "main"], executed[1])
        self._assert_run_delegate(executed[2], "/tmp/demo")
        self.assertFalse(any("pyronaut-test-resources-server" in cmd[0] for cmd in executed))
        self.assertNotIn("[test-resources]", stderr.getvalue())

    def test_test_resources_disabled_pyproject_skips_server_orchestration_quietly(self):
        executed = []
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "disabled"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            self._write_test_resources_enabled(project_dir, enabled=False)

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
        self.assertEqual(3, len(executed))
        self.assertFalse(any("pyronaut-test-resources-server" in cmd[0] for cmd in executed))
        self.assertNotIn("[test-resources]", stderr.getvalue())

    def test_missing_test_resources_pyproject_config_skips_server_orchestration_quietly(self):
        executed = []
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "unconfigured"
            cache_dir = project_dir / "__pyronaut__"
            (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
            (cache_dir / "resolved-runtime-dependencies").write_text("/tmp/runtime.jar\n", encoding="utf-8")
            (cache_dir / "resolved-test-dependencies").write_text("/tmp/test.jar\n", encoding="utf-8")
            (cache_dir / "resolved-build-dependencies").write_text("/tmp/build.jar\n", encoding="utf-8")

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
        self.assertEqual(3, len(executed))
        self.assertFalse(any("pyronaut-test-resources-server" in cmd[0] for cmd in executed))
        self.assertNotIn("[test-resources]", stderr.getvalue())

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

    def test_test_resources_server_start_receives_java_home_from_provider(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append((command_line, env))
            return 0

        with patch.dict(os.environ, {"PATH": "/usr/bin"}, clear=True):
            exit_code = cli.run(
                ["test-resources-server", "start", "--project-dir", "/tmp/demo"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk-25",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            [
                ["/tmp/pyronaut-install", "--project-dir", "/tmp/demo"],
                ["/tmp/pyronaut-test-resources-server", "start", "--project-dir", "/tmp/demo"],
            ],
            [command_line for command_line, _ in executed],
        )
        server_env = executed[1][1]
        self.assertIsInstance(server_env, dict)
        self.assertEqual("/tmp/graalvm-jdk-25", server_env["JAVA_HOME"])
        self.assertTrue(server_env["PATH"].startswith("/tmp/graalvm-jdk-25/bin" + os.pathsep))

    def test_create_command_delegates_to_micronaut_launch_with_fixed_python_options(self):
        executed = []

        def runner(command_line, env=None):
            executed.append(command_line)
            return 0

        with (
            patch.object(cli, "_micronaut_platform_version", return_value="5.2.0"),
            patch.object(cli, "_ensure_micronaut_launch", return_value=Path("/tmp/mn")),
        ):
            exit_code = cli.run(
                ["create", "demo", "--features", "data-jdbc,mysql"],
                runner_with_env=runner,
                platform_name="linux",
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        self.assertEqual(
            [[
                "/tmp/mn", "create-app", "demo", "--features", "data-jdbc,mysql",
                "--lang", "python", "--build", "pyronaut", "--test", "pytest",
            ]],
            executed,
        )

    def test_create_rejects_hidden_micronaut_options(self):
        executed = []

        with patch.object(cli, "_micronaut_platform_version", return_value="5.2.0"):
            exit_code = cli.run(
                ["create", "demo", "--lang", "python"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertEqual([], executed)

    def test_create_rejects_known_python_incompatible_feature(self):
        executed = []
        stderr = io.StringIO()
        with (
            patch.object(cli, "_micronaut_platform_version", return_value="5.2.0"),
            redirect_stderr(stderr),
        ):
            exit_code = cli.run(
                ["create", "demo", "--features", "jackson-databind"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertIn("not supported for Python", stderr.getvalue())
        self.assertEqual([], executed)

    def test_create_requires_micronaut_platform_5_2_or_newer(self):
        executed = []
        stderr = io.StringIO()
        with (
            patch.object(cli, "_micronaut_platform_version", return_value="5.1.0"),
            patch.object(cli, "_ensure_micronaut_launch", side_effect=AssertionError("resolved too early")),
            redirect_stderr(stderr),
        ):
            exit_code = cli.run(
                ["create", "demo"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("requires Micronaut Platform 5.2.0 or newer", stderr.getvalue())
        self.assertEqual([], executed)

    def test_create_help_does_not_require_platform_metadata_or_mn(self):
        executed = []
        with patch.object(cli, "_micronaut_platform_version", return_value=None):
            exit_code = cli.run(
                ["create", "--help"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        self.assertEqual([], executed)

    def test_create_accepts_combined_help_and_diagnostic_short_flags(self):
        executed = []
        with patch.object(cli, "_micronaut_platform_version", return_value=None):
            exit_code = cli.run(
                ["create", "-hiv"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        self.assertEqual([], executed)

    def test_create_feature_output_filters_incompatible_rows(self):
        output = (
            "Available Features\n"
            "  jackson-databind   Jackson\n"
            "  data-jdbc          JDBC\n"
            "  hibernate-jpa      JPA\n"
            "  micrometer-new-relic Metrics\n"
        )
        executed = []
        with (
            patch.object(cli, "_micronaut_platform_version", return_value="5.2.0"),
            patch.object(cli, "_ensure_micronaut_launch", return_value=Path("/tmp/mn")),
            patch.object(cli, "_capture_subprocess", return_value=(0, output, "")) as capture,
            redirect_stdout(io.StringIO()) as stdout,
        ):
            exit_code = cli.run(
                ["create", "--list-features"],
                runner_with_env=lambda command_line, env=None: executed.append(command_line) or 0,
                platform_name="linux",
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        capture.assert_called_once_with([
            "/tmp/mn", "create-app", "--list-features",
            "--lang", "python", "--build", "pyronaut", "--test", "pytest",
        ])
        self.assertIn("data-jdbc", stdout.getvalue())
        self.assertIn("micrometer-new-relic", stdout.getvalue())
        self.assertNotIn("jackson-databind", stdout.getvalue())
        self.assertNotIn("hibernate-jpa", stdout.getvalue())
        self.assertEqual([], executed)

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

    def test_resolved_tool_executable_requires_complete_matching_cache(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package = root / "package"
            tools = package / "tools"
            descriptor = tools / "pyronaut-run" / "bin" / "pyronaut-tool-classpath.tsv"
            descriptor.parent.mkdir(parents=True)
            descriptor.write_text("bundled\tmicronaut-pyronaut-run-1.0.jar\n", encoding="utf-8")
            bundled_artifact = tools / "shared" / "lib" / "micronaut-pyronaut-run-1.0.jar"
            bundled_artifact.parent.mkdir(parents=True)
            bundled_artifact.write_bytes(b"run")
            (tools / "tool-runtime.properties").write_text("sdk.version=1.2.3\n", encoding="utf-8")
            (package / "version.properties").write_text("pyronaut=1.2.3\n", encoding="utf-8")

            cache = root / "home" / ".pyronaut" / "tools" / "1.2.3" / "current"
            executable = cache / "tools" / "pyronaut-run" / "bin" / "pyronaut-run"
            executable.parent.mkdir(parents=True)
            executable.write_text("#!/bin/sh\n", encoding="utf-8")
            artifact = cache / "tools" / "shared" / "lib" / "micronaut-pyronaut-run-1.0.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"run")
            digest = hashlib.sha256()
            digest.update(b"pyronaut-run\0")
            digest.update(descriptor.read_bytes())
            digest.update(b"\0")
            (tools / "tool-runtime.properties").write_text(
                f"sdk.version=1.2.3\ndescriptor.sha256={digest.hexdigest()}\n", encoding="utf-8"
            )
            (cache / "tool-runtime.properties").write_text(
                f"sdk.version=1.2.3\ndescriptor.sha256={digest.hexdigest()}\n", encoding="utf-8"
            )

            with patch.object(cli, "__file__", str(package / "cli.py")), patch.dict(
                os.environ, {"HOME": str(root / "home")}
            ):
                self.assertEqual(executable, cli._resolved_tool_executable("pyronaut-run"))
                artifact.write_bytes(b"corrupt")
                self.assertIsNone(cli._resolved_tool_executable("pyronaut-run"))
                artifact.unlink()
                corrupt = root / "corrupt.jar"
                corrupt.write_bytes(b"corrupt")
                artifact.symlink_to(corrupt)
                self.assertIsNone(cli._resolved_tool_executable("pyronaut-run"))
                artifact.unlink()
                self.assertIsNone(cli._resolved_tool_executable("pyronaut-run"))

    def test_setup_repositories_default_to_maven_central_and_snapshots_for_snapshot_sdk(self):
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3-SNAPSHOT"),
        ):
            self.assertEqual(
                ["mavenCentral", cli._SONATYPE_SNAPSHOTS_REPOSITORY],
                cli._setup_repositories(),
            )

        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3"),
        ):
            self.assertEqual(["mavenCentral"], cli._setup_repositories())

    def test_setup_repositories_can_be_replaced_in_user_settings(self):
        settings = {"maven": {"repositories": ["https://repo.example/releases", "companySnapshots"]}}
        with patch.object(cli, "_read_pyronaut_user_settings", return_value=settings):
            self.assertEqual(
                ["https://repo.example/releases", "companySnapshots"],
                cli._setup_repositories(),
            )

    def test_setup_reuses_recorded_custom_local_repository_until_explicitly_changed(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest = root / "setup.json"
            custom = root / "custom-m2"
            default = root / "home" / ".m2" / "repository"
            manifest.write_text(
                cli.json.dumps({"localRepository": str(custom.resolve())}), encoding="utf-8"
            )
            with (
                patch.object(cli, "_setup_is_required", return_value=True),
                patch.object(cli, "_setup_manifest_path", return_value=manifest),
                patch("pathlib.Path.home", return_value=root / "home"),
            ):
                self.assertEqual(custom.resolve(), cli._setup_local_repository(()))
                self.assertEqual(
                    default.resolve(),
                    cli._setup_local_repository(("--local-repository", str(default))),
                )

    def test_execution_requires_setup_but_help_and_version_do_not(self):
        stderr = io.StringIO()
        with (
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_read_valid_setup_manifest", side_effect=RuntimeError("stale")),
            redirect_stderr(stderr),
        ):
            self.assertEqual(cli.PRECONDITION_FAILED, cli.run(["install"], platform_name="linux"))
            self.assertEqual(cli.SUCCESS, cli.run(["install", "--help"], platform_name="linux"))
            self.assertEqual(cli.SUCCESS, cli.run(["dev", "--version"], platform_name="linux"))

        self.assertEqual(cli._SETUP_REQUIRED_MESSAGE + "\n", stderr.getvalue())

    def test_setup_provisions_all_components_and_publishes_validated_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            home = root / "home"
            package = root / "package"
            tools = package / "tools"
            tool_descriptor = tools / "pyronaut-run" / "bin" / "pyronaut-tool-classpath.tsv"
            tool_descriptor.parent.mkdir(parents=True)
            tool_descriptor.write_text(
                "maven\tio.micronaut.pyronaut\tmicronaut-pyronaut-run\t1.2.3\tjar\t\tmicronaut-pyronaut-run-1.2.3.jar\n",
                encoding="utf-8",
            )
            (tools / "tool-runtime.properties").write_text(
                "sdk.version=1.2.3\ndescriptor.sha256=tool-hash\n", encoding="utf-8"
            )
            tool_root = home / ".pyronaut" / "tools" / "1.2.3" / "current"
            tool_executable = tool_root / "tools" / "pyronaut-run" / "bin" / "pyronaut-run"
            tool_executable.parent.mkdir(parents=True)
            tool_executable.write_text("#!/bin/sh\n", encoding="utf-8")
            tool_executable.chmod(0o755)

            installer = root / "pyronaut-install"
            installer.write_text("#!/bin/sh\n", encoding="utf-8")
            installer.chmod(0o755)
            java_home = root / "graalvm"
            java = java_home / "bin" / "java"
            java.parent.mkdir(parents=True)
            java.write_text("#!/bin/sh\n", encoding="utf-8")
            java.chmod(0o755)

            images = {}
            resolved_by_image = {}
            for image_name in cli._SETUP_IMAGE_COMMANDS:
                executable = root / "images" / image_name / image_name
                executable.parent.mkdir(parents=True)
                executable.write_text("native", encoding="utf-8")
                executable.chmod(0o755)
                resolved_by_image[image_name] = self._write_native_compile_descriptor(
                    executable,
                    home,
                    [("com.example", f"{image_name}-compiler", "1.0", f"{image_name}-compiler-1.0.jar")],
                )[0].resolve()
                images[image_name] = executable

            manifest_path = home / ".pyronaut" / "setup" / "1.2.3" / "linux-amd64" / "setup.json"
            expectation = {
                "schemaVersion": 1,
                "sdkVersion": "1.2.3",
                "platform": "linux-amd64",
                "localRepository": str((home / ".m2" / "repository").resolve()),
                "repositories": ["mavenCentral", cli._SONATYPE_SNAPSHOTS_REPOSITORY],
                "sdkDescriptorSha256": "sdk-hash",
                "toolDescriptorSha256": "tool-hash",
                "nativeImages": {
                    "source": "https://example.invalid/",
                    "version": "1.2.3",
                    "releaseTag": None,
                },
                "graalpyVersion": "graalpy3.13-25.4.4",
            }
            commands = []
            provisioning_order = []
            graalpy_home = home / ".pyronaut" / "sdks" / "graalpy3.13-25.4.4"
            graalpy = cli._GraalPyInstallation(
                executable=graalpy_home / "bin" / "graalpy",
                home=graalpy_home,
                site_packages=str(graalpy_home / "lib" / "python3.13" / "site-packages"),
                version_line="GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)",
            )

            def provision_jdk(_project_dir, *, offline=False):
                provisioning_order.append("graalvm")
                self.assertFalse(offline)
                return str(java_home)

            def provision_graalpy(_runner, *, offline=False):
                provisioning_order.append("graalpy")
                self.assertFalse(offline)
                return graalpy

            def provision_image(name):
                provisioning_order.append(name)
                return images[name]

            def runner(command_line, env=None):
                commands.append((command_line, env))
                request_dir = Path(command_line[command_line.index("--native-classpaths-dir") + 1])
                output = request_dir / "resolved"
                output.mkdir()
                for image_name, artifact in resolved_by_image.items():
                    (output / f"{image_name}.txt").write_text(str(artifact) + "\n", encoding="utf-8")
                return 0

            metadata = cli._GraalVmMetadata("25.0.3", 25, "ee")
            stderr = io.StringIO()
            with (
                patch.object(cli, "__file__", str(package / "cli.py")),
                patch.object(cli, "_setup_manifest_path", return_value=manifest_path),
                patch.object(cli, "_setup_expectation", return_value=expectation),
                patch.object(cli, "_read_valid_setup_manifest", side_effect=RuntimeError("missing")),
                patch.object(cli, "_ensure_graalvm_java_home", side_effect=provision_jdk),
                patch.object(cli, "_required_graalpy", return_value=cli._GraalPySpec("graalpy3.13-25.4.4", "25.4.4.1.1", "graal-25.4.4")),
                patch.object(cli, "_ensure_graalpy", side_effect=provision_graalpy),
                patch.object(cli, "_ensure_native_image", side_effect=provision_image),
                patch.object(cli, "_bundled_executable", return_value=installer),
                patch.object(cli, "_seed_bundled_pyronaut_maven_repository") as seed_repository,
                patch.object(cli, "_resolved_tool_cache_root", return_value=tool_root),
                patch.object(cli, "_resolved_tools_cache_complete", return_value=True),
                patch.object(cli, "_read_graalvm_metadata", return_value=metadata),
                patch("pathlib.Path.home", return_value=home),
                redirect_stderr(stderr),
            ):
                exit_code = cli._run_setup(["--refresh"], runner)

            self.assertEqual(cli.SUCCESS, exit_code)
            self.assertIn("Locating GraalVM JDK (25+)...", stderr.getvalue())
            self.assertIn("Provisioning native launchers...", stderr.getvalue())
            self.assertIn("Resolving SDK dependencies...", stderr.getvalue())
            self.assertNotIn("\r", stderr.getvalue())
            self.assertNotIn("\x1b", stderr.getvalue())
            self.assertIn("Locating GraalPy graalpy3.13-25.4.4...", stderr.getvalue())
            self.assertEqual(["graalvm", "graalpy", *cli._SETUP_IMAGE_COMMANDS], provisioning_order)
            seed_repository.assert_called_once()
            self.assertEqual(1, len(commands))
            command_line, environment = commands[0]
            self.assertIn("--resolve-tools-only", command_line)
            self.assertEqual(2, command_line.count("--repository"))
            self.assertIn("--refresh", command_line)
            self.assertEqual("auto", command_line[command_line.index("--progress") + 1])
            self.assertEqual(str(java_home), environment["JAVA_HOME"])

            state = cli.json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(expectation["repositories"], state["repositories"])
            self.assertEqual("graalpy3.13-25.4.4", state["graalpyVersion"])
            self.assertEqual(
                {
                    "executable": str(graalpy.executable),
                    "home": str(graalpy_home),
                    "sitePackages": graalpy.site_packages,
                    "version": graalpy.version_line,
                },
                state["graalpy"],
            )
            self.assertEqual(str(tool_executable.resolve()), state["executables"]["pyronaut-run"])
            self.assertEqual(set(cli._SETUP_IMAGE_COMMANDS), set(state["images"]))
            for image_name in cli._SETUP_IMAGE_COMMANDS:
                self.assertEqual(
                    [str(resolved_by_image[image_name])],
                    state["images"][image_name]["classpath"],
                )

            resolved_by_image["pyronaut-dev"].unlink()
            with (
                patch.object(cli, "__file__", str(package / "cli.py")),
                patch.object(cli, "_setup_manifest_path", return_value=manifest_path),
                patch.object(cli, "_setup_expectation", return_value=expectation),
                patch.object(cli, "_resolved_tools_cache_complete", return_value=True),
                patch.object(cli, "_read_graalvm_metadata", return_value=metadata),
                patch("pathlib.Path.home", return_value=home),
            ):
                with self.assertRaisesRegex(RuntimeError, cli._SETUP_REQUIRED_MESSAGE):
                    cli._read_valid_setup_manifest([])

    def test_setup_is_idempotent_without_refresh(self):
        runner = Mock(side_effect=AssertionError("setup cache hit invoked installer"))
        with (
            patch.object(cli, "_setup_manifest_path", return_value=Path("/tmp/setup.json")),
            patch.object(cli, "_read_valid_setup_manifest", return_value={}),
            patch.object(cli, "_ensure_graalvm_java_home") as ensure_jdk,
        ):
            self.assertEqual(cli.SUCCESS, cli._run_setup([], runner))
        ensure_jdk.assert_not_called()

    def test_bundled_pyronaut_maven_repository_stages_jars_and_bom_in_default_repository(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package = root / "package"
            installer = package / "tools" / "pyronaut-install" / "bin" / "pyronaut-install"
            installer.parent.mkdir(parents=True)
            installer.write_text("#!/bin/sh\n", encoding="utf-8")
            shared = package / "tools" / "shared" / "lib"
            shared.mkdir(parents=True)
            poms = package / "tools" / "shared" / "maven-poms"
            poms.mkdir(parents=True)
            jar_name = "micronaut-pyronaut-logback-0.0.1-SNAPSHOT.jar"
            (shared / jar_name).write_bytes(b"logback")
            artifact_pom = '<project><artifactId>micronaut-pyronaut-logback</artifactId><dependency><artifactId>micronaut-http</artifactId></dependency></project>'
            (poms / "micronaut-pyronaut-logback-0.0.1-SNAPSHOT.pom").write_text(
                artifact_pom,
                encoding="utf-8",
            )

            with patch.object(cli, "__file__", str(package / "cli.py")), patch.dict(
                os.environ, {"HOME": str(root / "home")}
            ):
                cli._seed_bundled_pyronaut_maven_repository(
                    root / "project",
                    None,
                    str(installer),
                )

            repository = root / "home" / ".m2" / "repository" / "io" / "micronaut" / "pyronaut"
            artifact_dir = repository / "micronaut-pyronaut-logback" / "0.0.1-SNAPSHOT"
            self.assertEqual(b"logback", (artifact_dir / jar_name).read_bytes())
            self.assertEqual(
                artifact_pom,
                (artifact_dir / "micronaut-pyronaut-logback-0.0.1-SNAPSHOT.pom").read_text(encoding="utf-8"),
            )
            bom = repository / "micronaut-pyronaut-bom" / "0.0.1-SNAPSHOT" / "micronaut-pyronaut-bom-0.0.1-SNAPSHOT.pom"
            self.assertIn("micronaut-pyronaut-logback", bom.read_text(encoding="utf-8"))

    def test_bundled_executable_selects_windows_batch_launcher(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            package = Path(temp_dir) / "package"
            with (
                patch.object(cli, "__file__", str(package / "cli.py")),
                patch.object(cli.sys, "platform", "win32"),
            ):
                self.assertEqual(
                    package.resolve() / "tools" / "pyronaut-run" / "bin" / "pyronaut-run.bat",
                    cli._bundled_executable("pyronaut-run"),
                )

    def test_resolve_executable_preserves_override_and_installer_precedence(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            installer = Path(temp_dir) / "pyronaut-install"
            installer.write_text("#!/bin/sh\n", encoding="utf-8")
            with (
                patch.dict(os.environ, {"PYRONAUT_RUN_EXECUTABLE": "/custom/pyronaut-run"}),
                patch.object(cli, "_resolved_tool_executable", return_value=Path("/cache/pyronaut-run")),
            ):
                self.assertEqual("/custom/pyronaut-run", cli._resolve_executable("pyronaut-run"))
            with (
                patch.dict(os.environ, {"PYRONAUT_INSTALL_EXECUTABLE": ""}),
                patch.object(cli, "_bundled_executable", return_value=installer),
                patch.object(cli, "_resolved_tool_executable") as resolved_tool,
            ):
                self.assertEqual(str(installer), cli._resolve_executable("pyronaut-install"))
                resolved_tool.assert_not_called()

    def test_resolve_executable_does_not_implicitly_bootstrap_missing_cache(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            bundled = root / "bundled" / "bin" / "pyronaut-run"
            bundled.parent.mkdir(parents=True)
            bundled.write_text("#!/bin/sh\n", encoding="utf-8")
            (bundled.parent / "pyronaut-tool-classpath.tsv").write_text(
                "maven\tio.micronaut.pyronaut\tmicronaut-pyronaut-run\t1.0\tjar\t\tmicronaut-pyronaut-run-1.0.jar\n",
                encoding="utf-8",
            )
            with (
                patch.dict(os.environ, {"PYRONAUT_RUN_EXECUTABLE": ""}),
                patch.object(cli, "_resolved_tool_executable", return_value=None),
                patch.object(cli, "_bundled_executable", return_value=bundled),
                patch.object(cli.shutil, "which", return_value=None),
            ):
                self.assertIsNone(cli._resolve_executable("pyronaut-run"))

            (bundled.parent / "pyronaut-tool-classpath.tsv").unlink()
            with (
                patch.dict(os.environ, {"PYRONAUT_RUN_EXECUTABLE": ""}),
                patch.object(cli, "_resolved_tool_executable", return_value=None),
                patch.object(cli, "_bundled_executable", return_value=bundled),
            ):
                self.assertEqual(str(bundled), cli._resolve_executable("pyronaut-run"))

    def test_tui_non_interactive_test_mode_propagates_delegate_failure(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            if command_line[0] in {"/tmp/pyronaut-validate-config", "/tmp/pyronaut-processor"}:
                return 0
            return 3

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            expected_project_dir = project_dir.resolve()
            with redirect_stderr(io.StringIO()):
                exit_code = cli.run(
                    ["--tui", "--non-interactive", "--test", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(3, exit_code)
        self.assertEqual(3, len(executed))
        self._assert_test_delegate(executed[2], str(expected_project_dir), expect_project_jars=False)

    def test_tui_non_interactive_run_mode_delegates_run(self):
        executed = []

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
            self._write_manifests(project_dir)
            expected_project_dir = project_dir.resolve()
            with redirect_stderr(io.StringIO()):
                exit_code = cli.run(
                    ["--tui", "--non-interactive", "--project-dir", str(project_dir)],
                    runner_with_env=runner_with_env,
                    resolver=self._resolver(),
                    platform_name="linux",
                )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-processor", "--project-dir", str(expected_project_dir), "--pass", "main"],
            executed[0],
        )
        self._assert_run_delegate(executed[-1], str(expected_project_dir))

    def test_tui_leading_command_token_selects_test_mode(self):
        for argv_prefix in (["--tui", "test"], ["test", "--tui"]):
            with self.subTest(argv=argv_prefix):
                executed = []

                def runner_with_env(command_line, env):
                    executed.append(command_line)
                    return 0

                with tempfile.TemporaryDirectory() as temp_dir:
                    project_dir = Path(temp_dir) / "demo"
                    project_dir.mkdir(parents=True, exist_ok=True)
                    expected_project_dir = project_dir.resolve()
                    with redirect_stderr(io.StringIO()):
                        exit_code = cli.run(
                            [*argv_prefix, "--smoke", "--project-dir", str(project_dir)],
                            runner_with_env=runner_with_env,
                            resolver=self._resolver(),
                            platform_name="linux",
                        )

                self.assertEqual(0, exit_code)
                self.assertEqual(
                    ["/tmp/pyronaut-validate-config", "--project-dir", str(expected_project_dir), "--scenario", "test"],
                    executed[0],
                )
                self._assert_test_delegate(executed[-1], str(expected_project_dir), expect_project_jars=False)

    def test_tui_interactive_reports_missing_delegate_executable(self):
        base_resolver = self._resolver()

        def resolver(command_name):
            if command_name == "pyronaut-validate-config":
                return None
            return base_resolver(command_name)

        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "demo"
            project_dir.mkdir(parents=True, exist_ok=True)
            with redirect_stderr(stderr):
                exit_code = cli.run(
                    ["--tui", "--project-dir", str(project_dir)],
                    runner_with_env=lambda command_line, env: 0,
                    resolver=resolver,
                    platform_name="linux",
                )

        self.assertEqual(cli.PRECONDITION_FAILED, exit_code)
        self.assertIn("Missing delegated executable: pyronaut-validate-config", stderr.getvalue())

    def test_malformed_orchestrator_options_return_usage_error(self):
        cases = (
            (["run", "--project-dir", "/tmp/demo", "--debug-vm=maybe"], "Invalid value for --debug-vm"),
            (["run", "--project-dir", "/tmp/demo", "--local-repository"], "Missing value for --local-repository"),
            (["run", "--project-dir", "/tmp/demo", "--no-cache=bogus"], "Invalid value for --no-cache"),
        )
        for argv, message in cases:
            with self.subTest(argv=argv):
                stderr = io.StringIO()
                with redirect_stderr(stderr):
                    exit_code = cli.run(
                        argv,
                        runner=self._runner_ok(),
                        resolver=self._resolver(),
                        platform_name="linux",
                    )
                self.assertEqual(cli.USAGE_ERROR, exit_code)
                self.assertIn(message, stderr.getvalue())
                self.assertIn("Usage: pyronaut", stderr.getvalue())

    def test_orchestrator_flag_helpers_stop_at_separator(self):
        self.assertFalse(cli._extract_flag(["--", "--help"], "--help"))
        self.assertTrue(cli._extract_flag(["--help", "--"], "--help"))
        self.assertFalse(cli._extract_continuous(["--", "-t"]))
        self.assertEqual(["--", "-t"], cli._remove_continuous(["-t", "--", "-t"]))
        self.assertFalse(cli._extract_no_validate(["--", "--no-validate"]))
        self.assertEqual(["--", "--no-validate"], cli._remove_no_validate(["--no-validate", "--", "--no-validate"]))
        self.assertFalse(cli._extract_debug_vm(["--", "--debug-vm=maybe"]))
        self.assertEqual(["--", "--debug-vm"], cli._remove_debug_vm(["--debug-vm", "--", "--debug-vm"]))
        self.assertFalse(cli._extract_no_cache(["--", "--no-cache=bogus"]))
        self.assertEqual(["--", "--no-cache"], cli._strip_no_cache_flag(["--no-cache", "--", "--no-cache"]))
        self.assertIsNone(cli._extract_local_repository(["--", "--local-repository"]))
        self.assertEqual(
            ["--", "--local-repository", "/tmp/repo"],
            cli._strip_local_repository_args(["--local-repository", "/tmp/other", "--", "--local-repository", "/tmp/repo"]),
        )
        self.assertEqual(["--", "--offline"], cli._strip_orchestrator_only_args(["--offline", "--", "--offline"]))

    def test_run_forwards_application_arguments_after_separator(self):
        executed = []
        project_dir = Path("/tmp/demo")
        (project_dir / "__pyronaut__" / "classes").mkdir(parents=True, exist_ok=True)
        self._write_manifests(project_dir)
        stdout = io.StringIO()

        def runner_with_env(command_line, env):
            executed.append(command_line)
            return 0

        with redirect_stdout(stdout):
            exit_code = cli.run(
                ["run", "--project-dir", "/tmp/demo", "--", "--help", "-t", "--debug-vm"],
                runner_with_env=runner_with_env,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertNotIn("Usage:", stdout.getvalue())
        self._assert_run_delegate(executed[-1], "/tmp/demo", ["--", "--help", "-t", "--debug-vm"])
        self.assertNotIn(self._JDWP_FLAG, executed[-1])

    def test_run_direct_source_debug_vm_uses_jvm_launcher_with_jdwp(self):
        executed = []
        with tempfile.TemporaryDirectory() as temp_dir:
            native_dev = Path(temp_dir) / "pyronaut-dev"
            native_dev.write_text("", encoding="utf-8")
            native_dev.chmod(0o755)
            source = Path(temp_dir) / "controller.py"
            source.write_text("print('ok')\n", encoding="utf-8")

            def runner(command_line, env=None):
                executed.append((command_line, env))
                return 0

            with patch.object(cli, "_check_port_available", return_value=(True, None)):
                with patch.object(cli, "_bundled_native_executable", side_effect=lambda command_name: native_dev if command_name == "pyronaut-dev" else None):
                    exit_code = cli.run(
                        ["run", "--debug-vm", str(source)],
                        runner_with_env=runner,
                        resolver=self._resolver(),
                        platform_name="linux",
                        java_home_provider=lambda: "/tmp/java-home",
                    )

        self.assertEqual(0, exit_code)
        self.assertEqual(1, len(executed))
        command_line, env = executed[0]
        self.assertEqual(str(self._fake_dev_delegate_root / "bin" / "pyronaut-dev"), command_line[0])
        self.assertNotIn("--debug-vm", command_line)
        self.assertEqual(str(source), command_line[-1])
        self.assertIn(self._JDWP_FLAG, env["JAVA_TOOL_OPTIONS"].split())

    def test_owned_test_resources_session_stop_is_not_repeated(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = (Path(temp_dir) / "demo").resolve()
            (project_dir / "__pyronaut__").mkdir(parents=True, exist_ok=True)
            executed = []

            def runner(command_line, env=None):
                executed.append(command_line)
                return 0

            session = cli._OwnedTestResourcesSession(project_dir=project_dir, owner_command="pyronaut test")
            session._persist_session(started_at=time.time())  # noqa: SLF001 - simulate a started owned server
            session._started = True  # noqa: SLF001
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                session.stop_if_owned(runner=runner, resolver=self._resolver())
                session.stop_if_owned(runner=runner, resolver=self._resolver())

        self.assertEqual(1, len(executed))
        self._assert_test_resources_stop(executed[0], str(project_dir))
        self.assertNotIn("ownership mismatch", stderr.getvalue())

    def test_owned_test_resources_session_shared_stop_skip_is_reported_once(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = (Path(temp_dir) / "demo").resolve()
            project_dir.mkdir(parents=True, exist_ok=True)
            session = cli._OwnedTestResourcesSession(project_dir=project_dir, owner_command="pyronaut test")
            session._shared_server = True  # noqa: SLF001
            session._started = True  # noqa: SLF001
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                session.stop_if_owned(runner=lambda command_line, env=None: 0, resolver=self._resolver())
                session.stop_if_owned(runner=lambda command_line, env=None: 0, resolver=self._resolver())

        self.assertEqual(1, stderr.getvalue().count("stop skipped (shared server mode)"))

    def test_test_resources_server_start_forwards_local_repository_to_install(self):
        executed = []

        def runner(command_line):
            executed.append(command_line)
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            repository = str(Path(temp_dir) / "repo")
            exit_code = cli.run(
                ["test-resources-server", "start", "--project-dir", "/tmp/demo", "--local-repository", repository],
                runner=runner,
                resolver=self._resolver(),
                platform_name="linux",
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["/tmp/pyronaut-install", "--project-dir", "/tmp/demo", "--local-repository", repository],
            executed[0],
        )

    def test_external_test_install_uses_java_home_provider_once(self):
        delegated = []
        java_home_provider = lambda: "/tmp/java-home"

        def delegate(command, *args, **kwargs):
            delegated.append((command, kwargs.get("java_home_provider")))
            return 0

        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = Path(temp_dir) / "external"
            project_dir.mkdir()
            (project_dir / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
            with patch.object(cli, "_delegate", side_effect=delegate):
                with patch.object(cli, "_run_lifecycle_validation", return_value=0):
                    exit_code = cli.run(
                        ["test", "--project-dir", str(project_dir)],
                        runner_with_env=lambda command_line, env=None: 0,
                        resolver=self._resolver(),
                        platform_name="linux",
                        java_home_provider=java_home_provider,
                    )

        self.assertEqual(0, exit_code)
        self.assertEqual(("install", java_home_provider), delegated[0])
        self.assertEqual(1, sum(1 for command, _ in delegated if command == "install"))
        self.assertEqual("test", delegated[-1][0])

    @staticmethod
    def _resolver():
        def resolve(command_name):
            if command_name == "pyronaut-run-python":
                return str(OrchestratorTest._fake_python_delegate_root / "bin" / command_name)
            if command_name == "pyronaut-dev":
                return str(OrchestratorTest._fake_dev_delegate_root / "bin" / command_name)
            if command_name in {"pyronaut-run", "pyronaut-test"}:
                return str(OrchestratorTest._fake_dev_delegate_root / "bin" / command_name)
            return f"/tmp/{command_name}"

        return resolve

    @staticmethod
    def _runner_ok():
        def runner(_command_line):
            return 0

        return runner


if __name__ == "__main__":
    unittest.main()
