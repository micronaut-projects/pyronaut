import importlib.util
import os
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

_PACKAGE_DIR = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2"
if "pyronaut_cli_v2" not in sys.modules:
    _package = types.ModuleType("pyronaut_cli_v2")
    _package.__path__ = [str(_PACKAGE_DIR)]
    sys.modules["pyronaut_cli_v2"] = _package
_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.aot_cache", _PACKAGE_DIR / "aot_cache.py")
aot_cache = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = aot_cache
_SPEC.loader.exec_module(aot_cache)


class AotCacheTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.cache_root = self.root / "caches"
        self.project_dir = self.root / "project"
        self.project_dir.mkdir()
        self.jar = self.root / "lib" / "app.jar"
        self.jar.parent.mkdir()
        self.jar.write_bytes(b"jar")
        self.java = self._jdk("jdk", "25.0.1")

    def tearDown(self):
        self._temp.cleanup()

    def _jdk(self, name, version):
        java_home = self.root / name
        (java_home / "bin").mkdir(parents=True)
        java = java_home / "bin" / "java"
        java.write_text("", encoding="utf-8")
        (java_home / "release").write_text(f'JAVA_VERSION="{version}"\n', encoding="utf-8")
        return java

    def _java_command(self, java=None, *jvm_options):
        return [str(java or self.java), "--enable-native-access=ALL-UNNAMED", *jvm_options, "-cp", str(self.jar), "example.Main", "--project-dir", "."]

    def _prepare(self, command_line, env=None):
        return aot_cache.prepare(command_line, env, project_dir=self.project_dir, root=self.cache_root)

    def _launcher_script(self, name="pyronaut-processor", content=None):
        script = self.root / "tools" / name / "bin" / name
        script.parent.mkdir(parents=True)
        script.write_text(
            content or "#!/bin/sh\nCLASSPATH=$APP_HOME/../shared/lib/app.jar\nexec \"$JAVACMD\" \"$@\"\n",
            encoding="utf-8",
        )
        return script

    def test_first_direct_java_launch_records_a_training_configuration(self):
        env = {"PATH": "/usr/bin"}

        launch = self._prepare(self._java_command(), env)

        self.assertIs(env, launch.env)
        self.assertEqual(str(self.java), launch.command_line[0])
        self.assertIn("-XX:AOTMode=record", launch.command_line)
        self.assertIn("-Xlog:aot*=off,cds*=off", launch.command_line)
        configuration = next(value for value in launch.command_line if value.startswith("-XX:AOTConfiguration="))
        self.assertLess(launch.command_line.index(configuration), launch.command_line.index("-cp"))
        self.assertEqual(["example.Main", "--project-dir", "."], launch.command_line[-3:])
        self.assertIsNotNone(launch.training)
        create = launch.training.create_command
        self.assertEqual(str(self.java), create[0])
        self.assertIn("-XX:AOTMode=create", create)
        self.assertIn("-XX:AOTCache={cache}", create)
        self.assertEqual(["-cp", str(self.jar)], create[-2:])
        self.assertEqual(launch.training.configuration, Path(configuration.split("=", 1)[1]))

    def test_launch_uses_the_cache_once_it_exists(self):
        training = self._prepare(self._java_command()).training
        training.cache.write_bytes(b"cache")

        launch = self._prepare(self._java_command())

        self.assertIsNone(launch.training)
        self.assertIn(f"-XX:AOTCache={training.cache}", launch.command_line)
        self.assertNotIn("-XX:AOTMode=record", launch.command_line)

    def test_changed_class_path_jar_trains_a_new_cache_and_removes_the_old_one(self):
        old = self._prepare(self._java_command()).training.cache
        old.write_bytes(b"cache")
        self.jar.write_bytes(b"a changed jar")

        launch = self._prepare(self._java_command())

        self.assertIsNotNone(launch.training)
        self.assertNotEqual(old, launch.training.cache)
        self.assertEqual(old.parent, launch.training.cache.parent)
        self.assertFalse(old.exists())

    def test_system_properties_do_not_change_the_cache(self):
        first = self._prepare(self._java_command(None, "-Dmicronaut.server.port=8080")).training.cache
        second = self._prepare(self._java_command(None, "-Dmicronaut.server.port=9090")).training.cache

        self.assertEqual(first, second)

    def test_jvm_options_change_the_cache(self):
        first = self._prepare(self._java_command()).training.cache
        second = self._prepare(self._java_command(None, "-XX:+UseSerialGC")).training.cache

        self.assertNotEqual(first, second)

    def test_jdk_before_25_is_unchanged(self):
        java = self._jdk("jdk21", "21.0.5")
        command = self._java_command(java)

        launch = self._prepare(command)

        self.assertEqual(command, launch.command_line)
        self.assertIsNone(launch.training)

    def test_user_managed_aot_options_are_left_alone(self):
        command = self._java_command(None, "-XX:AOTCache=/tmp/mine.aot")
        self.assertEqual(command, self._prepare(command).command_line)
        command = self._java_command()
        self.assertEqual(command, self._prepare(command, {"JAVA_TOOL_OPTIONS": "-XX:AOTMode=off"}).command_line)

    def test_failed_creation_is_not_retrained(self):
        training = self._prepare(self._java_command()).training
        training.cache.with_suffix(".failed").touch()

        launch = self._prepare(self._java_command())

        self.assertEqual(self._java_command(), launch.command_line)
        self.assertIsNone(launch.training)

    def test_launcher_script_gets_options_through_its_opts_variable(self):
        script = self._launcher_script()
        shared = script.parent.parent.parent / "shared" / "lib"
        shared.mkdir(parents=True)
        (shared / "app.jar").write_bytes(b"jar")
        env = {"JAVA_HOME": str(self.java.parent.parent), "PYRONAUT_PROCESSOR_OPTS": "-Xmx1g"}

        launch = self._prepare([str(script), "--project-dir", "."], env)

        self.assertEqual([str(script), "--project-dir", "."], launch.command_line)
        options = launch.env["PYRONAUT_PROCESSOR_OPTS"].split()
        self.assertIn("-XX:AOTMode=record", options)
        self.assertEqual("-Xmx1g", options[-1])
        self.assertEqual("-Xmx1g", env["PYRONAUT_PROCESSOR_OPTS"])
        self.assertEqual([str(script)], launch.training.create_command)
        self.assertIn("-XX:AOTMode=create", launch.training.create_env["PYRONAUT_PROCESSOR_OPTS"])

        (shared / "app.jar").write_bytes(b"a changed jar")
        self.assertNotEqual(
            launch.training.cache,
            self._prepare([str(script)], env).training.cache,
        )

    def test_native_executables_and_other_programs_are_unchanged(self):
        native = self.root / "pyronaut-dev"
        native.write_bytes(b"\xcf\xfa\xed\xfe")
        env = {"JAVA_HOME": str(self.java.parent.parent)}
        for command in ([str(native), "dev"], ["/usr/bin/pip", "install"]):
            launch = self._prepare(command, env)
            self.assertEqual(command, launch.command_line)
            self.assertIs(env, launch.env)
            self.assertIsNone(launch.training)

    def test_finish_creates_the_cache_in_the_background_under_its_final_name(self):
        training = self._prepare(self._java_command()).training
        training.configuration.write_bytes(b"configuration")
        training.create_command = [
            sys.executable, "-c", "import pathlib, sys; pathlib.Path(sys.argv[1]).write_text('cache')", "{cache}",
        ]

        training.finish()

        deadline = time.monotonic() + 10
        while not training.cache.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertEqual(b"cache", training.cache.read_bytes())
        deadline = time.monotonic() + 10
        while training.configuration.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse(training.configuration.exists())
        self.assertEqual([], list(training.cache.parent.glob("*.tmp-*")))
        self.assertEqual([], list(training.cache.parent.glob("*.json")))

    def test_failed_creation_is_recorded(self):
        training = self._prepare(self._java_command()).training
        training.configuration.write_bytes(b"configuration")
        training.create_command = [sys.executable, "-c", "raise SystemExit(1)"]

        training.finish()

        failed = training.cache.with_suffix(".failed")
        deadline = time.monotonic() + 10
        while not failed.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(failed.exists())
        self.assertFalse(training.cache.exists())

    def test_finish_without_a_configuration_creates_nothing(self):
        training = self._prepare(self._java_command()).training
        training.create_command = [sys.executable, "-c", "raise SystemExit(1)"]

        training.finish()

        time.sleep(0.2)
        self.assertEqual([], list(training.cache.parent.iterdir()))

    def test_windows_launcher_script_gets_options_through_its_opts_variable(self):
        script = self._launcher_script(
            "pyronaut-processor.bat",
            "@if \"%DEBUG%\"==\"\" @echo off\r\n"
            "set CLASSPATH=%APP_HOME%\\..\\shared\\lib\\app.jar;%APP_HOME%\\..\\shared\\lib\\other.jar\r\n"
            "\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %PYRONAUT_PROCESSOR_OPTS% -classpath \"%CLASSPATH%\" Main %*\r\n",
        )
        env = {"JAVA_HOME": str(self.java.parent.parent)}

        launch = self._prepare([str(script), "--project-dir", "."], env)

        self.assertEqual([str(script), "--project-dir", "."], launch.command_line)
        self.assertIn("-XX:AOTMode=record", launch.env["PYRONAUT_PROCESSOR_OPTS"].split())
        self.assertNotIn("PYRONAUT_PROCESSOR.BAT_OPTS", launch.env)
        self.assertEqual([str(script)], launch.training.create_command)

    def test_java_exe_is_a_direct_java_launch(self):
        java = self.java.with_name("java.exe")
        self.java.rename(java)

        launch = self._prepare(self._java_command(java))

        self.assertIn("-XX:AOTMode=record", launch.command_line)

    def test_launcher_options_with_spaces_are_quoted(self):
        script = self._launcher_script()
        env = {"JAVA_HOME": str(self.java.parent.parent)}
        root = self.root / "home with spaces"

        launch = aot_cache.prepare([str(script)], env, project_dir=self.project_dir, root=root)

        options = launch.env["PYRONAUT_PROCESSOR_OPTS"]
        self.assertIn(f'"-XX:AOTConfiguration={launch.training.configuration}"', options)

    def test_terminating_a_training_process_on_windows_discards_its_configuration(self):
        launch = self._prepare(self._java_command())
        launch.training.configuration.write_bytes(b"partial")
        process = MagicMock()
        managed = aot_cache.ManagedProcess(process, launch)

        with patch.object(aot_cache, "_WINDOWS", True):
            managed.terminate()

        process.terminate.assert_called_once()
        self.assertFalse(launch.training.configuration.exists())

    def test_terminating_a_training_process_on_posix_keeps_its_configuration(self):
        launch = self._prepare(self._java_command())
        launch.training.configuration.write_bytes(b"configuration")
        managed = aot_cache.ManagedProcess(MagicMock(), launch)

        with patch.object(aot_cache, "_WINDOWS", False):
            managed.terminate()

        self.assertTrue(launch.training.configuration.exists())

    def test_killed_training_process_discards_its_configuration(self):
        launch = self._prepare(self._java_command())
        launch.training.configuration.write_bytes(b"partial")
        process = MagicMock()
        process.poll.return_value = -9
        managed = aot_cache.ManagedProcess(process, launch)

        managed.kill()
        self.assertEqual(-9, managed.poll())

        process.kill.assert_called_once()
        self.assertFalse(launch.training.configuration.exists())
        time.sleep(0.2)
        self.assertFalse(launch.training.cache.with_suffix(".failed").exists())
        self.assertEqual(aot_cache.TRAINING_STOP_TIMEOUT_SECONDS, managed.stop_timeout)


if __name__ == "__main__":
    unittest.main()
