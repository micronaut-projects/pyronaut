import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pyronaut_cli_v2 import cli
from pyronaut_cli_v2.cli import _validated_native_compile_descriptor
from pyronaut_cli_v2.wheel_audit import _validate_native_descriptor


class NativeClasspathTest(unittest.TestCase):
    def _report(self, root, entries, groups=("org.graalvm.*",)):
        settings = root / "settings.toml"
        lock = root / "bundle.lock"
        lock_text = "version = 1\n"
        for group, artifact, version, extension, classifier, file_path, coordinates_version in entries:
            digest = hashlib.sha256(file_path.read_bytes()).hexdigest()
            coordinates = f"{group}:{artifact}:{extension}:"
            if classifier:
                coordinates += f"{classifier}:"
            coordinates += coordinates_version
            trusted_path = cli._maven_layout_path(coordinates)
            lock_text += (
                "\n[[artifact]]\n"
                f'coordinates = "{coordinates}"\npath = "{trusted_path}"\nsha256 = "{digest}"\n'
            )
        lock.write_text(lock_text, encoding="utf-8")
        settings.write_text(
            '[maven.verification]\nlock-file = "bundle.lock"\n'
            + "groups = [" + ", ".join(json.dumps(group) for group in groups) + "]\n",
            encoding="utf-8",
        )
        report = root / "verified-maven-artifacts.json"
        report.write_text(
            json.dumps(
                {
                    "version": 1,
                    "mavenSettingsSha256": "PLACEHOLDER",
                    "artifacts": [
                        {
                            "groupId": group,
                            "path": cli._maven_layout_path(
                                f"{group}:{artifact}:{extension}:"
                                + (f"{classifier}:" if classifier else "")
                                + coordinates_version
                            ),
                            "file": str(file_path.absolute()),
                        }
                        for group, artifact, version, extension, classifier, file_path, coordinates_version in entries
                    ],
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        with patch.object(cli, "_settings_path", return_value=settings):
            parsed = json.loads(report.read_text(encoding="utf-8"))
            parsed["mavenSettingsSha256"] = cli._maven_settings_fingerprint()
            report.write_text(json.dumps(parsed), encoding="utf-8")
        return settings

    def test_verification_allows_unselected_artifact_when_cache_root_contains_selected_group(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            artifact = root / "org" / "graalvm" / "cache" / "com" / "example" / "unknown" / "1.0" / "unknown-1.0.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"unselected release")
            settings = self._report(
                root,
                [("com.example", "unknown", "1.0", "jar", "", artifact, "1.0")],
            )
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(artifact) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                self.assertEqual([str(artifact)], cli._read_manifest_entries(manifest))

    def test_verification_rejects_missing_selected_classpath_entry(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            artifact = root / "repository/org/graalvm/example/language/1.0/language-1.0.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"selected release")
            settings = self._report(root, [])
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(artifact) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                with self.assertRaisesRegex(RuntimeError, "absent from"):
                    cli._read_manifest_entries(manifest)

    def test_verification_rejects_invalid_policies_and_format_versions(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = root / "settings.toml"
            lock = root / "bundle.lock"
            lock.write_text("version = 1\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                for policy in ('lock-file="bundle.lock"\ngroups=[]', 'lock-file="bundle.lock"\ngroups=[1]',
                               'lock-file="bundle.lock"\ngroups=["org.*.invalid"]', 'groups=["org.graalvm.*"]'):
                    settings.write_text("[maven.verification]\n" + policy, encoding="utf-8")
                    with self.assertRaises(RuntimeError):
                        cli._maven_verification()
                settings.write_text('[maven.verification]\nlock-file="bundle.lock"\ngroups=["org.graalvm.*"]\n', encoding="utf-8")
                for version in ("true", "1.0", "2"):
                    lock.write_text(f"version = {version}\n", encoding="utf-8")
                    with self.assertRaisesRegex(RuntimeError, "format version"):
                        cli._maven_verification()

    def test_verification_checks_warmed_artifacts_without_using_repository_origin(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = root / "settings.toml"
            lock = root / "bundle.lock"
            repository = root / "repository"
            artifact = repository / "org" / "graalvm" / "example" / "language" / "1.0" / "language-1.0.jar"
            artifact.parent.mkdir(parents=True)
            source = root / "source-built.jar"
            source.write_bytes(b"source-built")
            artifact.symlink_to(source)
            settings = self._report(root, [("org.graalvm.example", "language", "1.0", "jar", "", artifact, "1.0")])
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(artifact) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings), \
                    patch.object(cli, "_setup_local_repository", return_value=repository):
                cli._read_manifest_entries(manifest)
                source.write_bytes(b"another release with the same coordinate")
                with self.assertRaisesRegex(RuntimeError, "expected SHA-256"):
                    cli._read_manifest_entries(manifest)

    def test_verification_checks_selected_pom_even_when_manifest_contains_only_jar(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            jar = root / "repository/org/graalvm/example/language/1.0/language-1.0.jar"
            pom = root / "repository/org/graalvm/example/language/1.0/language-1.0.pom"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"jar")
            pom.write_bytes(b"pom")
            settings = self._report(
                root,
                [
                    ("org.graalvm.example", "language", "1.0", "jar", "", jar, "1.0"),
                    ("org.graalvm.example", "language", "1.0", "pom", "", pom, "1.0"),
                ],
            )
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(jar) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                cli._read_manifest_entries(manifest)
                pom.write_bytes(b"tampered pom")
                with self.assertRaisesRegex(RuntimeError, "expected SHA-256"):
                    cli._read_manifest_entries(manifest)

    def test_verification_requires_current_report(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            artifact = root / "repository/org/graalvm/example/language/1.0/language-1.0.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"jar")
            settings = self._report(root, [("org.graalvm.example", "language", "1.0", "jar", "", artifact, "1.0")])
            report = root / "verified-maven-artifacts.json"
            report.unlink()
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(artifact) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                with self.assertRaisesRegex(RuntimeError, "missing or stale"):
                    cli._read_manifest_entries(manifest)
                self._report(root, [("org.graalvm.example", "language", "1.0", "jar", "", artifact, "1.0")])
                settings.write_text(settings.read_text(encoding="utf-8") + "# changed\n", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "missing or stale"):
                    cli._read_manifest_entries(manifest)

    def test_verification_rejects_mutated_native_descriptor_classpath(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = root / "settings.toml"
            lock = root / "bundle.lock"
            artifact = root / "cached.jar"
            artifact.write_bytes(b"source-built")
            checksum = hashlib.sha256(artifact.read_bytes()).hexdigest()
            lock.write_text(
                "version = 1\n\n[[artifact]]\n"
                'coordinates = "org.graalvm.example:language:jar:1.0"\n'
                'path = "org/graalvm/example/language/1.0/language-1.0.jar"\n'
                f'sha256 = "{checksum}"\n',
                encoding="utf-8",
            )
            settings.write_text(
                '[maven.verification]\nlock-file = "bundle.lock"\ngroups = ["org.graalvm.*"]\n',
                encoding="utf-8",
            )
            entry = "\t".join(("maven", "org.graalvm.example", "language", "1.0", "jar", "", "language-1.0.jar"))
            with patch.object(cli, "_settings_path", return_value=settings):
                cli._verify_descriptor_paths([entry], [artifact])
                artifact.write_bytes(b"tampered")
                with self.assertRaisesRegex(RuntimeError, "expected SHA-256"):
                    cli._verify_descriptor_paths([entry], [artifact])

    def test_verification_manifest_is_part_of_maven_settings_fingerprint(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = root / "settings.toml"
            lock = root / "bundle.lock"
            lock_contents = "version = 1\n"
            lock.write_text(lock_contents, encoding="utf-8")
            settings_contents = '[maven.verification]\nlock-file = "bundle.lock"\ngroups = ["org.graalvm.*"]\n'
            settings.write_text(settings_contents, encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                expected = hashlib.sha256(
                    str(settings.parent.resolve()).encode("utf-8")
                    + b"\0"
                    + settings_contents.encode("utf-8")
                    + b"\0"
                    + lock_contents.encode("utf-8")
                ).hexdigest()
                self.assertEqual(expected, cli._maven_settings_fingerprint())
                lock.write_text(lock_contents + "# changed\n", encoding="utf-8")
                self.assertNotEqual(expected, cli._maven_settings_fingerprint())

    def test_verification_matches_normalized_snapshot_path_to_trusted_timestamp(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = root / "settings.toml"
            artifact = root / "cached.jar"
            artifact.write_bytes(b"source-built snapshot")
            settings = self._report(root, [("org.graalvm.example", "language", "1.2-SNAPSHOT", "jar", "", artifact, "1.2-20261029.120000-1")])
            entry = "\t".join(("maven", "org.graalvm.example", "language", "1.2-SNAPSHOT", "jar", "", "language-1.2-SNAPSHOT.jar"))
            normalized = root / "repository/org/graalvm/example/language/1.2-SNAPSHOT/language-1.2-SNAPSHOT.jar"
            normalized.parent.mkdir(parents=True)
            normalized.symlink_to(artifact)
            report = root / "verified-maven-artifacts.json"
            parsed = json.loads(report.read_text(encoding="utf-8"))
            parsed["artifacts"][0]["file"] = str(normalized.absolute())
            report.write_text(json.dumps(parsed), encoding="utf-8")
            manifest = root / "resolved-runtime-dependencies"
            manifest.write_text(str(normalized) + "\n", encoding="utf-8")
            with patch.object(cli, "_settings_path", return_value=settings):
                cli._verify_descriptor_paths([entry], [artifact])
                cli._read_manifest_entries(manifest)

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
