import importlib.util
import os
import sys
import tempfile
import types
import unittest
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

from pyronaut_cli_v2 import home  # noqa: E402


class PyronautHomeTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="pyronaut-home-test-")).resolve()
        self.user_home = self.temp / "fresh-home"
        environ = {key: value for key, value in os.environ.items() if key != home.PYRONAUT_HOME_ENV}
        environ["HOME"] = str(self.user_home)
        env_patch = patch.dict(os.environ, environ, clear=True)
        env_patch.start()
        self.addCleanup(env_patch.stop)
        home_patch = patch("pathlib.Path.home", return_value=self.user_home)
        home_patch.start()
        self.addCleanup(home_patch.stop)

    def test_defaults_to_dot_pyronaut_under_home(self):
        self.assertEqual(self.user_home / ".pyronaut", home.pyronaut_home())

    def test_pyronaut_home_environment_relocates_state(self):
        state = self.temp / "state"
        os.environ["PYRONAUT_HOME"] = str(state)
        self.assertEqual(state, home.pyronaut_home())
        self.assertEqual(state / "tools" / "1.0" / "current", cli._pyronaut_home() / "tools" / "1.0" / "current")
        self.assertTrue(str(cli._setup_manifest_path()).startswith(str(state / "setup")))
        self.assertEqual(state / "sdks", cli._graalvm_jdks_root())
        self.assertEqual(state / "caches" / "aot", cli._aot_cache.cache_root())

    def test_relative_and_tilde_values_resolve(self):
        with patch("pathlib.Path.cwd", return_value=self.temp):
            self.assertEqual(self.temp / "rel", home.pyronaut_home({"PYRONAUT_HOME": "rel"}))
        self.assertTrue(home.pyronaut_home({"PYRONAUT_HOME": "~/state"}).is_absolute())
        self.assertEqual(self.user_home / ".pyronaut", home.pyronaut_home({"PYRONAUT_HOME": "  "}))

    def test_main_exports_resolved_home_to_child_processes(self):
        seen = {}

        def fake_run(argv):
            seen["home"] = os.environ.get("PYRONAUT_HOME")
            return 0

        with patch.object(cli, "run", side_effect=fake_run), self.assertRaises(SystemExit):
            cli.main()
        self.assertEqual(str(self.user_home / ".pyronaut"), seen["home"])

    def test_main_keeps_explicit_pyronaut_home(self):
        state = self.temp / "explicit"
        os.environ["PYRONAUT_HOME"] = str(state)
        with patch.object(cli, "run", return_value=0), self.assertRaises(SystemExit):
            cli.main()
        self.assertEqual(str(state), os.environ["PYRONAUT_HOME"])


if __name__ == "__main__":
    unittest.main()
