import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

_CLI_MODULE_PATH = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2" / "cli.py"
_CLI_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.cli", _CLI_MODULE_PATH)
if _CLI_SPEC is None or _CLI_SPEC.loader is None:
    raise RuntimeError("Failed loading pyronaut_cli_v2.cli for tests")
cli = importlib.util.module_from_spec(_CLI_SPEC)
_CLI_SPEC.loader.exec_module(cli)


class OrchestratorTest(unittest.TestCase):
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
            self.assertEqual(
                [
                    ["/tmp/pyronaut-install", "--project-dir", str(project_dir)],
                    ["/tmp/pyronaut-processor", "--project-dir", str(project_dir)],
                    ["/tmp/pyronaut-run", "--project-dir", str(project_dir), "--main-class", "example.Main"],
                ],
                executed,
            )

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
            self.assertEqual(
                [["/tmp/pyronaut-run", "--project-dir", str(project_dir), "--main-class", "example.Main"]],
                executed,
            )

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
            self.assertEqual(1, len(executed))
            self.assertIn("pyronaut-install", executed[0][0])

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
        self.assertEqual(
            [
                ["/tmp/pyronaut-processor", "--project-dir", str(project_dir)],
                ["/tmp/pyronaut-test", "--project-dir", str(project_dir)],
            ],
            executed,
        )

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
        self.assertEqual([["/tmp/pyronaut-test", "--project-dir", str(project_dir)]], executed)

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
