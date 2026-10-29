import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pyronaut_cli_v2 import cli
from pyronaut_cli_v2.cli import _validated_native_compile_descriptor
from pyronaut_cli_v2.wheel_audit import _validate_native_descriptor


class NativeClasspathTest(unittest.TestCase):
    def test_checkout_resolves_timestamped_descriptors_from_normalized_maven_cache(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            launcher = root / "native" / "bin" / "pyronaut-dev"
            launcher.parent.mkdir(parents=True)
            launcher.touch()
            entry = "\t".join(("maven", "example", "library", "1.2-SNAPSHOT", "jar", "tests",
                               "library-1.2-20261029.120000-1-tests.jar"))
            (launcher.parent / "native-compile-classpath.txt").write_text(entry + "\n", encoding="utf-8")
            repository = root / "repository"
            normalized = repository / "example" / "library" / "1.2-SNAPSHOT" / "library-1.2-SNAPSHOT-tests.jar"
            normalized.parent.mkdir(parents=True)
            normalized.write_text("snapshot", encoding="utf-8")

            with patch.object(cli, "_setup_is_required", return_value=False):
                self.assertEqual([str(normalized.resolve())],
                                 cli._native_launcher_compile_classpath_entries(str(launcher), str(repository)))

    def test_accepts_release_and_snapshot_filenames_with_classifiers(self):
        cases = (
            ("1.2", "", "library-1.2.jar"),
            ("1.2-SNAPSHOT", "", "library-1.2-SNAPSHOT.jar"),
            ("1.2-SNAPSHOT", "", "library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "sources", "library-1.2-20261029.120000-12-sources.jar"),
        )
        for version, classifier, filename in cases:
            entry = "\t".join(("maven", "example", "library", version, "jar", classifier, filename))
            with self.subTest(entry=entry):
                self.assertEqual([entry], _validated_native_compile_descriptor("probe", [entry]))
                _validate_native_descriptor("probe", entry.encode())

    def test_rejects_unrelated_and_unsafe_snapshot_filenames(self):
        cases = (
            ("1.2", "", "library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", "other-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", "library-1.3-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", "library-1.2-20261029X120000-1.jar"),
            ("1.2-SNAPSHOT", "", "library-1.2-20261029.120000-1-sources.jar"),
            ("1.2-SNAPSHOT", "sources", "library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", "library-1.2-20261029.120000-1.jar.extra"),
            ("1.2-SNAPSHOT", "", "../library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", "/tmp/library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "", r"C:\temp\library-1.2-20261029.120000-1.jar"),
            ("1.2-SNAPSHOT", "../sources", "library-1.2-20261029.120000-1-../sources.jar"),
        )
        for version, classifier, filename in cases:
            entry = "\t".join(("maven", "example", "library", version, "jar", classifier, filename))
            with self.subTest(entry=entry):
                with self.assertRaises(RuntimeError):
                    _validated_native_compile_descriptor("probe", [entry])
                with self.assertRaises(RuntimeError):
                    _validate_native_descriptor("probe", entry.encode())
