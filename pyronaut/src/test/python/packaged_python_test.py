"""Vendored GraalPy environments in JARs and application wheels."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
from pyronaut_cli_v2 import cli, python_packages

ROOT = Path(__file__).resolve().parents[4]
VFS = Path("META-INF/GRAALPY-VFS/micronaut-application")


class PackagedPythonTest(unittest.TestCase):
    def test_invalid_requirements_do_not_install_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            (project / "pyproject.toml").write_text('[project]\ndependencies = "idna"\n')
            with patch.object(python_packages, "install") as install:
                with self.assertRaisesRegex(ValueError, "array of requirement strings"):
                    cli._prepare_packaged_python_dependencies(project, offline=False)
                install.assert_not_called()

    def test_build_restores_the_development_environment_even_on_failure(self):
        for failure in (None, "prepare", "build"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                project = Path(temporary)
                (project / "requirements.txt").write_text("idna\n")
                vfs = project / "__pyronaut__/classes" / VFS
                (vfs / "venv").mkdir(parents=True)
                (vfs / "venv/development.txt").write_text("original")
                (vfs / "fileslist.txt").write_text("original index\n")

                def prepare(*args, **kwargs):
                    (vfs / "venv").mkdir()
                    (vfs / "venv/packaged.txt").touch()
                    (vfs / "fileslist.txt").write_text("packaged index\n")
                    if failure == "prepare":
                        raise RuntimeError("failed installation")

                with patch.object(cli, "_prepare_packaged_python_dependencies", side_effect=prepare):
                    try:
                        with cli._packaged_python_dependencies(project, offline=False):
                            self.assertTrue((vfs / "venv/packaged.txt").is_file())
                            if failure == "build":
                                raise RuntimeError("failed build")
                    except RuntimeError:
                        self.assertIsNotNone(failure)
                self.assertEqual("original", (vfs / "venv/development.txt").read_text())
                self.assertFalse((vfs / "venv/packaged.txt").exists())
                self.assertEqual("original index\n", (vfs / "fileslist.txt").read_text())

    def test_package_keeps_sources_and_omits_host_interpreter(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            venv, classes = root / "venv", root / "classes"
            (venv / "lib/python3.13/site-packages/idna").mkdir(parents=True)
            (venv / "lib/python3.13/site-packages/idna/__init__.py").write_text("PACKAGE = True\n")
            (venv / "pyvenv.cfg").write_text("home = /build/graalpy\n")
            (venv / "bin").mkdir()
            (venv / "bin/python").write_text("host interpreter")
            (classes / VFS / "src").mkdir(parents=True)
            (classes / VFS / "src/main.py").write_text("import idna\n")
            with patch.object(python_packages.subprocess, "check_output", return_value="idna==3.10\n"):
                python_packages.package(venv, classes)
            self.assertTrue((classes / VFS / "venv/lib/python3.13/site-packages/idna/__init__.py").is_file())
            self.assertFalse((classes / VFS / "venv/bin/python").exists())
            index = (classes / VFS / "fileslist.txt").read_text()
            self.assertIn("/src/main.py\n", index)
            self.assertIn("/venv/lib/python3.13/site-packages/idna/__init__.py\n", index)
            python_packages.remove_packaged_environment(classes)
            self.assertTrue((classes / VFS / "src/main.py").is_file())
            self.assertNotIn("/venv/", (classes / VFS / "fileslist.txt").read_text())

    def test_docker_does_not_copy_a_previous_host_package_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "classes"
            (classes / VFS / "venv").mkdir(parents=True)
            (classes / VFS / "venv/pyronaut-packaged.txt").touch()
            (classes / VFS / "venv/host-native.so").touch()
            cli._copy_docker_classes(classes, root / "docker-classes")
            self.assertFalse((root / "docker-classes" / VFS / "venv").exists())
            self.assertTrue((classes / VFS / "venv/host-native.so").exists())

    def test_removing_requirements_removes_the_previous_vendored_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            classes = project / "__pyronaut__/classes"
            (classes / VFS / "venv").mkdir(parents=True)
            (classes / VFS / "venv/pyronaut-packaged.txt").touch()
            cli._prepare_packaged_python_dependencies(project, offline=True)
            self.assertFalse((classes / VFS / "venv").exists())


@unittest.skipUnless(os.environ.get("PYRONAUT_PACKAGED_PYTHON_E2E") == "true",
                     "Set PYRONAUT_PACKAGED_PYTHON_E2E=true after building the runner and JAR-builder distributions")
class PackagedPythonE2ETest(unittest.TestCase):
    def test_platform_wheel_does_not_require_the_build_interpreters_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package = root / "launcher"
            package.mkdir()
            (package / "__init__.py").touch()
            cli._write_build_setup_files(staging_dir=root, launcher_package="launcher", project_name="tag-probe",
                                         project_version="1.0", native=True)
            subprocess.run([sys.executable, "-m", "pip", "wheel", "--no-deps", "--wheel-dir", str(root / "dist"), str(root)], check=True, timeout=180)
            wheel = next((root / "dist").glob("*.whl"))
            self.assertIn("-py3-none-", wheel.name)
            self.assertNotIn("none-any", wheel.name)

    def test_jar_and_installed_wheel_import_their_own_packages(self):
        java = Path(os.environ["PYRONAUT_PACKAGE_TEST_JAVA"])
        libraries = ROOT / "pyronaut-run-python/build/install/micronaut-pyronaut-run-python/lib"
        packager = ROOT / "pyronaut-jar-build/build/install/micronaut-pyronaut-jar-build/bin/pyronaut-jar-build"
        probe = ROOT / "pyronaut/src/test/resources/python-packages/PackagedPythonProbe.java"
        for installer in ("pip", "uv", "uv-workspace"):
            with self.subTest(installer=installer), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                project = root / "project"
                classes = project / "__pyronaut__/classes"
                classes.mkdir(parents=True)
                declaration = '[project]\nname = "dependency-demo"\nversion = "0.1.0"\nrequires-python = ">=3.13"\n'
                if installer == "pip":
                    (project / "requirements.txt").write_text("idna==3.10\nxxhash==3.6.0\n")
                else:
                    declaration += ('dependencies = ["idna==3.10", "xxhash==3.6.0", "local-dependency"]\n'
                                    '[tool.uv.sources]\nlocal-dependency = { workspace = true }\n'
                                    if installer == "uv-workspace" else 'dependencies = ["idna==3.10", "xxhash==3.6.0"]\n')
                (project / "pyproject.toml").write_text(declaration)
                if installer == "uv-workspace":
                    (root / "pyproject.toml").write_text('[tool.uv.workspace]\nmembers = ["project", "library"]\n')
                    (root / "library").mkdir()
                    (root / "library/pyproject.toml").write_text(
                        '[project]\nname = "local-dependency"\nversion = "1.0"\n'
                        '[build-system]\nrequires = ["setuptools>=61"]\nbuild-backend = "setuptools.build_meta"\n'
                        '[tool.setuptools]\npy-modules = ["local_dependency"]\n')
                    (root / "library/local_dependency.py").write_text('VALUE = 42\n')
                if installer != "pip":
                    subprocess.run(["uv", "lock", "--project", str(project), "--python", sys.executable], check=True)
                cli._prepare_packaged_python_dependencies(project, offline=False)
                subprocess.run([str(java.with_name("javac")), "--release", "25", "-cp", str(libraries / "*"),
                                "-d", str(classes), str(probe)], check=True)
                manifest = project / "__pyronaut__/resolved-runtime-dependencies"
                manifest.write_text("".join(str(path) + "\n" for path in sorted(libraries.glob("*.jar"))))
                env = dict(os.environ, JAVA_HOME=str(java.parent.parent),
                           PYRONAUT_TEST_HOST_PYTHON=str(root / "empty-venv/bin/python"))
                env.pop("PYRONAUT_TEST_WORKSPACE", None)
                if installer == "uv-workspace":
                    env["PYRONAUT_TEST_WORKSPACE"] = "true"
                (root / "empty-venv/bin").mkdir(parents=True)
                (root / "empty-venv/bin/python").touch()
                (root / "empty-venv/pyvenv.cfg").write_text("home = /missing\n")
                jar = root / "application.jar"
                subprocess.run([str(packager), "--output", str(jar), "--classes-dir", str(classes),
                                "--classpath-file", str(manifest), "--name", "demo", "--version", "1",
                                "--main-class", "PackagedPythonProbe"], env=env, check=True, timeout=300)
                self.assert_import([str(java), "--enable-native-access=ALL-UNNAMED", "-Dpolyglot.engine.WarnInterpreterOnly=false", "-jar", str(jar)], env)
                stage = root / "stage"
                cli._prepare_build_wheel_staging(project_dir=project, staging_dir=stage, project_name="dependency-demo",
                    project_version="0.1.0", mode="jvm", main_class="", runner_executable=None)
                wheel_directory = root / "dist"
                subprocess.run([sys.executable, "-m", "pip", "wheel", "--no-deps",
                                "--wheel-dir", str(wheel_directory), str(stage)], check=True, timeout=180)
                wheel = next(wheel_directory.glob("*.whl"))
                self.assertNotIn("none-any", wheel.name)
                installed = root / "installed"
                subprocess.run([sys.executable, "-m", "pip", "install", "--no-deps", "--no-compile", "--target", str(installed), str(wheel)], check=True, timeout=180)
                app = installed / cli._launcher_package_name("dependency-demo") / "app"
                entries = [app / "__pyronaut__/classes", *(app / entry for entry in (app / "__pyronaut__/resolved-runtime-dependencies").read_text().splitlines())]
                self.assert_import([str(java), "--enable-native-access=ALL-UNNAMED", "-Dpolyglot.engine.WarnInterpreterOnly=false", "-cp",
                                    os.pathsep.join(map(str, entries)), "PackagedPythonProbe"], env)

    def assert_import(self, command, env):
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PACKAGED_IMPORT_OK", result.stdout)
