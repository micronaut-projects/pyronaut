import importlib.util
import os
import shutil
import sys
import tempfile
import types
import unittest
import zipfile
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

_VFS = "META-INF/GRAALPY-VFS/micronaut-application"


class EmbeddedPythonVfsFilterTest(unittest.TestCase):
    def setUp(self):
        root = Path(tempfile.mkdtemp(prefix="pyronaut-vfs-filter-test-")).resolve()
        self.addCleanup(shutil.rmtree, root, True)
        launcher_bin = root / "launcher" / "bin"
        launcher_bin.mkdir(parents=True)
        self.launcher = launcher_bin / "pyronaut-run-python"
        self.launcher.write_text("", encoding="utf-8")
        launcher_lib = root / "launcher" / "lib"
        launcher_lib.mkdir()
        with zipfile.ZipFile(launcher_lib / "micronaut-python-vfs-1.0.0.jar", "w") as archive:
            archive.writestr(f"{_VFS}/venv/lib/site.py", "embedded")
        self.project = root / "app"
        self.classes = self.project / "__pyronaut__" / "classes"
        self.vfs = self.classes / _VFS

    def _process(self, app_source: str, files: list[str]) -> None:
        """Simulate a pyronaut process run writing the application classes."""
        (self.vfs / "src" / "app").mkdir(parents=True, exist_ok=True)
        (self.vfs / "venv" / "lib").mkdir(parents=True, exist_ok=True)
        (self.vfs / "venv" / "lib" / "site.py").write_text("embedded", encoding="utf-8")
        for name in list((self.vfs / "src" / "app").iterdir()):
            name.unlink()
        for name in files:
            (self.vfs / "src" / "app" / name).write_text(app_source, encoding="utf-8")
        (self.vfs / "fileslist.txt").write_text(
            "".join(f"/{_VFS}/{entry}\n" for entry in ["venv/lib/site.py", *(f"src/app/{n}" for n in files)]),
            encoding="utf-8",
        )

    def _filtered_classes(self) -> Path:
        entries = cli._replace_embedded_python_vfs_classes(  # noqa: SLF001
            [str(self.classes), "/other.jar"], self.project, str(self.launcher)
        )
        self.assertEqual("/other.jar", entries[1])
        filtered = Path(entries[0])
        self.assertNotEqual(self.classes.resolve(), filtered)
        self.assertTrue(filtered.name.startswith("classes-native-runtime-"))
        return filtered

    def _native_runtime_dirs(self) -> list[Path]:
        return sorted((self.project / "__pyronaut__").glob("classes-native-runtime-*"))

    def test_filters_embedded_vfs_entries(self):
        self._process("print('v1')", ["main.py"])

        filtered = self._filtered_classes()

        self.assertFalse((filtered / _VFS / "venv" / "lib" / "site.py").exists())
        self.assertEqual("print('v1')", (filtered / _VFS / "src" / "app" / "main.py").read_text(encoding="utf-8"))
        self.assertEqual(
            f"/{_VFS}/src/app/main.py\n", (filtered / _VFS / "fileslist.txt").read_text(encoding="utf-8")
        )
        # The source classes directory is left untouched.
        self.assertTrue((self.vfs / "venv" / "lib" / "site.py").exists())

    def test_reprocessed_classes_replace_stale_filtered_copy(self):
        self._process("print('v1')", ["main.py"])
        self._filtered_classes()

        self._process("print('version two')", ["main.py", "extra.py"])
        filtered = self._filtered_classes()

        self.assertEqual(
            "print('version two')", (filtered / _VFS / "src" / "app" / "main.py").read_text(encoding="utf-8")
        )
        self.assertTrue((filtered / _VFS / "src" / "app" / "extra.py").is_file())
        self.assertIn(f"/{_VFS}/src/app/extra.py\n", (filtered / _VFS / "fileslist.txt").read_text(encoding="utf-8"))
        self.assertEqual([filtered], self._native_runtime_dirs())

    def test_same_size_rewrite_is_detected(self):
        self._process("print('aa')", ["main.py"])
        self._filtered_classes()

        self._process("print('bb')", ["main.py"])
        main = self.vfs / "src" / "app" / "main.py"
        stat = main.stat()
        os.utime(main, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))
        filtered = self._filtered_classes()

        self.assertEqual("print('bb')", (filtered / _VFS / "src" / "app" / "main.py").read_text(encoding="utf-8"))

    def test_unchanged_classes_reuse_filtered_copy(self):
        self._process("print('v1')", ["main.py"])
        first = self._filtered_classes()
        sentinel = first / "reused"
        sentinel.write_text("", encoding="utf-8")

        second = self._filtered_classes()

        self.assertEqual(first, second)
        self.assertTrue(sentinel.exists())

    def test_copy_without_matching_marker_is_rebuilt(self):
        self._process("print('v1')", ["main.py"])
        filtered = self._filtered_classes()
        (filtered / ".native-vfs-filter").write_text("stale", encoding="utf-8")
        (filtered / _VFS / "src" / "app" / "main.py").write_text("stale", encoding="utf-8")

        rebuilt = self._filtered_classes()

        self.assertEqual("print('v1')", (rebuilt / _VFS / "src" / "app" / "main.py").read_text(encoding="utf-8"))

    def test_prunes_obsolete_native_runtime_copies(self):
        old = self.project / "__pyronaut__" / "classes-native-runtime-0123456789ab"
        (old / "x").mkdir(parents=True)
        self._process("print('v1')", ["main.py"])

        filtered = self._filtered_classes()

        self.assertEqual([filtered], self._native_runtime_dirs())


if __name__ == "__main__":
    unittest.main()
