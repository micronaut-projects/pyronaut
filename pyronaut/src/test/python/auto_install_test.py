import importlib.util
import io
import json
import os
import sys
import tempfile
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

_PYPROJECT = """[project]
name = "demo"
version = "0.1.0"

[tool.pyronaut.dependencies]
runtime = ["{coordinate}"]
"""


class AutoInstallTest(unittest.TestCase):
    def setUp(self):
        self._temp_dir = tempfile.TemporaryDirectory()
        self.project_dir = Path(self._temp_dir.name).resolve() / "demo"
        self.project_dir.mkdir()
        self.calls: list[list[str]] = []
        self.output = io.StringIO()
        # Keep the developer's own auto-install preferences out of the tests.
        for patcher in (
            patch.object(cli, "_read_pyronaut_user_settings", return_value={}),
            patch.dict(os.environ, {}, clear=False),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)
        os.environ.pop(cli.AUTO_INSTALL_ENV, None)

    def tearDown(self):
        self._temp_dir.cleanup()

    def _write_pyproject(self, coordinate: str = "io.example:lib:1.0") -> None:
        (self.project_dir / "pyproject.toml").write_text(_PYPROJECT.format(coordinate=coordinate), encoding="utf-8")

    def _write_installed_state(self, *, record: bool = True) -> None:
        cache_dir = self.project_dir / "__pyronaut__"
        (cache_dir / "classes").mkdir(parents=True, exist_ok=True)
        (cache_dir / "test-classes").mkdir(parents=True, exist_ok=True)
        for name in ("resolved-build-dependencies", "resolved-runtime-dependencies", "resolved-test-dependencies"):
            (cache_dir / name).write_text("/tmp/lib.jar\n", encoding="utf-8")
        if record:
            cli._record_install_state(self.project_dir)

    def _fake_delegate(self, command, args, *_args, **_kwargs):
        self.calls.append([command, *args])
        if command == "install":
            self._write_installed_state(record=False)
            # The real installer rewrites pyproject.toml with a schema directive.
            pyproject = self.project_dir / "pyproject.toml"
            pyproject.write_text("#:schema ./schema.json\n" + pyproject.read_text(encoding="utf-8"), encoding="utf-8")
        return cli.SUCCESS

    def _run(self, argv, **kwargs) -> int:
        with patch.object(cli, "_delegate", side_effect=self._fake_delegate), \
                patch.object(cli, "_run_lifecycle_validation", return_value=cli.SUCCESS), \
                redirect_stdout(self.output), redirect_stderr(self.output):
            return cli.run(
                argv,
                runner_with_env=lambda command_line, env=None: cli.SUCCESS,
                resolver=lambda name: f"/tmp/{name}",
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk",
                **kwargs,
            )

    def _commands(self) -> list[str]:
        return [call[0] for call in self.calls]

    def test_run_skips_install_when_recorded_state_matches(self):
        self._write_pyproject()
        self._write_installed_state()

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "run"], self._commands())
        self.assertNotIn("pyronaut install", self.output.getvalue())

    def test_run_installs_first_when_dependencies_changed(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["install", "process", "run"], self._commands())
        self.assertEqual(["install", "--project-dir", str(self.project_dir)], self.calls[0])
        lines = [line for line in self.output.getvalue().splitlines() if "pyronaut install" in line]
        self.assertEqual(
            ["[tool.pyronaut] settings changed since the last install; running pyronaut install before run"],
            lines,
        )
        # The new state is recorded, so the next run does not install again.
        self.calls.clear()
        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))
        self.assertEqual(["process", "run"], self._commands())

    def test_run_installs_when_manifests_are_missing(self):
        self._write_pyproject()

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir), "--no-cache"]))

        self.assertEqual("install", self._commands()[0])
        self.assertIn("--no-cache", self.calls[0])
        self.assertIn("Dependencies are not installed; running pyronaut install before run", self.output.getvalue())

    def test_run_installs_when_pyronaut_version_changed(self):
        self._write_pyproject()
        self._write_installed_state()
        state_file = self.project_dir / "__pyronaut__" / cli._INSTALL_STATE_FILE
        state = json.loads(state_file.read_text(encoding="utf-8"))
        state["pyronaut"] = "0.0.0-older"
        state_file.write_text(json.dumps(state), encoding="utf-8")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["install", "process", "run"], self._commands())

    def test_project_metadata_edits_do_not_reinstall(self):
        self._write_pyproject()
        self._write_installed_state()
        pyproject = self.project_dir / "pyproject.toml"
        pyproject.write_text(pyproject.read_text(encoding="utf-8").replace("0.1.0", "0.2.0"), encoding="utf-8")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "run"], self._commands())

    def test_install_before_state_was_recorded_is_trusted(self):
        self._write_installed_state(record=False)
        self._write_pyproject("io.example:lib:2.0")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "run"], self._commands())

    def test_no_install_flag_warns_and_is_not_forwarded(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir), "--no-install"]))

        self.assertEqual(["process", "run"], self._commands())
        self.assertNotIn("--no-install", self.calls[-1])
        self.assertIn(
            "[tool.pyronaut] settings changed since the last install; skipped install (--no-install), "
            "run pyronaut install",
            self.output.getvalue(),
        )

    def test_auto_install_can_be_disabled_in_user_settings(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        with patch.object(cli, "_read_pyronaut_user_settings", return_value={"install": {"auto": False}}):
            self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "run"], self._commands())
        self.assertIn(
            "skipped install ([install] auto = false in ~/.pyronaut/settings.toml), run pyronaut install",
            self.output.getvalue(),
        )

    def test_auto_install_can_be_disabled_with_environment_variable(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        with patch.dict(os.environ, {cli.AUTO_INSTALL_ENV: "false"}):
            self.assertEqual(cli.SUCCESS, self._run(["test", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "test"], self._commands())
        self.assertIn("skipped install (PYRONAUT_AUTO_INSTALL=false)", self.output.getvalue())

    def test_environment_variable_overrides_user_settings(self):
        with patch.object(cli, "_read_pyronaut_user_settings", return_value={"install": {"auto": False}}), \
                patch.dict(os.environ, {cli.AUTO_INSTALL_ENV: "true"}):
            self.assertIsNone(cli._auto_install_disabled_by())

    def test_auto_install_is_enabled_by_default(self):
        self.assertIsNone(cli._auto_install_disabled_by())

    def test_invalid_auto_install_configuration_fails_the_command(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        with patch.dict(os.environ, {cli.AUTO_INSTALL_ENV: "sometimes"}):
            self.assertEqual(cli.PRECONDITION_FAILED, self._run(["run", "--project-dir", str(self.project_dir)]))
        self.assertIn("PYRONAUT_AUTO_INSTALL must be true or false", self.output.getvalue())

        with patch.object(cli, "_read_pyronaut_user_settings", return_value={"install": {"auto": "no"}}):
            with self.assertRaisesRegex(RuntimeError, r"\[install\]\.auto"):
                cli._auto_install_disabled_by()

    def test_no_install_after_double_dash_is_an_application_argument(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir), "--", "--no-install"]))

        self.assertEqual(["install", "process", "run"], self._commands())
        self.assertEqual("--no-install", self.calls[-1][-1])

    def test_python_requirement_change_installs_only_python_dependencies(self):
        self._write_pyproject()
        self._write_installed_state()
        venv_calls = []

        def ensure_venv(project_dir, runner, **_kwargs):
            venv_calls.append(project_dir)
            return cli.SUCCESS

        with patch.object(cli, "_python_requirements_stale", return_value=True), \
                patch.object(cli, "_ensure_project_virtualenv", side_effect=ensure_venv):
            self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual([self.project_dir], venv_calls)
        self.assertEqual(["process", "run"], self._commands())
        self.assertIn(
            "Python dependencies changed since the last install; running pyronaut install before run",
            self.output.getvalue(),
        )

    def test_python_requirements_compare_with_recorded_virtualenv_state(self):
        (self.project_dir / "pyproject.toml").write_text(
            '[project]\nname = "demo"\nversion = "0.1"\ndependencies = ["requests==2.0"]\n', encoding="utf-8"
        )
        self.assertTrue(cli._python_requirements_stale(self.project_dir))

        venv_dir = self.project_dir / ".venv"
        venv_dir.mkdir()
        state = cli._project_venv_state(Path("/tmp/graalpy"), ["requests==2.0"], [])
        (venv_dir / cli._PROJECT_VENV_STATE).write_text(json.dumps(state), encoding="utf-8")
        self.assertFalse(cli._python_requirements_stale(self.project_dir))

        (self.project_dir / "pyproject.toml").write_text(
            '[project]\nname = "demo"\nversion = "0.1"\ndependencies = ["requests==2.1"]\n', encoding="utf-8"
        )
        self.assertTrue(cli._python_requirements_stale(self.project_dir))

    def _forbid_virtualenv_install(self):
        def fail(*_args, **_kwargs):
            raise AssertionError("dev/run/test must not reinstall Python dependencies")

        return patch.object(cli, "_ensure_project_virtualenv", side_effect=fail)

    def test_uv_project_recorded_by_install_is_not_reinstalled(self):
        (self.project_dir / "pyproject.toml").write_text(
            '[project]\nname = "demo"\nversion = "0.1"\ndependencies = ["requests==2.0"]\n', encoding="utf-8"
        )
        lock = self.project_dir / "uv.lock"
        lock.write_text("version = 1\n", encoding="utf-8")
        self._write_installed_state()
        with patch.object(cli, "_find_uv", return_value="/tmp/uv"):
            plan = cli._python_dependency_plan(self.project_dir)
            self.assertEqual("uv", plan.installer)
            venv_dir = self.project_dir / ".venv"
            venv_dir.mkdir()
            # What `pyronaut install` records after `uv sync`.
            (venv_dir / cli._PROJECT_VENV_STATE).write_text(
                json.dumps(plan.state(Path("/tmp/graalpy"))), encoding="utf-8"
            )

            with self._forbid_virtualenv_install():
                self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))
            self.assertEqual(["process", "run"], self._commands())

            lock.write_text("version = 1\n# requests 2.1\n", encoding="utf-8")
            self.assertTrue(cli._python_requirements_stale(self.project_dir))

    def test_self_managed_virtualenv_is_never_reinstalled(self):
        (self.project_dir / "pyproject.toml").write_text(
            '[project]\nname = "demo"\nversion = "0.1"\ndependencies = ["requests==2.0"]\n\n'
            "[tool.pyronaut.python]\nmanage-dependencies = false\n",
            encoding="utf-8",
        )
        self._write_installed_state()

        with self._forbid_virtualenv_install():
            self.assertEqual(cli.SUCCESS, self._run(["run", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["process", "run"], self._commands())

    def test_invalid_manage_dependencies_setting_fails_instead_of_looping(self):
        (self.project_dir / "pyproject.toml").write_text(
            '[project]\nname = "demo"\nversion = "0.1"\ndependencies = ["requests==2.0"]\n\n'
            '[tool.pyronaut.python]\nmanage-dependencies = "sometimes"\n',
            encoding="utf-8",
        )
        self._write_installed_state()

        exit_code = self._run(["run", "--project-dir", str(self.project_dir)])

        self.assertEqual(cli.USAGE_ERROR, exit_code)
        self.assertEqual([], self._commands())
        self.assertIn("manage-dependencies", self.output.getvalue())

    def test_failed_install_stops_before_processing(self):
        self._write_pyproject()

        def failing_delegate(command, args, *_args, **_kwargs):
            self.calls.append([command, *args])
            return 7 if command == "install" else cli.SUCCESS

        with patch.object(cli, "_delegate", side_effect=failing_delegate), redirect_stdout(self.output), \
                redirect_stderr(self.output):
            exit_code = cli.run(
                ["run", "--project-dir", str(self.project_dir)],
                runner_with_env=lambda command_line, env=None: cli.SUCCESS,
                resolver=lambda name: f"/tmp/{name}",
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk",
            )

        self.assertEqual(7, exit_code)
        self.assertEqual(["install"], self._commands())
        self.assertFalse((self.project_dir / "__pyronaut__" / cli._INSTALL_STATE_FILE).exists())

    def test_test_installs_first_when_dependencies_changed(self):
        self._write_pyproject()
        self._write_installed_state()
        self._write_pyproject("io.example:lib:2.0")

        self.assertEqual(cli.SUCCESS, self._run(["test", "--project-dir", str(self.project_dir)]))

        self.assertEqual(["install", "process", "test"], self._commands())
        self.assertIn("running pyronaut install before test", self.output.getvalue())

    def test_explicit_install_records_state(self):
        self._write_pyproject()

        self.assertEqual(cli.SUCCESS, self._run(["install", "--project-dir", str(self.project_dir)]))

        recorded = json.loads(
            (self.project_dir / "__pyronaut__" / cli._INSTALL_STATE_FILE).read_text(encoding="utf-8")
        )
        self.assertEqual(cli._install_inputs_state(self.project_dir), recorded)

    def test_install_without_manifests_records_nothing(self):
        self._write_pyproject()

        cli._record_install_state(self.project_dir)

        self.assertFalse((self.project_dir / "__pyronaut__").exists())

    def test_external_build_projects_are_not_checked(self):
        self._write_pyproject()
        (self.project_dir / "build.gradle").write_text("", encoding="utf-8")

        self.assertIsNone(cli._stale_install_reason(self.project_dir))

    def test_dev_reinstalls_and_restarts_when_pyproject_dependencies_change(self):
        self._write_pyproject()
        self._write_installed_state()
        events: list[str] = []

        class FakeProcess:
            def __init__(self, exit_after_polls):
                self.exit_after_polls = exit_after_polls
                self.polls = 0
                self.terminated = False

            def poll(self):
                if self.terminated:
                    return 0
                self.polls += 1
                return 0 if self.polls >= self.exit_after_polls else None

            def terminate(self):
                events.append("stop")
                self.terminated = True

            def wait(self, timeout=None):
                return 0

            def kill(self):
                self.terminated = True

        processes = [FakeProcess(exit_after_polls=100), FakeProcess(exit_after_polls=1)]

        def process_runner(command_line, env):
            events.append("start")
            return processes.pop(0)

        ticks = {"count": 0}
        now = {"value": 0.0}

        def sleep(seconds):
            now["value"] += seconds
            ticks["count"] += 1
            if ticks["count"] == 1:
                self._write_pyproject("io.example:lib:2.0")
            if ticks["count"] > 50:
                raise TimeoutError("dev did not restart")

        def recording_delegate(command, args, *rest, **kwargs):
            events.append(command)
            return self._fake_delegate(command, args, *rest, **kwargs)

        with patch.object(cli, "_delegate", side_effect=recording_delegate), \
                patch.object(cli, "_run_lifecycle_validation", return_value=cli.SUCCESS), \
                patch.object(cli, "_build_dev_delegate_invocation", return_value=(["/tmp/pyronaut-dev"], {})), \
                redirect_stdout(self.output), redirect_stderr(self.output):
            exit_code = cli.run(
                ["dev", "--project-dir", str(self.project_dir)],
                process_runner=process_runner,
                resolver=lambda name: f"/tmp/{name}",
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm-jdk",
                watch_poll_interval=0.2,
                watch_debounce_seconds=0.2,
                monotonic=lambda: now["value"],
                sleep=sleep,
            )

        self.assertEqual(cli.SUCCESS, exit_code)
        # The application stops before install renders, so only one writer owns the terminal.
        self.assertEqual(["process", "start", "stop", "install", "process", "start"], events)
        self.assertIn("running pyronaut install before restarting", self.output.getvalue())


if __name__ == "__main__":
    unittest.main()
