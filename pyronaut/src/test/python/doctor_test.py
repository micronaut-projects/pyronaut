import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import time
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

_CLI_MODULE_PATH = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2" / "cli.py"
_CLI_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.cli", _CLI_MODULE_PATH)
if _CLI_SPEC is None or _CLI_SPEC.loader is None:
    raise RuntimeError("Failed loading pyronaut_cli_v2.cli for tests")
if str(_CLI_MODULE_PATH.parent.parent) not in sys.path:
    sys.path.insert(0, str(_CLI_MODULE_PATH.parent.parent))

sys.modules.pop("pyronaut_cli_v2", None)
pkg = types.ModuleType("pyronaut_cli_v2")
pkg.__path__ = [str(_CLI_MODULE_PATH.parent)]
sys.modules["pyronaut_cli_v2"] = pkg

cli = importlib.util.module_from_spec(_CLI_SPEC)
_CLI_SPEC.loader.exec_module(cli)
doctor = cli._doctor
progress = sys.modules["pyronaut_cli_v2.progress"]

_PROXY_ENV = ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy")


def _result(check_id, status, detail="detail", fix=None):
    return doctor.CheckResult(check_id, check_id.title(), status, detail, fix)


def _write_executable(path: Path, content: str = "#!/bin/sh\nexit 0\n") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)
    return path


class DoctorModelTest(unittest.TestCase):
    def test_check_result_rejects_unknown_status(self):
        with self.assertRaises(ValueError):
            doctor.CheckResult("x", "X", "maybe", "detail")

    def test_run_checks_turns_exceptions_into_failed_rows_and_keeps_going(self):
        started = []
        landed = []

        def boom():
            raise RuntimeError("no such file")

        results = doctor.run_checks(
            [
                ("first", "First", lambda: _result("first", doctor.PASS)),
                ("broken", "Broken", boom),
                ("last", "Last", lambda: _result("last", doctor.WARN)),
            ],
            on_start=started.append,
            on_result=lambda result: landed.append(result.id),
        )

        self.assertEqual(["First", "Broken", "Last"], started)
        self.assertEqual(["first", "broken", "last"], landed)
        self.assertEqual([doctor.PASS, doctor.FAIL, doctor.WARN], [result.status for result in results])
        self.assertIn("check crashed: RuntimeError: no such file", results[1].detail)
        self.assertIsNotNone(results[1].fix)

    def test_summary_overall_status_and_summary_line(self):
        results = [_result("a", doctor.PASS), _result("b", doctor.WARN), _result("c", doctor.WARN)]
        self.assertEqual({"pass": 1, "warn": 2, "fail": 0}, doctor.summarize(results))
        self.assertEqual(doctor.WARN, doctor.overall_status(results))
        self.assertEqual("Pyronaut doctor: 1 passed, 2 warnings", doctor.summary_line(results))

        results.append(_result("d", doctor.FAIL))
        self.assertEqual(doctor.FAIL, doctor.overall_status(results))
        self.assertEqual("Pyronaut doctor: 1 passed, 2 warnings, 1 failed", doctor.summary_line(results))

        self.assertEqual(doctor.PASS, doctor.overall_status([_result("a", doctor.PASS)]))
        self.assertEqual("Pyronaut doctor: 1 passed", doctor.summary_line([_result("a", doctor.PASS)]))

    def test_to_json_reports_every_row_with_fix_and_data(self):
        results = [
            doctor.CheckResult("python", "Python", doctor.PASS, "3.12.1", data={"version": "3.12.1"}),
            doctor.CheckResult("docker", "Docker", doctor.WARN, "not reachable", "Start Docker"),
        ]
        report = json.loads(doctor.to_json(results, version="1.2.3", platform="linux-amd64", project_dir=None))
        self.assertEqual("1.2.3", report["pyronaut"])
        self.assertEqual("linux-amd64", report["platform"])
        self.assertIsNone(report["project"])
        self.assertEqual("warn", report["status"])
        self.assertEqual({"pass": 1, "warn": 1, "fail": 0}, report["summary"])
        self.assertEqual(
            [
                {"id": "python", "title": "Python", "status": "pass", "detail": "3.12.1", "fix": None, "data": {"version": "3.12.1"}},
                {"id": "docker", "title": "Docker", "status": "warn", "detail": "not reachable", "fix": "Start Docker", "data": {}},
            ],
            report["checks"],
        )

    def test_render_prints_status_rows_and_fix_hints_through_console(self):
        console = progress.Console()
        console.configure("off")
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            doctor.render(
                console,
                [
                    _result("python", doctor.PASS, "3.12.1"),
                    _result("docker", doctor.WARN, "not reachable", "Start Docker"),
                    _result("graalvm", doctor.FAIL, "missing", "Run pyronaut setup"),
                ],
            )
        self.assertEqual(
            [
                "Python: 3.12.1",
                "WARNING: Docker: not reachable",
                "  fix: Start Docker",
                "ERROR: Graalvm: missing",
                "  fix: Run pyronaut setup",
            ],
            stderr.getvalue().splitlines(),
        )


class DoctorCommandTest(unittest.TestCase):
    def setUp(self):
        cli._progress_console().configure("off")

    def test_help_and_unknown_option(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(cli.SUCCESS, cli.run(["doctor", "--help"]))
        self.assertIn("Usage: pyronaut doctor [--project-dir <dir>] [--json]", stdout.getvalue())

        stderr = io.StringIO()
        with redirect_stderr(stderr):
            self.assertEqual(cli.USAGE_ERROR, cli.run(["doctor", "--bogus"]))
            self.assertEqual(cli.USAGE_ERROR, cli.run(["doctor", "--progress", "loud"]))
        self.assertIn("Unknown pyronaut doctor option: --bogus", stderr.getvalue())
        self.assertIn("Invalid value for --progress", stderr.getvalue())
        self.assertIn("<setup|doctor|install", stderr.getvalue())

    def test_doctor_does_not_require_setup_state(self):
        fake_checks = [("python", "Python", lambda: _result("python", doctor.PASS))]
        with (
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_read_valid_setup_manifest", side_effect=RuntimeError("stale")) as validator,
            patch.object(cli, "_doctor_checks", return_value=fake_checks),
            redirect_stdout(io.StringIO()),
            redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(cli.SUCCESS, cli.run(["doctor"]))
        validator.assert_not_called()

    def test_json_output_and_exit_code_follow_the_worst_row(self):
        failing = [
            ("python", "Python", lambda: _result("python", doctor.PASS, "3.12")),
            ("graalvm", "GraalVM JDK", lambda: _result("graalvm", doctor.FAIL, "missing", "Run pyronaut setup")),
        ]
        stdout = io.StringIO()
        stderr = io.StringIO()
        with (
            patch.object(cli, "_doctor_checks", return_value=failing),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3"),
            patch.object(cli, "_doctor_platform", return_value="linux-amd64"),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            self.assertEqual(cli.PRECONDITION_FAILED, cli.run(["doctor", "--json"]))
        report = json.loads(stdout.getvalue())
        self.assertEqual("fail", report["status"])
        self.assertEqual("1.2.3", report["pyronaut"])
        self.assertEqual(["python", "graalvm"], [check["id"] for check in report["checks"]])
        self.assertEqual("Run pyronaut setup", report["checks"][1]["fix"])
        self.assertEqual("", stderr.getvalue(), "JSON mode must keep stderr quiet")

        warning_only = [("docker", "Docker", lambda: _result("docker", doctor.WARN, "down", "Start Docker"))]
        with (
            patch.object(cli, "_doctor_checks", return_value=warning_only),
            redirect_stdout(io.StringIO()),
            redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(cli.SUCCESS, cli.run(["doctor", "--json"]))

    def test_human_output_prints_rows_on_stderr_and_summary_on_stdout(self):
        checks = [
            ("python", "Python", lambda: _result("python", doctor.PASS, "3.12")),
            ("docker", "Docker", lambda: _result("docker", doctor.WARN, "down", "Start Docker")),
            ("graalvm", "GraalVM JDK", lambda: _result("graalvm", doctor.FAIL, "missing", "Run pyronaut setup")),
        ]
        stdout = io.StringIO()
        stderr = io.StringIO()
        with (
            patch.object(cli, "_doctor_checks", return_value=checks),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            self.assertEqual(cli.PRECONDITION_FAILED, cli.run(["doctor", "--progress", "off"]))
        self.assertEqual("Pyronaut doctor: 1 passed, 1 warning, 1 failed\n", stdout.getvalue())
        lines = stderr.getvalue().splitlines()
        self.assertIn("Python: 3.12", lines)
        self.assertIn("WARNING: Docker: down", lines)
        self.assertIn("  fix: Start Docker", lines)
        self.assertIn("ERROR: Graalvm: missing", lines)
        self.assertTrue(any("project checks skipped" in line for line in lines))

    def test_project_checks_are_selected_by_pyproject_or_explicit_project_dir(self):
        captured = []

        def fake_checks(args, project_dir, **_):
            captured.append(project_dir)
            return [("python", "Python", lambda: _result("python", doctor.PASS))]

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "empty").mkdir()
            project = root / "app"
            project.mkdir()
            (project / "pyproject.toml").write_text("[project]\nname = 'app'\n", encoding="utf-8")
            with (
                patch.object(cli, "_doctor_checks", side_effect=fake_checks),
                redirect_stdout(io.StringIO()),
                redirect_stderr(io.StringIO()),
            ):
                original = os.getcwd()
                os.chdir(root / "empty")
                try:
                    cli.run(["doctor"])
                finally:
                    os.chdir(original)
                os.chdir(project)
                try:
                    cli.run(["doctor"])
                finally:
                    os.chdir(original)
                cli.run(["doctor", "--project", str(root / "empty")])
        self.assertIsNone(captured[0])
        self.assertEqual(project.resolve(), captured[1])
        self.assertEqual((root / "empty").resolve(), captured[2])

    def test_check_order_includes_project_checks_only_inside_project(self):
        self.assertEqual(
            ["python", "pyronaut", "graalvm", "graalpy", "interpreter", "launchers", "proxy", "docker"],
            [check_id for check_id, _, _ in cli._doctor_checks([], None)],
        )
        self.assertEqual(
            [
                "python", "pyronaut", "graalvm", "graalpy", "interpreter", "launchers",
                "pyproject", "install", "state", "threading", "packages", "compat", "pytest",
                "proxy", "docker",
            ],
            [check_id for check_id, _, _ in cli._doctor_checks([], Path("/tmp/app"), offline=True)],
        )

    def test_offline_flag_is_accepted_and_forwarded(self):
        captured = {}

        def fake_checks(args, project_dir, *, offline=False):
            captured["offline"] = offline
            return [("python", "Python", lambda: _result("python", doctor.PASS))]

        with patch.object(cli, "_doctor_checks", side_effect=fake_checks), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(cli.SUCCESS, cli.run(["doctor", "--offline"]))
        self.assertTrue(captured["offline"])


class DoctorChecksTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.addCleanup(self._temp.cleanup)
        self._home_patch = patch("pathlib.Path.home", return_value=self.home)
        self._home_patch.start()
        self.addCleanup(self._home_patch.stop)
        self._env_patch = patch.dict(os.environ, {"HOME": str(self.home)}, clear=False)
        self._env_patch.start()
        self.addCleanup(self._env_patch.stop)
        for name in ("JAVA_HOME", "PYENV_VERSION", "PYENV_ROOT", "SDKMAN_DIR", "DOCKER_HOST", *_PROXY_ENV):
            os.environ.pop(name, None)

    # -- python --------------------------------------------------------------

    def test_python_check_requires_3_10(self):
        with patch.object(sys, "version_info", types.SimpleNamespace(major=3, minor=9, micro=6)):
            result = cli._doctor_check_python()
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("3.9.6", result.detail)
        self.assertIn("older than the required 3.10", result.detail)
        self.assertIn("Install Python 3.10 or newer", result.fix)

        with patch.object(sys, "version_info", types.SimpleNamespace(major=3, minor=10, micro=0)):
            result = cli._doctor_check_python()
        self.assertEqual(doctor.PASS, result.status)
        self.assertEqual("3.10.0", result.data["version"])

    # -- setup state ---------------------------------------------------------

    def test_setup_state_check_passes_for_source_checkout(self):
        with (
            patch.object(cli, "_setup_is_required", return_value=False),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3.dev0"),
        ):
            result = cli._doctor_check_setup_state([])
        self.assertEqual(doctor.PASS, result.status)
        self.assertIn("source checkout", result.detail)

    def test_setup_state_check_suggests_setup_or_refresh(self):
        manifest = self.home / ".pyronaut" / "setup" / "1.2.3" / "linux-amd64" / "setup.json"
        with (
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3"),
            patch.object(cli, "_setup_manifest_path", return_value=manifest),
            patch.object(cli, "_read_valid_setup_manifest", side_effect=RuntimeError(cli._SETUP_REQUIRED_MESSAGE)),
        ):
            missing = cli._doctor_check_setup_state([])
            manifest.parent.mkdir(parents=True)
            manifest.write_text(json.dumps({"sdkVersion": "1.2.2"}), encoding="utf-8")
            stale = cli._doctor_check_setup_state([])

        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("no setup state at", missing.detail)
        self.assertEqual("Run pyronaut setup", missing.fix)

        self.assertEqual(doctor.FAIL, stale.status)
        self.assertIn("is stale (recorded for 1.2.2)", stale.detail)
        self.assertEqual("Run pyronaut setup --refresh", stale.fix)
        self.assertEqual("1.2.2", stale.data["recordedVersion"])

    def test_setup_state_check_reports_settings_errors_and_valid_state(self):
        manifest = self.home / "setup.json"
        with (
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3"),
            patch.object(cli, "_setup_manifest_path", return_value=manifest),
            patch.object(cli, "_read_valid_setup_manifest", side_effect=RuntimeError("[native-images].base-url must be a non-empty URL")),
        ):
            broken = cli._doctor_check_setup_state([])
        self.assertEqual(doctor.FAIL, broken.status)
        self.assertIn("[native-images].base-url", broken.detail)
        self.assertIn("settings.toml", broken.fix)

        with (
            patch.object(cli, "_setup_is_required", return_value=True),
            patch.object(cli, "_installed_pyronaut_version", return_value="1.2.3"),
            patch.object(cli, "_setup_manifest_path", return_value=manifest),
            patch.object(cli, "_read_valid_setup_manifest", return_value={"javaHome": "/opt/graalvm", "toolRuntime": "/tools"}),
        ):
            valid = cli._doctor_check_setup_state([])
        self.assertEqual(doctor.PASS, valid.status)
        self.assertIn("setup state valid", valid.detail)
        self.assertEqual("/opt/graalvm", valid.data["javaHome"])

    # -- graalvm -------------------------------------------------------------

    def test_graalvm_check_fails_when_nothing_is_found_and_mentions_ignored_java_home(self):
        os.environ["JAVA_HOME"] = "/opt/temurin-21"
        with (
            patch.object(cli, "_ensure_graalvm_java_home", return_value=None) as discover,
            patch.object(cli, "_matches_requested_graalvm_home", return_value=False),
        ):
            result = cli._doctor_check_graalvm(None)
        discover.assert_called_once_with(None, offline=True)
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("no GraalVM JDK 25+ found", result.detail)
        self.assertIn("JAVA_HOME=/opt/temurin-21 is not a compatible GraalVM", result.detail)
        self.assertIn("Run pyronaut setup to download GraalVM JDK 25", result.fix)
        self.assertEqual("/opt/temurin-21", result.data["ignoredJavaHome"])

    def test_graalvm_check_reports_distribution_and_discovery_source(self):
        sdkman_home = self.home / ".sdkman" / "candidates" / "java" / "25.0.2-graalce"
        _write_executable(sdkman_home / "bin" / "java")
        metadata = cli._GraalVmMetadata("25.0.2", 25, "ce")
        with (
            patch.object(cli, "_ensure_graalvm_java_home", return_value=str(sdkman_home)),
            patch.object(cli, "_read_graalvm_metadata", return_value=metadata),
        ):
            result = cli._doctor_check_graalvm(None)
        self.assertEqual(doctor.PASS, result.status)
        self.assertEqual(f"GraalVM Community 25.0.2 from SDKMAN at {sdkman_home}", result.detail)
        self.assertEqual({"source": "SDKMAN", "distribution": "ce", "javaVersion": 25}, {key: result.data[key] for key in ("source", "distribution", "javaVersion")})

        cached_home = self.home / ".pyronaut" / "sdks" / "graalvm-jdk-25" / "Contents" / "Home"
        os.environ["JAVA_HOME"] = "/opt/temurin-21"
        with (
            patch.object(cli, "_ensure_graalvm_java_home", return_value=str(cached_home)),
            patch.object(cli, "_read_graalvm_metadata", return_value=cli._GraalVmMetadata("25.0.1", 25, "ee")),
            patch.object(cli, "_matches_requested_graalvm_home", return_value=False),
        ):
            warned = cli._doctor_check_graalvm(None)
        self.assertEqual(doctor.WARN, warned.status)
        self.assertIn("Oracle GraalVM 25.0.1 from ~/.pyronaut/sdks", warned.detail)
        self.assertIn("JAVA_HOME=/opt/temurin-21 is ignored", warned.detail)
        self.assertEqual(f"Unset JAVA_HOME or point it at {cached_home}", warned.fix)

    def test_graalvm_check_uses_java_home_when_it_is_compatible(self):
        java_home = self.root / "graalvm"
        _write_executable(java_home / "bin" / "java")
        os.environ["JAVA_HOME"] = str(java_home)
        with (
            patch.object(cli, "_ensure_graalvm_java_home", return_value=str(java_home)),
            patch.object(cli, "_read_graalvm_metadata", return_value=cli._GraalVmMetadata("25.0.2", 25, "ee")),
            patch.object(cli, "_matches_requested_graalvm_home", return_value=True),
        ):
            result = cli._doctor_check_graalvm(None)
        self.assertEqual(doctor.PASS, result.status)
        self.assertEqual("JAVA_HOME", result.data["source"])

    def test_graalvm_check_reports_invalid_toolchain_table(self):
        project = self.root / "app"
        project.mkdir()
        (project / "pyproject.toml").write_text(
            "[tool.pyronaut.toolchain]\njava-version = 'twenty-five'\n", encoding="utf-8"
        )
        result = cli._doctor_check_graalvm(project)
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("Invalid toolchain java version", result.detail)

    # -- graalpy -------------------------------------------------------------

    def _install_pyenv_graalpy(self, name="graalpy3.13-25.4.4", root=None):
        pyenv_root = root or (self.home / ".pyenv")
        return _write_executable(pyenv_root / "versions" / name / "bin" / "graalpy")

    def test_graalpy_check_recognises_the_global_pyenv_version_file(self):
        # Issue #62: the global selection lives in ~/.pyenv/version when
        # PYENV_VERSION is not exported.
        executable = self._install_pyenv_graalpy()
        (self.home / ".pyenv" / "version").write_text("graalpy3.13-25.4.4\n", encoding="utf-8")
        probes = []

        def capture(command_line, timeout=None):
            probes.append(command_line)
            return 0, "GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)\n", ""

        with (
            patch.object(cli, "_doctor_capture", side_effect=capture),
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
            patch.object(shutil, "which", return_value=None),
        ):
            result = cli._doctor_check_graalpy(None)
        self.assertEqual(doctor.PASS, result.status, result.detail)
        self.assertEqual([[str(executable), "--version"]], probes)
        self.assertIn(f"selected by {self.home / '.pyenv' / 'version'}", result.detail)
        self.assertIn("GraalPy 3.13.14", result.detail)
        self.assertEqual(str(executable), result.data["executable"])

    def test_graalpy_check_prefers_pyenv_version_env_and_custom_root(self):
        pyenv_root = self.root / "pyenv-root"
        executable = self._install_pyenv_graalpy("graalpy3.12-25.1.3", root=pyenv_root)
        (pyenv_root / "version").write_text("3.12.4\n", encoding="utf-8")
        os.environ["PYENV_ROOT"] = str(pyenv_root)
        os.environ["PYENV_VERSION"] = "graalpy3.12-25.1.3"
        with (
            patch.object(cli, "_doctor_capture", return_value=(0, "GraalPy 3.12.8 (Oracle GraalVM Native 25.1.3)\n", "")),
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
            patch.object(shutil, "which", return_value=None),
        ):
            result = cli._doctor_check_graalpy(None)
        self.assertEqual(doctor.WARN, result.status)
        self.assertIn("selected by PYENV_VERSION", result.detail)
        self.assertIn("this Pyronaut bundles GraalPy 25.4.4.1.1", result.detail)
        self.assertIn("pyenv install graalpy3.13-25.4.4", result.fix)
        self.assertEqual(str(executable), result.data["executable"])

    def test_graalpy_check_reports_selected_but_uninstalled_pyenv_version(self):
        (self.home / ".pyenv").mkdir()
        (self.home / ".pyenv" / "version").write_text("graalpy3.13-25.4.4\n", encoding="utf-8")
        with (
            patch.object(cli, "_read_version_properties", return_value={}),
            patch.object(shutil, "which", return_value=None),
        ):
            result = cli._doctor_check_graalpy(None)
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("(not installed)", result.detail)
        self.assertIn("pyenv install", result.fix)

    def test_graalpy_check_falls_back_to_path_and_warns_when_absent(self):
        on_path = _write_executable(self.root / "bin" / "graalpy")
        with (
            patch.object(cli, "_doctor_capture", return_value=(0, "GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)\n", "")),
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
            patch.object(shutil, "which", return_value=str(on_path)),
        ):
            found = cli._doctor_check_graalpy(None)
        self.assertEqual(doctor.PASS, found.status)
        self.assertIn("via PATH", found.detail)

        with (
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
            patch.object(shutil, "which", return_value=None),
        ):
            absent = cli._doctor_check_graalpy(None)
            project = self.root / "app"
            project.mkdir()
            absent_in_project = cli._doctor_check_graalpy(project)
        self.assertEqual(doctor.WARN, absent.status)
        self.assertIn("no GraalPy found on PATH, PYENV_VERSION or ~/.pyenv/version", absent.detail)
        self.assertIn("pyenv install graalpy3.13-25.4.4 && pyenv global graalpy3.13-25.4.4", absent.fix)
        self.assertEqual(doctor.FAIL, absent_in_project.status)
        self.assertIn("no project .venv and", absent_in_project.detail)
        self.assertIn("pyronaut install", absent_in_project.fix)
        self.assertIn("Run pyronaut setup to provision GraalPy graalpy3.13-25.4.4", absent.fix)

    def test_graalpy_check_inspects_the_project_virtualenv(self):
        project = self.root / "app"
        venv_python = _write_executable(project / ".venv" / "bin" / "python")
        (project / ".venv" / "pyvenv.cfg").write_text("home = /opt/graalpy/bin\nversion = 3.13.14\n", encoding="utf-8")
        with (
            patch.object(cli, "_doctor_capture", return_value=(0, "GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)\n", "")),
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
        ):
            result = cli._doctor_check_graalpy(project)
        self.assertEqual(doctor.PASS, result.status, result.detail)
        self.assertEqual(f"project .venv uses GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1) ({venv_python})", result.detail)

        (project / ".venv" / "pyvenv.cfg").write_text("home = /usr/bin\nversion = 3.12.4\n", encoding="utf-8")
        with patch.object(cli, "_read_version_properties", return_value={}):
            cpython = cli._doctor_check_graalpy(project)
        self.assertEqual(doctor.FAIL, cpython.status)
        self.assertIn("was not created with GraalPy", cpython.detail)
        self.assertIn(f"rm -rf {project / '.venv'} && pyronaut install", cpython.fix)

    def test_graalpy_check_warns_when_project_has_no_virtualenv_but_graalpy_exists(self):
        project = self.root / "app"
        project.mkdir()
        self._install_pyenv_graalpy()
        (self.home / ".pyenv" / "version").write_text("graalpy3.13-25.4.4\n", encoding="utf-8")
        with (
            patch.object(cli, "_doctor_capture", return_value=(0, "GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)\n", "")),
            patch.object(cli, "_read_version_properties", return_value={"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}),
        ):
            result = cli._doctor_check_graalpy(project)
        self.assertEqual(doctor.WARN, result.status)
        self.assertTrue(result.detail.startswith("no project .venv; GraalPy 3.13.14"))
        self.assertIn("Run pyronaut install to create .venv with GraalPy", result.fix)

    # -- native launchers ----------------------------------------------------

    def _cache_launcher(self, name, version="1.2.3", *, metadata=None):
        executable = _write_executable(self.home / ".pyronaut" / "bin" / version / "linux-amd64" / name)
        state = metadata or {
            "source": "https://github.com/micronaut-projects/pyronaut/releases/",
            "version": version,
            "release-tag": None,
            "platform": "linux-amd64",
            "bundle-format": cli._NATIVE_IMAGE_BUNDLE_FORMAT,
        }
        executable.with_name(name + ".json").write_text(json.dumps(state), encoding="utf-8")
        return executable

    def test_native_launcher_check_uses_the_cached_image_validation(self):
        configuration = patch.object(
            cli,
            "_native_image_configuration",
            return_value=("https://github.com/micronaut-projects/pyronaut/releases/", "1.2.3", None),
        )
        platform_patch = patch.object(cli, "_native_image_platform", return_value=("linux", "amd64"))
        with configuration, platform_patch, patch.object(cli, "_setup_is_required", return_value=True):
            for name in cli._SETUP_IMAGE_COMMANDS:
                self._cache_launcher(name)
            ready = cli._doctor_check_native_launchers()

            (self.home / ".pyronaut" / "bin" / "1.2.3" / "linux-amd64" / "pyronaut-run").unlink()
            missing = cli._doctor_check_native_launchers()

            self._cache_launcher("pyronaut-run", metadata={"version": "0.9.0"})
            stale = cli._doctor_check_native_launchers()

        self.assertEqual(doctor.PASS, ready.status, ready.detail)
        self.assertIn("pyronaut-dev, pyronaut-run, pyronaut-run-python cached in", ready.detail)
        self.assertEqual("linux-amd64", ready.data["platform"])

        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("1 of 3 launchers missing", missing.detail)
        self.assertIn("pyronaut-run", missing.detail)
        self.assertEqual("Run pyronaut setup", missing.fix)
        self.assertIsNone(missing.data["images"]["pyronaut-run"])

        self.assertEqual(doctor.FAIL, stale.status)
        self.assertIn("1 of 3 launchers stale", stale.detail)
        self.assertEqual("Run pyronaut setup --refresh", stale.fix)

    def test_native_launcher_check_only_warns_for_source_checkouts_and_reports_bad_settings(self):
        configuration = patch.object(
            cli,
            "_native_image_configuration",
            return_value=("https://github.com/micronaut-projects/pyronaut/releases/", "1.2.3", None),
        )
        with configuration, patch.object(cli, "_native_image_platform", return_value=("linux", "amd64")), patch.object(cli, "_setup_is_required", return_value=False):
            result = cli._doctor_check_native_launchers()
        self.assertEqual(doctor.WARN, result.status)
        self.assertIn("3 of 3 launchers missing", result.detail)

        with patch.object(cli, "_native_image_configuration", side_effect=RuntimeError("[native-images].version must be a non-empty string")):
            broken = cli._doctor_check_native_launchers()
        self.assertEqual(doctor.FAIL, broken.status)
        self.assertIn("[native-images].version", broken.detail)
        self.assertIn("settings.toml", broken.fix)

        with patch.object(cli, "_native_image_configuration", return_value=("x", "1.2.3", None)), patch.object(
            cli, "_native_image_platform", side_effect=RuntimeError("Native Pyronaut images are not available for platform 'win32'. Supported platforms are Linux and macOS.")
        ):
            unsupported = cli._doctor_check_native_launchers()
        self.assertEqual(doctor.FAIL, unsupported.status)
        self.assertIn("Use macOS or Linux", unsupported.fix)

    # -- project checks ------------------------------------------------------

    def test_pyproject_check_parses_and_validates_the_pyronaut_table(self):
        project = self.root / "app"
        project.mkdir()
        missing = cli._doctor_check_pyproject(project)
        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("no pyproject.toml in", missing.detail)

        pyproject = project / "pyproject.toml"
        pyproject.write_text("[project]\nname = 'demo'\nversion = '2.0.0'\n", encoding="utf-8")
        ok = cli._doctor_check_pyproject(project)
        self.assertEqual(doctor.PASS, ok.status, ok.detail)
        self.assertIn("demo 2.0.0 parses", ok.detail)
        self.assertIn("no [tool.pyronaut] table", ok.detail)
        self.assertFalse(ok.data["pyronautTable"])

        pyproject.write_text("[project]\nname = 'demo'\n[tool.pyronaut]\ntoolchain = 3\n", encoding="utf-8")
        with_table = cli._doctor_check_pyproject(project)
        self.assertEqual(doctor.PASS, with_table.status, with_table.detail)
        self.assertTrue(with_table.data["pyronautTable"])

        pyproject.write_text("[project\nname = 'demo'\n", encoding="utf-8")
        broken = cli._doctor_check_pyproject(project)
        self.assertEqual(doctor.FAIL, broken.status)
        self.assertIn("does not parse", broken.detail)
        self.assertIn("Fix the TOML syntax", broken.fix)

        pyproject.write_text("[project]\nname = 'demo'\n[tool.pyronaut.toolchain]\ndistribution = 7\n", encoding="utf-8")
        invalid = cli._doctor_check_pyproject(project)
        self.assertEqual(doctor.FAIL, invalid.status)
        self.assertIn("Invalid toolchain distribution", invalid.detail)

    def test_install_manifest_check_lists_missing_manifests(self):
        project = self.root / "app"
        project.mkdir()
        (project / "pyproject.toml").write_text("[project]\nname = 'demo'\n", encoding="utf-8")
        missing = cli._doctor_check_install_manifests(project)
        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("3 of 3 missing in", missing.detail)
        self.assertIn("resolved-runtime-dependencies", missing.detail)
        self.assertEqual("Run pyronaut install", missing.fix)

        output = project / "__pyronaut__"
        output.mkdir()
        for name in ("resolved-build-dependencies", "resolved-runtime-dependencies", "resolved-test-dependencies"):
            (output / name).write_text("", encoding="utf-8")
        present = cli._doctor_check_install_manifests(project)
        self.assertEqual(doctor.PASS, present.status)
        self.assertIn(f"present in {output}", present.detail)

        gradle = self.root / "gradle-app"
        gradle.mkdir()
        (gradle / "build.gradle").write_text("", encoding="utf-8")
        external = cli._doctor_check_install_manifests(gradle)
        self.assertEqual(doctor.FAIL, external.status)
        self.assertIn("1 of 1 missing", external.detail)
        self.assertIn("project-layout.properties", external.detail)

    def test_pytest_check_imports_pytest_from_the_project_virtualenv(self):
        project = self.root / "app"
        project.mkdir()
        no_venv = cli._doctor_check_pytest(project)
        self.assertEqual(doctor.FAIL, no_venv.status)
        self.assertIn("no project virtualenv", no_venv.detail)
        self.assertEqual("Run pyronaut install to create .venv with GraalPy and install pytest", no_venv.fix)

        venv_python = _write_executable(project / ".venv" / "bin" / "python")
        probes = []

        def capture(command_line, timeout=None):
            probes.append(command_line)
            return 0, "8.3.2\n", ""

        with patch.object(cli, "_doctor_capture", side_effect=capture):
            ok = cli._doctor_check_pytest(project)
        self.assertEqual(doctor.PASS, ok.status)
        self.assertEqual(f"8.3.2 importable from {venv_python}", ok.detail)
        self.assertEqual([[str(venv_python), "-c", "import pytest; print(pytest.__version__)"]], probes)

        with patch.object(cli, "_doctor_capture", return_value=(1, "", "Traceback...\nModuleNotFoundError: No module named 'pytest'\n")):
            missing = cli._doctor_check_pytest(project)
        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("ModuleNotFoundError: No module named 'pytest'", missing.detail)
        self.assertEqual(f"{venv_python} -m pip install pytest", missing.fix)

    # -- proxy ---------------------------------------------------------------

    def test_proxy_check_passes_without_a_proxy(self):
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_download_proxy", return_value=(None, None)),
        ):
            result = cli._doctor_check_proxy()
        self.assertEqual(doctor.PASS, result.status)
        self.assertEqual("no proxy configured", result.detail)

    def test_proxy_check_redacts_credentials_and_names_the_source(self):
        os.environ["HTTPS_PROXY"] = "http://user:secret@proxy.example.com:3128"
        os.environ["NO_PROXY"] = "localhost,*.internal"
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={"proxy": {"url": "http://other:8080"}}),
            patch.object(cli, "_download_proxy", return_value=("http://user:secret@proxy.example.com:3128", "localhost,*.internal")),
        ):
            result = cli._doctor_check_proxy()
        self.assertEqual(doctor.PASS, result.status, result.detail)
        self.assertNotIn("secret", result.detail)
        self.assertNotIn("secret", json.dumps(result.data))
        self.assertIn("http://***@proxy.example.com:3128 from environment (HTTPS_PROXY)", result.detail)
        self.assertIn("bypass localhost,*.internal", result.detail)
        self.assertIn("overrides [proxy] in ~/.pyronaut/settings.toml", result.detail)

        for name in _PROXY_ENV:
            os.environ.pop(name, None)
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={"proxy": {"host": "proxy.example.com", "port": 3128}}),
            patch.object(cli, "_download_proxy", return_value=("http://proxy.example.com:3128", None)),
        ):
            settings = cli._doctor_check_proxy()
        self.assertEqual(doctor.PASS, settings.status)
        self.assertEqual("http://proxy.example.com:3128 from ~/.pyronaut/settings.toml", settings.detail)

        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_download_proxy", return_value=("https://proxy.example.com:7443", "localhost|127.*")),
        ):
            maven = cli._doctor_check_proxy()
        self.assertEqual("https://proxy.example.com:7443 from ~/.m2/settings.xml, bypass localhost|127.*", maven.detail)

    def test_proxy_check_flags_conflicts_incomplete_tables_and_malformed_urls(self):
        os.environ["HTTPS_PROXY"] = "http://proxy.example.com:3128"
        os.environ["https_proxy"] = "http://old-proxy.example.com:3128"
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.object(cli, "_download_proxy", return_value=("http://proxy.example.com:3128", None)),
        ):
            conflict = cli._doctor_check_proxy()
        self.assertEqual(doctor.WARN, conflict.status)
        self.assertIn("HTTPS_PROXY and https_proxy differ (HTTPS_PROXY wins)", conflict.detail)

        for name in _PROXY_ENV:
            os.environ.pop(name, None)
        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={"proxy": {"username": "user"}}),
            patch.object(cli, "_download_proxy", return_value=(None, None)),
        ):
            ignored = cli._doctor_check_proxy()
        self.assertEqual(doctor.WARN, ignored.status)
        self.assertIn("sets neither url nor host and port", ignored.detail)
        self.assertIn("[proxy].url", ignored.fix)

        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={"proxy": {"url": "http://proxy.example.com"}}),
            patch.object(cli, "_download_proxy", return_value=("http://proxy.example.com", None)),
        ):
            no_port = cli._doctor_check_proxy()
        self.assertEqual(doctor.FAIL, no_port.status)
        self.assertIn("is not an http(s)://host:port URL", no_port.detail)

        with (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={"proxy": "http://proxy.example.com:3128"}),
        ):
            not_table = cli._doctor_check_proxy()
        self.assertEqual(doctor.FAIL, not_table.status)
        self.assertIn("must be a table", not_table.detail)

        with patch.object(cli, "_read_pyronaut_user_settings", side_effect=RuntimeError("Failed reading settings.toml: bad toml")):
            unreadable = cli._doctor_check_proxy()
        self.assertEqual(doctor.FAIL, unreadable.status)
        self.assertIn("bad toml", unreadable.detail)

    # -- docker --------------------------------------------------------------

    def test_docker_check_only_warns(self):
        with patch.object(shutil, "which", return_value=None):
            absent = cli._doctor_check_docker()
        self.assertEqual(doctor.WARN, absent.status)
        self.assertIn("docker CLI not found on PATH", absent.detail)
        self.assertIn("needed only for test resources", absent.detail)
        self.assertIn("PYRONAUT_TEST_RESOURCES_DISABLED", absent.fix)

        with (
            patch.object(shutil, "which", return_value="/usr/local/bin/docker"),
            patch.object(cli, "_doctor_capture", return_value=(1, "", "Cannot connect to the Docker daemon at unix:///var/run/docker.sock\n")),
        ):
            down = cli._doctor_check_docker()
        self.assertEqual(doctor.WARN, down.status)
        self.assertIn("daemon not reachable via /usr/local/bin/docker", down.detail)
        self.assertIn("Cannot connect to the Docker daemon", down.detail)
        self.assertIn("DOCKER_HOST", down.fix)

        probes = []

        def capture(command_line, timeout=None):
            probes.append(command_line)
            return 0, "27.1.1\n", ""

        with patch.object(shutil, "which", return_value="/usr/local/bin/docker"), patch.object(cli, "_doctor_capture", side_effect=capture):
            up = cli._doctor_check_docker()
        self.assertEqual(doctor.PASS, up.status)
        self.assertEqual("daemon reachable, server 27.1.1 (/usr/local/bin/docker)", up.detail)
        self.assertEqual([["/usr/local/bin/docker", "version", "--format", "{{.Server.Version}}"]], probes)
        self.assertEqual("27.1.1", up.data["serverVersion"])

    # -- probes --------------------------------------------------------------

    def test_doctor_capture_never_raises(self):
        code, out, err = cli._doctor_capture([sys.executable, "-c", "print('hi')"])
        self.assertEqual((0, "hi\n", ""), (code, out, err))
        code, out, err = cli._doctor_capture([str(self.root / "missing-tool")])
        self.assertEqual(cli.INTERNAL_ERROR, code)
        self.assertTrue(err)
        code, out, err = cli._doctor_capture([sys.executable, "-c", "import time; time.sleep(5)"], timeout=0.2)
        self.assertEqual(cli.INTERNAL_ERROR, code)
        self.assertIn("timed out", err)


class DoctorRuntimeChecksTest(unittest.TestCase):
    """Checks that mirror the project's threading, packages and deployment setup."""

    _TOML = "[project]\nname = 'demo'\nversion = '1.0'\ndependencies = ['requests>=2', 'attrs', 'not-installed-xyz']\n"

    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name).resolve()
        self.addCleanup(self._temp.cleanup)
        self.home = self.root / "home"
        self.home.mkdir()
        patcher = patch("pathlib.Path.home", return_value=self.home)
        patcher.start()
        self.addCleanup(patcher.stop)
        env = patch.dict(os.environ, {"HOME": str(self.home)}, clear=False)
        env.start()
        self.addCleanup(env.stop)
        for name in ("PYRONAUT_PYTHON_EXECUTABLE", "VIRTUAL_ENV", "PYENV_VERSION", "PYENV_ROOT"):
            os.environ.pop(name, None)
        self.project = self.root / "app"
        (self.project / "config").mkdir(parents=True)
        (self.project / "pyproject.toml").write_text(self._TOML, encoding="utf-8")

    def _write_config(self, text):
        (self.project / "config" / "application.toml").write_text(text, encoding="utf-8")

    def _fake_venv(self, python=None):
        """A real venv layout whose python is the interpreter running the tests.

        CPython discovers ``pyvenv.cfg`` next to the ``bin`` directory of the
        launched path before resolving symlinks, so the probe sees this venv's
        ``site-packages`` exactly like a project ``.venv``.
        """
        venv = self.project / ".venv"
        bin_dir = venv / "bin"
        bin_dir.mkdir(parents=True, exist_ok=True)
        link = bin_dir / "python"
        if not link.exists():
            link.symlink_to(python or sys.executable)
        version = f"{sys.version_info.major}.{sys.version_info.minor}"
        (venv / "pyvenv.cfg").write_text(
            f"home = {Path(sys.executable).parent}\ninclude-system-site-packages = false\nversion = {version}.0\n", encoding="utf-8"
        )
        self.site_packages = venv / "lib" / f"python{version}" / "site-packages"
        self.site_packages.mkdir(parents=True, exist_ok=True)
        return link

    def _fake_distribution(self, name, *, modules, native=False, body="VALUE = 1\n"):
        """Install a minimal distribution (METADATA + RECORD) into the fake venv."""
        records = []
        for module in modules:
            package_dir = self.site_packages / module
            package_dir.mkdir(exist_ok=True)
            (package_dir / "__init__.py").write_text(body, encoding="utf-8")
            records.append(f"{module}/__init__.py,,")
            if native:
                (package_dir / "_speedups.cpython-313-graalpy.so").write_bytes(b"")
                records.append(f"{module}/_speedups.cpython-313-graalpy.so,,")
        dist_info = self.site_packages / f"{name.replace('-', '_')}-1.0.dist-info"
        dist_info.mkdir(exist_ok=True)
        (dist_info / "METADATA").write_text(f"Metadata-Version: 2.1\nName: {name}\nVersion: 1.0\n", encoding="utf-8")
        records.append(f"{dist_info.name}/METADATA,,")
        (dist_info / "RECORD").write_text("\n".join(records) + "\n", encoding="utf-8")

    # -- declared dependencies ------------------------------------------------

    def test_declared_python_dependencies_are_normalised(self):
        (self.project / "pyproject.toml").write_text(
            "[project]\nname = 'demo'\ndependencies = [\n"
            "  'Requests>=2.31 ; python_version >= \"3.10\"',\n"
            "  'attrs[tests]',\n"
            "  'requests',\n"
            "  'rich ; extra == \"cli\"',\n"
            "  'psycopg2-binary==2.9.10',\n"
            "  42,\n"
            "]\n",
            encoding="utf-8",
        )
        self.assertEqual(["Requests", "attrs", "psycopg2-binary"], cli._read_pyproject_python_dependencies(self.project))
        (self.project / "pyproject.toml").write_text("[project]\nname = 'demo'\n", encoding="utf-8")
        self.assertEqual([], cli._read_pyproject_python_dependencies(self.project))

    # -- runtime configuration ------------------------------------------------

    def test_runtime_config_reads_pool_executors_and_deployment_mode(self):
        self._write_config("[micronaut.python.pool]\nenabled = true\nsize = 4\n\n[micronaut.executors.io]\ntype = 'fixed'\nn-threads = 8\n")
        config = cli._doctor_runtime_config(self.project)
        self.assertEqual((True, 4, 4), (config.pool_enabled, config.pool_size, config.probe_threads))
        self.assertEqual({"io": {"type": "fixed", "n-threads": 8}}, config.executors)
        self.assertEqual(("wheel-jvm", "jvm", False), (config.packaging_format, config.toolchain_type, config.native))
        self.assertEqual(self.project / "config" / "application.toml", config.config_file)
        self.assertEqual((), config.problems)

        self._write_config("[micronaut.python.pool]\nenabled = false\n")
        self.assertEqual(1, cli._doctor_runtime_config(self.project).probe_threads)

        self._write_config("[micronaut.python.pool]\nsize = 0\n")
        with patch.object(os, "cpu_count", return_value=3):
            self.assertEqual(6, cli._doctor_runtime_config(self.project).probe_threads)
        with patch.object(os, "cpu_count", return_value=64):
            self.assertEqual(cli._DOCTOR_MAX_PROBE_THREADS, cli._doctor_runtime_config(self.project).probe_threads)

        self._write_config("[micronaut.python.pool]\nsize = 'many'\nenabled = 'yes'\n")
        problems = cli._doctor_runtime_config(self.project).problems
        self.assertTrue(any("pool.size" in problem for problem in problems))
        self.assertTrue(any("pool.enabled" in problem for problem in problems))

    def test_runtime_config_supports_properties_and_flags_unparsed_yaml(self):
        (self.project / "config" / "application.properties").write_text(
            "micronaut.python.pool.size=2\nmicronaut.executors.blocking.virtual=true\n", encoding="utf-8"
        )
        config = cli._doctor_runtime_config(self.project)
        self.assertEqual(2, config.pool_size)
        self.assertEqual({"blocking": {"virtual": True}}, config.executors)
        (self.project / "config" / "application.properties").unlink()

        (self.project / "config" / "application.yml").write_text("micronaut:\n  python:\n    pool:\n      size: 9\n", encoding="utf-8")
        config = cli._doctor_runtime_config(self.project)
        self.assertIsNone(config.config_file)
        self.assertEqual((str(self.project / "config" / "application.yml"),), config.unparsed)
        self.assertIsNone(config.pool_size)

    def test_runtime_config_detects_native_deployment(self):
        (self.project / "pyproject.toml").write_text(self._TOML + "[tool.pyronaut.packaging]\nformat = 'docker-native'\n", encoding="utf-8")
        self.assertTrue(cli._doctor_runtime_config(self.project).native)
        (self.project / "pyproject.toml").write_text(self._TOML + "[tool.pyronaut.toolchain]\ntype = 'native'\n", encoding="utf-8")
        config = cli._doctor_runtime_config(self.project)
        self.assertTrue(config.native)
        self.assertEqual("wheel-jvm packaging, native toolchain", cli._deployment_label(config))
        (self.project / "pyproject.toml").write_text(self._TOML + "[tool.pyronaut.packaging]\nformat = 'zip'\n", encoding="utf-8")
        self.assertTrue(any("packaging.format" in problem for problem in cli._doctor_runtime_config(self.project).problems))

    # -- threading ------------------------------------------------------------

    def test_threading_check_rejects_virtual_executors(self):
        self._write_config("[micronaut.python.pool]\nsize = 4\n\n[micronaut.executors.io]\nvirtual = true\n")
        result = cli._doctor_check_threading(self.project, cli._doctor_runtime_config(self.project))
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("executor io set virtual = true", result.detail)
        self.assertIn("Set virtual = false", result.fix)

        self._write_config("[micronaut.python.pool]\nsize = 4\n\n[micronaut.executors.io]\ntype = 'fixed'\nn-threads = 8\n")
        result = cli._doctor_check_threading(self.project, cli._doctor_runtime_config(self.project))
        self.assertEqual(doctor.PASS, result.status, result.detail)
        self.assertEqual("context pool enabled, size 4; executors io: fixed, 8 threads; wheel-jvm packaging, JVM toolchain", result.detail)
        self.assertEqual(4, result.data["probeThreads"])

        self._write_config("[micronaut.python.pool]\nsize = -1\n")
        result = cli._doctor_check_threading(self.project, cli._doctor_runtime_config(self.project))
        self.assertEqual(doctor.FAIL, result.status)
        self.assertIn("pool.size", result.detail)

    def test_threading_check_warns_about_uninspected_yaml(self):
        (self.project / "config" / "application.yml").write_text("micronaut: {}\n", encoding="utf-8")
        result = cli._doctor_check_threading(self.project, cli._doctor_runtime_config(self.project))
        self.assertEqual(doctor.WARN, result.status)
        self.assertIn("application.yml not inspected", result.detail)
        self.assertIn("no application.toml/properties found", result.detail)
        self.assertIn("application.toml", result.fix)

    # -- interpreter ----------------------------------------------------------

    def test_interpreter_check_reports_overrides(self):
        self.assertEqual(doctor.PASS, cli._doctor_check_interpreter(None).status)

        os.environ["PYRONAUT_PYTHON_EXECUTABLE"] = str(self.root / "missing-python")
        missing = cli._doctor_check_interpreter(None)
        self.assertEqual(doctor.FAIL, missing.status)
        self.assertIn("is not an executable file", missing.detail)

        cpython = _write_executable(self.root / "cpython" / "bin" / "python")
        os.environ["PYRONAUT_PYTHON_EXECUTABLE"] = str(cpython)
        not_graalpy = cli._doctor_check_interpreter(None)
        self.assertEqual(doctor.WARN, not_graalpy.status)
        self.assertIn("is not a GraalPy interpreter", not_graalpy.detail)
        os.environ.pop("PYRONAUT_PYTHON_EXECUTABLE")

        graalpy_home = self.root / "graalpy-25.4" / "bin"
        _write_executable(graalpy_home / "graalpy")
        _write_executable(self.project / ".venv" / "bin" / "python")
        (self.project / ".venv" / "pyvenv.cfg").write_text(f"home = {graalpy_home}\n", encoding="utf-8")
        os.environ["VIRTUAL_ENV"] = str(self.root / "other-venv")
        with patch.object(cli, "_find_global_graalpy", return_value=(graalpy_home / "graalpy", "PATH")):
            other = cli._doctor_check_interpreter(self.project)
        self.assertEqual(doctor.WARN, other.status)
        self.assertIn("is not the project .venv", other.detail)
        self.assertIn("activate", other.fix)
        os.environ.pop("VIRTUAL_ENV")

    def test_interpreter_check_compares_venv_base_with_selected_graalpy(self):
        graalpy_home = self.root / "graalpy-25.4" / "bin"
        _write_executable(graalpy_home / "graalpy")
        _write_executable(self.project / ".venv" / "bin" / "python")
        (self.project / ".venv" / "pyvenv.cfg").write_text(f"home = {graalpy_home}\n", encoding="utf-8")
        with patch.object(cli, "_find_global_graalpy", return_value=(graalpy_home / "graalpy", "PATH")):
            matching = cli._doctor_check_interpreter(self.project)
        self.assertEqual(doctor.PASS, matching.status, matching.detail)
        self.assertIn("matches the selected GraalPy", matching.detail)

        newer_home = self.root / "graalpy-26.0" / "bin"
        _write_executable(newer_home / "graalpy")
        with patch.object(cli, "_find_global_graalpy", return_value=(newer_home / "graalpy", "pyenv version graalpy3.14-26.0 selected by PYENV_VERSION")):
            switched = cli._doctor_check_interpreter(self.project)
        self.assertEqual(doctor.WARN, switched.status)
        self.assertIn(f"created from {graalpy_home} but the selected GraalPy is {newer_home / 'graalpy'}", switched.detail)
        self.assertIn("Recreate .venv", switched.fix)

        shutil.rmtree(graalpy_home.parent)
        with patch.object(cli, "_find_global_graalpy", return_value=None):
            removed = cli._doctor_check_interpreter(self.project)
        self.assertEqual(doctor.FAIL, removed.status)
        self.assertIn("no longer exists", removed.detail)

    # -- generated state ------------------------------------------------------

    def test_generated_state_check_detects_stale_manifests_and_pyproject_changes(self):
        self.assertEqual(doctor.PASS, cli._doctor_check_generated_state(self.project).status)

        output = self.project / "__pyronaut__"
        output.mkdir()
        jar = self.root / "m2" / "lib.jar"
        jar.parent.mkdir()
        jar.write_bytes(b"jar")
        for name in ("resolved-build-dependencies", "resolved-runtime-dependencies", "resolved-test-dependencies"):
            (output / name).write_text(f"{jar}\n", encoding="utf-8")
        old = 1_600_000_000
        os.utime(self.project / "pyproject.toml", (old, old))
        current = cli._doctor_check_generated_state(self.project)
        self.assertEqual(doctor.PASS, current.status, current.detail)
        self.assertIn("not processed yet", current.detail)

        (output / "classes").mkdir()
        (output / "classes" / "App.class").write_bytes(b"")
        (self.project / "src").mkdir()
        (self.project / "src" / "app.py").write_text("x = 1\n", encoding="utf-8")
        future = time.time() + 60
        os.utime(self.project / "src" / "app.py", (future, future))
        reprocess = cli._doctor_check_generated_state(self.project)
        self.assertEqual(doctor.PASS, reprocess.status)
        self.assertIn("sources changed after the last pyronaut process", reprocess.detail)
        self.assertTrue(reprocess.data["sourcesNewerThanClasses"])

        os.utime(self.project / "pyproject.toml", (future, future))
        edited = cli._doctor_check_generated_state(self.project)
        self.assertEqual(doctor.WARN, edited.status)
        self.assertIn("pyproject.toml changed after the last pyronaut install", edited.detail)
        self.assertEqual("Run pyronaut install", edited.fix)

        jar.unlink()
        stale = cli._doctor_check_generated_state(self.project)
        self.assertEqual(doctor.FAIL, stale.status)
        self.assertIn("resolved-runtime-dependencies (1 missing)", stale.detail)
        self.assertEqual({"resolved-build-dependencies": 1, "resolved-runtime-dependencies": 1, "resolved-test-dependencies": 1}, stale.data["staleManifests"])

    # -- packages -------------------------------------------------------------

    def test_package_probe_reports_missing_broken_and_importable_packages(self):
        venv_python = self._fake_venv()
        self._fake_distribution("Good-Pkg", modules=["good_pkg"])
        self._fake_distribution("broken-pkg", modules=["broken_pkg"], body="raise ImportError('needs libfoo')\n")
        self._fake_distribution("speedy", modules=["speedy"], native=True)
        request = json.dumps({"packages": ["good_pkg", "broken-pkg", "speedy", "definitely-missing-xyz"], "threads": 3})
        code, out, err = cli._doctor_capture([str(venv_python), "-c", cli._DOCTOR_PACKAGE_PROBE, request], timeout=120)
        self.assertEqual(0, code, err)
        report = json.loads(out.splitlines()[-1])
        self.assertEqual(3, report["threads"])
        packages = report["packages"]
        self.assertEqual({"installed": False}, packages["definitely-missing-xyz"])
        self.assertEqual({"installed": True, "version": "1.0", "modules": ["good_pkg"], "native": False, "errors": {}}, packages["good_pkg"])
        self.assertEqual({"broken_pkg": "ImportError: needs libfoo"}, packages["broken-pkg"]["errors"])
        self.assertTrue(packages["speedy"]["native"])
        self.assertEqual({}, packages["speedy"]["errors"])

    def test_packages_check_runs_the_probe_in_the_project_virtualenv(self):
        config = cli._doctor_runtime_config(self.project)
        no_venv = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.FAIL, no_venv.status)
        self.assertIn("no project virtualenv", no_venv.detail)

        venv_python = self._fake_venv()
        self._fake_distribution("good-pkg", modules=["good_pkg"])
        (self.project / "pyproject.toml").write_text(
            "[project]\nname = 'demo'\ndependencies = ['Good_Pkg>=1', 'not-installed-xyz']\n", encoding="utf-8"
        )
        self._write_config("[micronaut.python.pool]\nsize = 2\n")
        config = cli._doctor_runtime_config(self.project)
        missing = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.FAIL, missing.status, missing.detail)
        self.assertIn("1 of 2 declared packages not installed", missing.detail)
        self.assertEqual(f"{venv_python} -m pip install not-installed-xyz", missing.fix)
        self.assertEqual(2, missing.data["threads"])

        (self.project / "pyproject.toml").write_text("[project]\nname = 'demo'\ndependencies = ['Good_Pkg>=1']\n", encoding="utf-8")
        ok = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.PASS, ok.status, ok.detail)
        self.assertEqual("1 declared package import cleanly under 2 concurrent threads (wheel-jvm packaging, JVM toolchain)", ok.detail)
        self.assertEqual("1.0", ok.data["packages"]["Good_Pkg"]["version"])

        self._fake_distribution("broken-pkg", modules=["broken_pkg"], body="raise RuntimeError('boom')\n")
        (self.project / "pyproject.toml").write_text("[project]\nname = 'demo'\ndependencies = ['good-pkg', 'broken-pkg']\n", encoding="utf-8")
        broken = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.FAIL, broken.status, broken.detail)
        self.assertIn("broken-pkg (broken_pkg: RuntimeError: boom)", broken.detail)

    def test_packages_check_reports_import_errors_and_native_extensions(self):
        self._fake_venv()
        (self.project / "pyproject.toml").write_text(
            self._TOML + "[tool.pyronaut.packaging]\nformat = 'wheel-native'\n", encoding="utf-8"
        )
        config = cli._doctor_runtime_config(self.project)
        probe = {
            "requests": {"installed": True, "version": "2.32.3", "modules": ["requests"], "native": False, "errors": {}},
            "attrs": {"installed": True, "version": "25.1.0", "modules": ["attr", "attrs"], "native": True, "errors": {}},
            "not-installed-xyz": {"installed": True, "version": "1", "modules": ["nix"], "native": False, "errors": {"nix": "ImportError: cannot import name 'x'"}},
        }
        with patch.object(cli, "_doctor_probe_packages", return_value=({"packages": probe}, "")):
            broken = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.FAIL, broken.status)
        self.assertIn("1 of 3 packages fail to import", broken.detail)
        self.assertIn("not-installed-xyz (nix: ImportError: cannot import name 'x')", broken.detail)

        probe["not-installed-xyz"]["errors"] = {}
        with patch.object(cli, "_doctor_probe_packages", return_value=({"packages": probe}, "")):
            native = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.WARN, native.status)
        self.assertIn("wheel-native packaging, native toolchain", native.detail)
        self.assertIn("attrs contain native extensions, which are experimental on the native image", native.detail)

        with patch.object(cli, "_doctor_probe_packages", return_value=(None, "Segmentation fault")):
            crashed = cli._doctor_check_packages(self.project, config)
        self.assertEqual(doctor.FAIL, crashed.status)
        self.assertIn("package probe failed", crashed.detail)
        self.assertIn("Segmentation fault", crashed.detail)

    # -- GraalPy compatibility ------------------------------------------------

    def test_graalpy_release_tag_is_derived_from_the_interpreter_or_bundled_version(self):
        self.assertEqual("v254", cli._graalpy_release_tag("GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)", None))
        self.assertEqual("v250", cli._graalpy_release_tag(None, "25.0.1"))
        self.assertEqual("v242", cli._graalpy_release_tag("Python 3.12.4", "24.2.0"))
        self.assertIsNone(cli._graalpy_release_tag("Python 3.12.4", None))

    def test_compatibility_data_is_cached_and_parsed(self):
        csv = "requests,2.32.3,0,98.51\nattrs,25.1.0,0,100.00\nuvloop,0.21.0,2,0.00\naiohttp,3.11.18,0,83.99\nbroken-line\n"
        downloads = []

        def fake_download(url, destination, label, headers=None):
            downloads.append(url)
            Path(destination).write_text(csv, encoding="utf-8")

        with patch.object(cli, "_download_url_with_progress", side_effect=fake_download):
            table, note = cli._load_graalpy_compatibility("v254", offline=False)
            again, _ = cli._load_graalpy_compatibility("v254", offline=False)
        self.assertEqual(["https://graalpy.org/module_results/python-module-testing-v254.csv"], downloads)
        self.assertEqual("", note)
        self.assertEqual(("uvloop" in table, table["uvloop"], table["aiohttp"]), (True, ("0.21.0", 2, 0.0), ("3.11.18", 0, 83.99)))
        self.assertEqual(table, again)
        self.assertTrue(cli._graalpy_compatibility_cache("v254").is_file())

        with patch.object(cli, "_download_url_with_progress", side_effect=OSError("no network")):
            offline_table, _ = cli._load_graalpy_compatibility("v254", offline=True)
            missing, reason = cli._load_graalpy_compatibility("v250", offline=True)
            failed, failure = cli._load_graalpy_compatibility("v250", offline=False)
        self.assertEqual(table, offline_table)
        self.assertIsNone(missing)
        self.assertIn("not cached (offline)", reason)
        self.assertIsNone(failed)
        self.assertIn("could not refresh", failure)

    def test_compatibility_check_flags_packages_known_to_fail_on_graalpy(self):
        self._fake_venv()
        cache = cli._graalpy_compatibility_cache("v254")
        cache.parent.mkdir(parents=True)
        cache.write_text("requests,2.32.3,0,98.51\nattrs,25.1.0,0,100.00\nuvloop,0.21.0,2,0.00\naiohttp,3.11.18,0,83.99\n", encoding="utf-8")
        (self.project / "pyproject.toml").write_text(
            "[project]\nname = 'demo'\ndependencies = ['Requests', 'attrs', 'uvloop', 'aiohttp', 'mystery']\n", encoding="utf-8"
        )
        with patch.object(cli, "_probe_python_version", return_value="GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)"):
            result = cli._doctor_check_graalpy_compatibility(self.project, offline=True)
        self.assertEqual(doctor.WARN, result.status)
        self.assertIn("GraalPy 25.4: 2 of 5 declared packages compatible", result.detail)
        self.assertIn("untested: mystery", result.detail)
        self.assertIn("fail to install: uvloop 0.21.0", result.detail)
        self.assertIn("partially compatible: aiohttp 3.11.18 (84% tests pass)", result.detail)
        self.assertEqual("fails-to-install", result.data["packages"]["uvloop"]["status"])
        self.assertEqual("compatible", result.data["packages"]["Requests"]["status"])

        (self.project / "pyproject.toml").write_text("[project]\nname = 'demo'\ndependencies = ['requests', 'attrs']\n", encoding="utf-8")
        with patch.object(cli, "_probe_python_version", return_value="GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)"):
            ok = cli._doctor_check_graalpy_compatibility(self.project, offline=True)
        self.assertEqual(doctor.PASS, ok.status, ok.detail)
        self.assertEqual("GraalPy 25.4: 2 of 2 declared packages compatible per graalpy.org", ok.detail)

        cache.unlink()
        with patch.object(cli, "_probe_python_version", return_value="GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)"):
            unavailable = cli._doctor_check_graalpy_compatibility(self.project, offline=True)
        self.assertEqual(doctor.WARN, unavailable.status)
        self.assertIn("package data unavailable", unavailable.detail)

        shutil.rmtree(self.project / ".venv")
        with patch.object(cli, "_read_version_properties", return_value={}):
            unknown = cli._doctor_check_graalpy_compatibility(self.project, offline=True)
        self.assertEqual(doctor.WARN, unknown.status)
        self.assertIn("cannot determine the GraalPy release", unknown.detail)


if __name__ == "__main__":
    unittest.main()
