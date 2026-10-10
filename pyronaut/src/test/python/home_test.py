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
        cleared = {
            home.PYRONAUT_HOME_ENV, home.PYRONAUT_XDG_ENV, home.CONFIG_DIR_ENV, home.CACHE_DIR_ENV,
            home.DATA_DIR_ENV, "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME",
        }
        environ = {key: value for key, value in os.environ.items() if key not in cleared}
        environ["HOME"] = str(self.user_home)
        env_patch = patch.dict(os.environ, environ, clear=True)
        env_patch.start()
        self.addCleanup(env_patch.stop)
        home_patch = patch("pathlib.Path.home", return_value=self.user_home)
        home_patch.start()
        self.addCleanup(home_patch.stop)
        # macOS unless a test opts into Linux, where XDG is the default.
        linux_patch = patch.object(home, "_is_linux", return_value=False)
        self.is_linux = linux_patch.start()
        self.addCleanup(linux_patch.stop)

    def test_defaults_to_dot_pyronaut_under_home(self):
        self.assertEqual(self.user_home / ".pyronaut", home.pyronaut_home())

    def test_pyronaut_home_environment_relocates_state(self):
        state = self.temp / "state"
        os.environ["PYRONAUT_HOME"] = str(state)
        self.assertEqual(state, home.pyronaut_home())
        self.assertEqual(state, home.data_home())
        self.assertEqual(state, home.cache_home())
        self.assertEqual(state, home.config_home())
        self.assertTrue(str(cli._setup_manifest_path()).startswith(str(state / "setup")))
        self.assertEqual(state / "sdks", cli._graalvm_jdks_root())
        self.assertEqual(state / "caches" / "aot", cli._aot_cache.cache_root())

    def test_relative_and_tilde_values_resolve(self):
        with patch("pathlib.Path.cwd", return_value=self.temp):
            self.assertEqual(self.temp / "rel", home.pyronaut_home({"PYRONAUT_HOME": "rel"}))
        self.assertTrue(home.pyronaut_home({"PYRONAUT_HOME": "~/state"}).is_absolute())
        self.assertEqual(self.user_home / ".pyronaut", home.pyronaut_home({"PYRONAUT_HOME": "  "}))

    def test_main_exports_resolved_dirs_to_child_processes(self):
        seen = {}

        def fake_run(argv):
            seen.update({name: os.environ.get(name) for name in (
                "PYRONAUT_HOME", "PYRONAUT_CONFIG_DIR", "PYRONAUT_CACHE_DIR", "PYRONAUT_DATA_DIR",
            )})
            return 0

        with patch.object(cli, "run", side_effect=fake_run), self.assertRaises(SystemExit):
            cli.main()
        legacy = str(self.user_home / ".pyronaut")
        # PYRONAUT_HOME is not invented: it would mask the XDG layout in a nested CLI.
        self.assertIsNone(seen["PYRONAUT_HOME"])
        self.assertEqual(legacy, seen["PYRONAUT_CONFIG_DIR"])
        self.assertEqual(legacy, seen["PYRONAUT_CACHE_DIR"])
        self.assertEqual(legacy, seen["PYRONAUT_DATA_DIR"])

    def test_main_keeps_explicit_pyronaut_home(self):
        state = self.temp / "explicit"
        os.environ["PYRONAUT_HOME"] = str(state)
        with patch.object(cli, "run", return_value=0), self.assertRaises(SystemExit):
            cli.main()
        self.assertEqual(str(state), os.environ["PYRONAUT_HOME"])
        self.assertEqual(str(state), os.environ["PYRONAUT_CACHE_DIR"])

    def test_default_layout_keeps_everything_under_dot_pyronaut(self):
        dirs = home.pyronaut_dirs()
        legacy = self.user_home / ".pyronaut"
        self.assertEqual(home.LAYOUT_HOME, dirs.layout)
        self.assertEqual((legacy, legacy, legacy), (dirs.config, dirs.cache, dirs.data))
        self.assertFalse(home.xdg_enabled())

    def test_fresh_linux_installation_defaults_to_xdg(self):
        self.is_linux.return_value = True
        dirs = home.pyronaut_dirs()
        self.assertEqual(home.LAYOUT_XDG, dirs.layout)
        self.assertEqual(self.user_home / ".config" / "pyronaut", dirs.config)
        self.assertEqual(self.user_home / ".cache" / "pyronaut", dirs.cache)
        self.assertEqual(self.user_home / ".local" / "share" / "pyronaut", dirs.data)

    def test_xdg_base_variable_enables_xdg_by_default_on_any_platform(self):
        os.environ["XDG_CACHE_HOME"] = str(self.temp / "cache")
        dirs = home.pyronaut_dirs()
        self.assertEqual(home.LAYOUT_XDG, dirs.layout)
        self.assertEqual(self.temp / "cache" / "pyronaut", dirs.cache)
        self.assertEqual(self.user_home / ".config" / "pyronaut", dirs.config)

    def test_relative_xdg_base_variable_does_not_enable_xdg(self):
        os.environ["XDG_DATA_HOME"] = "relative"
        self.assertFalse(home.xdg_enabled())

    def test_existing_dot_pyronaut_keeps_its_layout_on_linux(self):
        self.is_linux.return_value = True
        os.environ["XDG_CONFIG_HOME"] = str(self.temp / "cfg")
        (self.user_home / ".pyronaut").mkdir(parents=True)
        self.assertEqual(home.LAYOUT_HOME, home.pyronaut_dirs().layout)
        os.environ["PYRONAUT_XDG"] = "true"
        self.assertEqual(home.LAYOUT_XDG, home.pyronaut_dirs().layout)

    def test_pyronaut_xdg_false_keeps_dot_pyronaut_on_linux(self):
        self.is_linux.return_value = True
        os.environ["PYRONAUT_XDG"] = "false"
        self.assertEqual(self.user_home / ".pyronaut", home.data_home())

    def test_setup_pins_the_default_xdg_layout(self):
        self.is_linux.return_value = True
        with patch.object(cli, "_setup_manifest_path", side_effect=RuntimeError("stop")), \
                patch.object(cli, "_progress_console"), patch("builtins.print"):
            cli._run_setup([], lambda command, env: 0)
        self.assertTrue((self.user_home / ".config" / "pyronaut").is_dir())
        # A ~/.pyronaut created afterwards no longer switches the layout.
        (self.user_home / ".pyronaut").mkdir()
        self.assertEqual(home.LAYOUT_XDG, home.pyronaut_dirs().layout)

    def test_xdg_config_folder_enables_split_layout(self):
        (self.user_home / ".config" / "pyronaut").mkdir(parents=True)
        self.assertTrue(home.xdg_enabled())
        self.assertEqual(self.user_home / ".config" / "pyronaut", home.config_home())
        self.assertEqual(self.user_home / ".cache" / "pyronaut", home.cache_home())
        self.assertEqual(self.user_home / ".local" / "share" / "pyronaut", home.data_home())
        self.assertEqual(self.user_home / ".config" / "pyronaut" / "settings.toml", cli._settings_path())
        self.assertEqual(self.user_home / ".local" / "share" / "pyronaut" / "sdks", cli._graalvm_jdks_root())
        self.assertTrue(str(cli._setup_manifest_path()).startswith(str(self.user_home / ".local" / "share" / "pyronaut" / "setup")))
        self.assertEqual(self.user_home / ".cache" / "pyronaut" / "caches" / "aot", cli._aot_cache.cache_root())
        self.assertTrue(str(cli._graalpy_compatibility_cache("25.0")).startswith(str(self.user_home / ".cache" / "pyronaut")))

    def test_xdg_base_directories_are_honoured_and_relative_values_ignored(self):
        config = self.temp / "cfg"
        (config / "pyronaut").mkdir(parents=True)
        os.environ.update({
            "XDG_CONFIG_HOME": str(config),
            "XDG_CACHE_HOME": str(self.temp / "cache"),
            "XDG_DATA_HOME": "relative/data",
        })
        dirs = home.pyronaut_dirs()
        self.assertEqual(home.LAYOUT_XDG, dirs.layout)
        self.assertEqual(config / "pyronaut", dirs.config)
        self.assertEqual(self.temp / "cache" / "pyronaut", dirs.cache)
        self.assertEqual(self.user_home / ".local" / "share" / "pyronaut", dirs.data)

    def test_xdg_environment_flag_overrides_the_marker(self):
        os.environ["PYRONAUT_XDG"] = "true"
        self.assertTrue(home.xdg_enabled())
        (self.user_home / ".config" / "pyronaut").mkdir(parents=True)
        os.environ["PYRONAUT_XDG"] = "false"
        self.assertFalse(home.xdg_enabled())
        self.assertEqual(self.user_home / ".pyronaut", home.config_home())

    def test_pyronaut_home_overrides_xdg(self):
        (self.user_home / ".config" / "pyronaut").mkdir(parents=True)
        os.environ["PYRONAUT_XDG"] = "true"
        os.environ["PYRONAUT_HOME"] = str(self.temp / "state")
        dirs = home.pyronaut_dirs()
        self.assertEqual(home.LAYOUT_CUSTOM, dirs.layout)
        self.assertEqual(self.temp / "state", dirs.cache)

    def test_setup_xdg_creates_marker_copies_settings_and_reexports(self):
        legacy = self.user_home / ".pyronaut"
        legacy.mkdir(parents=True)
        (legacy / "settings.toml").write_text("[proxy]\nurl = \"http://proxy:3128\"\n", encoding="utf-8")
        cli._export_pyronaut_home()
        self.assertEqual(str(legacy), os.environ["PYRONAUT_DATA_DIR"])

        with patch("builtins.print") as printed:
            cli._enable_xdg_layout()
        output = "\n".join(" ".join(str(arg) for arg in call.args) for call in printed.call_args_list)

        config = self.user_home / ".config" / "pyronaut"
        self.assertTrue(config.is_dir())
        self.assertEqual(
            "[proxy]\nurl = \"http://proxy:3128\"\n", (config / "settings.toml").read_text(encoding="utf-8")
        )
        self.assertEqual(str(config), os.environ["PYRONAUT_CONFIG_DIR"])
        self.assertEqual(str(self.user_home / ".cache" / "pyronaut"), os.environ["PYRONAUT_CACHE_DIR"])
        self.assertEqual(str(self.user_home / ".local" / "share" / "pyronaut"), os.environ["PYRONAUT_DATA_DIR"])
        self.assertNotIn("PYRONAUT_HOME", os.environ)
        self.assertIn("~/.pyronaut is no longer used", output)
        self.assertIn("set PYRONAUT_XDG=false", output)

        # An existing XDG settings file is never overwritten.
        (legacy / "settings.toml").write_text("changed", encoding="utf-8")
        with patch("builtins.print"):
            cli._enable_xdg_layout()
        self.assertIn("proxy:3128", (config / "settings.toml").read_text(encoding="utf-8"))

    def test_setup_xdg_rejects_pyronaut_home_and_disabled_flag(self):
        os.environ["PYRONAUT_HOME"] = str(self.temp / "state")
        with self.assertRaisesRegex(RuntimeError, "PYRONAUT_HOME"):
            cli._enable_xdg_layout()
        del os.environ["PYRONAUT_HOME"]
        os.environ["PYRONAUT_XDG"] = "off"
        with self.assertRaisesRegex(RuntimeError, "PYRONAUT_XDG"):
            cli._enable_xdg_layout()
        self.assertFalse((self.user_home / ".config" / "pyronaut").exists())

    def test_setup_accepts_xdg_flag_and_documents_it(self):
        cli._validate_setup_arguments(["--xdg", "--offline"])
        import io

        stream = io.StringIO()
        cli._print_setup_usage(stream)
        self.assertIn("--xdg", stream.getvalue())
        self.assertIn("State: ~/.pyronaut", stream.getvalue())

    def test_doctor_reports_active_layout(self):
        result = cli._doctor_check_pyronaut_dirs()
        self.assertEqual("pass", result.status.lower())
        self.assertEqual("~/.pyronaut", result.detail)
        self.is_linux.return_value = True
        (self.user_home / ".pyronaut").mkdir(parents=True)
        result = cli._doctor_check_pyronaut_dirs()
        self.assertEqual("pass", result.status.lower())
        self.assertIn("run pyronaut setup --xdg", result.detail)
        self.is_linux.return_value = False
        (self.user_home / ".config" / "pyronaut").mkdir(parents=True)
        result = cli._doctor_check_pyronaut_dirs()
        self.assertEqual("xdg", result.data["layout"])
        self.assertIn("XDG layout", result.detail)
        self.assertIn("~/.pyronaut is no longer used", result.detail)


if __name__ == "__main__":
    unittest.main()
