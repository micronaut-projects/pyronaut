import importlib.util
import io
import shutil
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

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


def _touch(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("x", encoding="utf-8")
    return path


class CleanCommandTest(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="pyronaut-clean-test-")).resolve()
        self.addCleanup(shutil.rmtree, self.project, True)

    def _run(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = cli.run(["clean", "--project-dir", str(self.project), *args])
        return code, stdout.getvalue(), stderr.getvalue()

    def _populate(self, output_dir: Path) -> None:
        for name in ("classes/a.class", "test-classes/b.class", "test-sources/c.py",
                     "incremental/main/state", "processor.inputs",
                     "classes-native-runtime-0123456789ab/d.class"):
            _touch(output_dir / name)
        for name in ("resolved-build-dependencies", "resolved-runtime-dependencies",
                     "resolved-test-dependencies", "m2-repository/x.jar", "schemas/s.json",
                     "daemon/daemon.lock", "reports/tests/r.xml"):
            _touch(output_dir / name)
        _touch(self.project / ".venv" / "pyvenv.cfg")

    def test_default_removes_only_process_outputs(self):
        _touch(self.project / "pyproject.toml")
        output = self.project / "__pyronaut__"
        self._populate(output)

        code, _, stderr = self._run()

        self.assertEqual(cli.SUCCESS, code)
        for name in ("classes", "test-classes", "test-sources", "incremental", "processor.inputs",
                     "classes-native-runtime-0123456789ab"):
            self.assertFalse((output / name).exists(), name)
        for name in ("resolved-build-dependencies", "resolved-runtime-dependencies",
                     "resolved-test-dependencies", "m2-repository", "schemas", "daemon", "reports"):
            self.assertTrue((output / name).exists(), name)
        self.assertTrue((self.project / ".venv").is_dir())
        self.assertIn("Removed __pyronaut__/classes", stderr)
        self.assertNotIn("pyronaut install", stderr)

    def test_full_removes_generated_state_and_venv(self):
        _touch(self.project / "pyproject.toml")
        self._populate(self.project / "__pyronaut__")

        code, _, stderr = self._run("--full")

        self.assertEqual(cli.SUCCESS, code)
        self.assertFalse((self.project / "__pyronaut__").exists())
        self.assertFalse((self.project / ".venv").exists())
        self.assertTrue((self.project / "pyproject.toml").is_file())
        self.assertIn("pyronaut install", stderr)

    def test_short_full_flag(self):
        _touch(self.project / "pyproject.toml")
        _touch(self.project / ".venv" / "pyvenv.cfg")

        code, _, _ = self._run("-f")

        self.assertEqual(cli.SUCCESS, code)
        self.assertFalse((self.project / ".venv").exists())

    def test_full_unlinks_symlinked_venv_without_touching_target(self):
        _touch(self.project / "pyproject.toml")
        target = Path(tempfile.mkdtemp(prefix="pyronaut-clean-venv-")).resolve()
        self.addCleanup(shutil.rmtree, target, True)
        _touch(target / "pyvenv.cfg")
        (self.project / ".venv").symlink_to(target, target_is_directory=True)

        code, _, _ = self._run("--full")

        self.assertEqual(cli.SUCCESS, code)
        self.assertFalse((self.project / ".venv").is_symlink())
        self.assertTrue((target / "pyvenv.cfg").is_file())

    def test_maven_project_cleans_target_pyronaut(self):
        _touch(self.project / "pom.xml")
        output = self.project / "target" / "pyronaut"
        self._populate(output)
        _touch(self.project / "target" / "app.jar")

        code, _, _ = self._run()

        self.assertEqual(cli.SUCCESS, code)
        self.assertFalse((output / "classes").exists())
        self.assertTrue((output / "resolved-runtime-dependencies").exists())

        code, _, _ = self._run("--full")

        self.assertEqual(cli.SUCCESS, code)
        self.assertFalse(output.exists())
        self.assertTrue((self.project / "target" / "app.jar").is_file())

    def test_nothing_to_clean(self):
        _touch(self.project / "pyproject.toml")

        code, _, stderr = self._run()

        self.assertEqual(cli.SUCCESS, code)
        self.assertIn("Nothing to clean", stderr)

    def test_refuses_directory_without_project_descriptor(self):
        _touch(self.project / ".venv" / "pyvenv.cfg")

        code, _, stderr = self._run("--full")

        self.assertEqual(cli.PRECONDITION_FAILED, code)
        self.assertIn("not a Pyronaut project", stderr)
        self.assertTrue((self.project / ".venv").is_dir())

    def test_unknown_option_is_usage_error(self):
        _touch(self.project / "pyproject.toml")

        code, _, stderr = self._run("--bogus")

        self.assertEqual(cli.USAGE_ERROR, code)
        self.assertIn("Unknown pyronaut clean option: --bogus", stderr)

    def test_help(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = cli.run(["clean", "--help"])
        self.assertEqual(cli.SUCCESS, code)
        self.assertIn("Usage: pyronaut clean", stdout.getvalue())
        self.assertIn("--full", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
