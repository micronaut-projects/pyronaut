import tempfile
import unittest
import zipfile
from pathlib import Path

from pyronaut_cli_v2.wheel_audit import WheelAuditError, audit_wheel


class WheelAuditTest(unittest.TestCase):
    def test_rejects_platform_checkout_cache_and_uri_path_leaks(self):
        leaks = (
            ("/Users/builder/work/pyronaut", "/Users/builder/work/pyronaut/build/file.txt"),
            ("/home/builder/.gradle", "/home/builder/.gradle/caches/modules/example.jar"),
            (r"C:\Users\builder\pyronaut", r"C:\Users\builder\pyronaut\build\file.txt"),
            ("/opt/agent/work tree", "file:///opt/agent/work%20tree/build/file.txt"),
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for index, (forbidden, leak) in enumerate(leaks):
                wheel = root / f"leak-{index}.whl"
                with zipfile.ZipFile(wheel, "w") as archive:
                    archive.writestr("package/data.txt", leak)
                with self.assertRaises(WheelAuditError):
                    audit_wheel(wheel, [forbidden])

    def test_rejects_leaks_in_entry_names_and_nested_jars(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            nested = root / "nested.jar"
            with zipfile.ZipFile(nested, "w") as archive:
                archive.writestr("origin.txt", "/Users/builder/.gradle/caches/example.jar")
            wheel = root / "nested.whl"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.write(nested, "package/lib/example.jar")
            with self.assertRaises(WheelAuditError):
                audit_wheel(wheel, ["/Users/builder", "/Users/builder/.gradle"])

            named = root / "named.whl"
            with zipfile.ZipFile(named, "w") as archive:
                archive.writestr("home/builder/work/package.txt", "portable")
            with self.assertRaises(WheelAuditError):
                audit_wheel(named, ["home/builder/work"])

    def test_validates_native_descriptors_and_accepts_portable_wheel(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            invalid = root / "invalid.whl"
            with zipfile.ZipFile(invalid, "w") as archive:
                archive.writestr(
                    "package/native-compile-classpath.txt",
                    "/Users/builder/.gradle/caches/example.jar\n",
                )
            with self.assertRaisesRegex(
                WheelAuditError,
                r"Invalid native classpath descriptor in wheel: package/native-compile-classpath\.txt "
                r"\(expected a Maven coordinate entry, got '/Users/builder/\.gradle/caches/example\.jar'\)",
            ):
                audit_wheel(invalid, ["/unrelated/home"])

            portable = root / "portable.whl"
            with zipfile.ZipFile(portable, "w") as archive:
                archive.writestr(
                    "package/native-compile-classpath.txt",
                    "maven\tio.micronaut\tmicronaut-runtime\t5.0.0\tjar\t\tmicronaut-runtime-5.0.0.jar\n",
                )
                archive.writestr("package/origins.txt", "runtime.jar\t:micronaut-runtime\n")
            audit_wheel(portable, ["/Users/builder", r"C:\Users\builder"])


if __name__ == "__main__":
    unittest.main()
