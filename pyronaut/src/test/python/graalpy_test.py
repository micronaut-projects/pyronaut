import hashlib
import importlib.util
import io
import json
import os
import sys
import tarfile
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

_CLI_MODULE_PATH = Path(__file__).resolve().parents[2] / "main" / "python" / "pyronaut_cli_v2" / "cli.py"
_CLI_SPEC = importlib.util.spec_from_file_location("pyronaut_cli_v2.cli", _CLI_MODULE_PATH)
if _CLI_SPEC is None or _CLI_SPEC.loader is None:
    raise RuntimeError("Failed loading pyronaut_cli_v2.cli for tests")
if str(_CLI_MODULE_PATH.parent.parent) not in sys.path:
    sys.path.insert(0, str(_CLI_MODULE_PATH.parent.parent))
sys.modules.pop("pyronaut_cli_v2", None)
_pkg = types.ModuleType("pyronaut_cli_v2")
_pkg.__path__ = [str(_CLI_MODULE_PATH.parent)]
sys.modules["pyronaut_cli_v2"] = _pkg

cli = importlib.util.module_from_spec(_CLI_SPEC)
_CLI_SPEC.loader.exec_module(cli)

_VERSION_PROPERTIES = {"graalpy": "25.4.4.1.1", "graalpy.pyenv": "graalpy3.13-25.4.4"}
_SPEC = cli._GraalPySpec("graalpy3.13-25.4.4", "25.4.4.1.1", "graal-25.4.4")


def _write_fake_graalpy(home: Path, version_line: str = "GraalPy 3.13.14 (Oracle GraalVM Native 25.4.4.1.1)") -> Path:
    """A shell script that answers the interpreter probes like GraalPy."""
    executable = home / "bin" / "graalpy"
    executable.parent.mkdir(parents=True, exist_ok=True)
    details = json.dumps({
        "implementation": "graalpy",
        "basePrefix": str(home),
        "prefix": str(home),
        "sitePackages": str(home / "lib" / "python3.13" / "site-packages"),
    })
    executable.write_text(
        "#!/bin/sh\n"
        f"if [ \"$1\" = \"--version\" ]; then echo '{version_line}'; exit 0; fi\n"
        f"if [ \"$1\" = \"-c\" ]; then echo '{details}'; exit 0; fi\n"
        "exit 1\n",
        encoding="utf-8",
    )
    executable.chmod(0o755)
    return executable


class GraalPyProvisioningTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name).resolve()
        self.home = self.root / "home"
        self.home.mkdir()
        self._env = patch.dict(os.environ, {"PYENV_ROOT": str(self.home / ".pyenv"), "PATH": "/usr/bin:/bin"})
        self._env.start()
        os.environ.pop("PYENV_VERSION", None)
        self._home_patch = patch("pathlib.Path.home", return_value=self.home)
        self._home_patch.start()
        self._impl_patch = patch.object(cli, "_running_graalpy", return_value=None)
        self._impl_patch.start()
        cli._validated_setup_manifest = None

    def tearDown(self):
        cli._validated_setup_manifest = None
        self._impl_patch.stop()
        self._home_patch.stop()
        self._env.stop()
        self._temp.cleanup()

    def test_required_graalpy_comes_from_version_properties(self):
        with patch.object(cli, "_read_version_properties", return_value=_VERSION_PROPERTIES):
            self.assertEqual(_SPEC, cli._required_graalpy())
        with patch.object(cli, "_read_version_properties", return_value={"graalpy.pyenv": "graalpy3.13-25.3.4.1"}):
            self.assertEqual("graal-25.3.4", cli._required_graalpy().release_tag)
        with patch.object(
            cli,
            "_read_version_properties",
            return_value={**_VERSION_PROPERTIES, "graalpy.release-tag": "graal-custom"},
        ):
            self.assertEqual("graal-custom", cli._required_graalpy().release_tag)
        with patch.object(cli, "_read_version_properties", return_value={}):
            self.assertIsNone(cli._required_graalpy())

    def test_version_reports_required_graalpy_interpreter(self):
        stdout = io.StringIO()
        with patch.object(cli, "_read_version_properties", return_value=_VERSION_PROPERTIES), redirect_stdout(stdout):
            self.assertEqual(cli.SUCCESS, cli.run(["--version"]))
        self.assertIn("GraalPy: 25.4.4.1.1\n", stdout.getvalue())
        self.assertIn("GraalPy Interpreter: graalpy3.13-25.4.4\n", stdout.getvalue())

    def test_probe_accepts_matching_graalpy_and_rejects_other_versions(self):
        home = self.root / "graalpy"
        executable = _write_fake_graalpy(home)
        installation = cli._probe_graalpy(executable, _SPEC)
        self.assertIsNotNone(installation)
        self.assertEqual(executable, installation.executable)
        self.assertEqual(home, installation.home)
        self.assertEqual(str(home / "lib" / "python3.13" / "site-packages"), installation.site_packages)

        other = _write_fake_graalpy(self.root / "old", "GraalPy 3.13.14 (Oracle GraalVM Native 25.3.4.1)")
        self.assertIsNone(cli._probe_graalpy(other, _SPEC))

    def test_existing_pyenv_installation_is_reused_without_installing(self):
        pyenv_graalpy = _write_fake_graalpy(self.home / ".pyenv" / "versions" / _SPEC.name)
        runner_calls = []
        with (
            patch.object(cli, "_required_graalpy", return_value=_SPEC),
            patch.object(cli, "_download_and_install_graalpy") as download,
        ):
            installation = cli._ensure_graalpy(lambda command, env=None: runner_calls.append(command) or 0)
        self.assertEqual(pyenv_graalpy, installation.executable)
        self.assertEqual([], runner_calls)
        download.assert_not_called()

    def test_sdk_installation_takes_precedence_over_pyenv(self):
        _write_fake_graalpy(self.home / ".pyenv" / "versions" / _SPEC.name)
        sdk_graalpy = _write_fake_graalpy(self.home / ".pyronaut" / "sdks" / _SPEC.name)
        with patch.object(cli, "_required_graalpy", return_value=_SPEC):
            self.assertEqual(sdk_graalpy, cli._ensure_graalpy(lambda command, env=None: 1).executable)

    def test_pyenv_installs_missing_graalpy(self):
        pyenv = self.home / ".pyenv" / "bin" / "pyenv"
        pyenv.parent.mkdir(parents=True)
        pyenv.write_text("#!/bin/sh\n", encoding="utf-8")
        pyenv.chmod(0o755)
        commands = []

        def runner(command, env=None):
            commands.append(command)
            _write_fake_graalpy(self.home / ".pyenv" / "versions" / _SPEC.name)
            return 0

        with (
            patch.object(cli, "_required_graalpy", return_value=_SPEC),
            patch.object(cli, "_download_and_install_graalpy") as download,
            redirect_stderr(io.StringIO()),
        ):
            installation = cli._ensure_graalpy(runner)
        self.assertEqual([[str(pyenv), "install", "--skip-existing", _SPEC.name]], commands)
        self.assertEqual(self.home / ".pyenv" / "versions" / _SPEC.name / "bin" / "graalpy", installation.executable)
        download.assert_not_called()

    def test_offline_does_not_install_or_download(self):
        with (
            patch.object(cli, "_required_graalpy", return_value=_SPEC),
            patch.object(cli, "_install_graalpy_with_pyenv") as pyenv_install,
            patch.object(cli, "_download_and_install_graalpy") as download,
        ):
            self.assertIsNone(cli._ensure_graalpy(lambda command, env=None: 0, offline=True))
        pyenv_install.assert_not_called()
        download.assert_not_called()

    def _release_archive(self) -> bytes:
        staging = self.root / "staging"
        _write_fake_graalpy(staging / "graalpy3.13-25.4.4.1.1-linux-amd64")
        archive = self.root / "release.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(staging / "graalpy3.13-25.4.4.1.1-linux-amd64", arcname="graalpy3.13-25.4.4.1.1-linux-amd64")
        return archive.read_bytes()

    def test_download_installs_verified_release_into_sdks(self):
        payload = self._release_archive()
        downloads = []

        def download(url, destination, label, headers=None):
            downloads.append(url)
            if url.endswith(".sha256"):
                destination.write_text(hashlib.sha256(payload).hexdigest() + "\n", encoding="utf-8")
            else:
                destination.write_bytes(payload)

        with (
            patch.object(cli, "_native_image_platform", return_value=("linux", "amd64")),
            patch.object(cli, "_download_url_with_progress", side_effect=download),
            redirect_stderr(io.StringIO()),
        ):
            installation = cli._download_and_install_graalpy(_SPEC)
        base = "https://github.com/oracle/graalpython/releases/download/graal-25.4.4/graalpy3.13-25.4.4-linux-amd64.tar.gz"
        self.assertEqual([base, base + ".sha256"], downloads)
        expected_home = self.home / ".pyronaut" / "sdks" / _SPEC.name
        self.assertEqual(expected_home / "bin" / "graalpy", installation.executable)

    def test_download_rejects_checksum_mismatch(self):
        payload = self._release_archive()

        def download(url, destination, label, headers=None):
            if url.endswith(".sha256"):
                destination.write_text("0" * 64 + "  archive\n", encoding="utf-8")
            else:
                destination.write_bytes(payload)

        with (
            patch.object(cli, "_native_image_platform", return_value=("linux", "amd64")),
            patch.object(cli, "_download_url_with_progress", side_effect=download),
            redirect_stderr(io.StringIO()),
        ):
            with self.assertRaisesRegex(RuntimeError, "Checksum mismatch"):
                cli._download_and_install_graalpy(_SPEC)
        self.assertFalse((self.home / ".pyronaut" / "sdks" / _SPEC.name).exists())

    def test_delegated_environment_uses_configured_graalpy(self):
        graalpy = self.root / "sdks" / "graalpy" / "bin" / "graalpy"
        cli._validated_setup_manifest = {
            "graalpy": {"executable": str(graalpy), "sitePackages": "/sdks/graalpy/site-packages"}
        }
        env = cli._build_java_home_env("run", lambda: "/tmp/graalvm")
        self.assertEqual(str(graalpy), env["PYRONAUT_PYTHON_EXECUTABLE"])
        self.assertEqual("/sdks/graalpy/site-packages", env["PYRONAUT_PYTHON_SITE_PACKAGES"])
        self.assertEqual(["/tmp/graalvm/bin", str(graalpy.parent)], env["PATH"].split(os.pathsep)[:2])

        with patch.dict(os.environ, {"PYRONAUT_PYTHON_EXECUTABLE": "/custom/python"}):
            env = cli._build_java_home_env("run", lambda: "/tmp/graalvm")
        self.assertEqual("/custom/python", env["PYRONAUT_PYTHON_EXECUTABLE"])


class ProjectVirtualenvTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name).resolve()
        self.project = self.root / "app"
        self.project.mkdir()
        self.graalpy_home = self.root / "graalpy"
        self.graalpy = _write_fake_graalpy(self.graalpy_home)
        self.commands = []
        self.windows_venv = False
        cli._validated_setup_manifest = {"graalpy": {"executable": str(self.graalpy)}}

    def tearDown(self):
        cli._validated_setup_manifest = None
        self._temp.cleanup()

    def runner(self, command, env=None):
        self.commands.append((command, env))
        if command[1:3] == ["-m", "venv"]:
            venv = Path(command[3])
            venv_bin = venv / ("Scripts" if self.windows_venv else "bin")
            python = venv_bin / ("python.exe" if self.windows_venv else "python")
            python.parent.mkdir(parents=True)
            python.write_text("# fake GraalPy interpreter\n", encoding="utf-8")
            (venv / "pyvenv.cfg").write_text(f"home = {self.graalpy.parent}\n", encoding="utf-8")
        return 0

    def ensure(self, **kwargs):
        with redirect_stderr(io.StringIO()):
            return cli._ensure_project_virtualenv(self.project, self.runner, **kwargs)

    def test_project_without_python_dependencies_is_left_alone(self):
        (self.project / "pyproject.toml").write_text("[project]\nname = 'app'\n", encoding="utf-8")
        self.assertEqual(cli.SUCCESS, self.ensure())
        self.assertEqual([], self.commands)
        self.assertFalse((self.project / ".venv").exists())

    def test_creates_graalpy_venv_and_installs_declared_dependencies(self):
        (self.project / "pyproject.toml").write_text(
            "[project]\nname = 'app'\ndependencies = ['requests>=2', 'pydantic']\n\n"
            "[dependency-groups]\ndev = [{include-group = 'test'}, 'ruff']\ntest = ['pytest>=8']\n",
            encoding="utf-8",
        )
        (self.project / "requirements.txt").write_text("attrs==24.2.0\n", encoding="utf-8")

        self.assertEqual(cli.SUCCESS, self.ensure())

        venv = self.project / ".venv"
        self.assertEqual([str(self.graalpy), "-m", "venv", str(venv)], self.commands[0][0])
        pip_command, pip_env = self.commands[1]
        self.assertEqual(
            [
                str(venv / "bin" / "python"), "-m", "pip", "install", "--disable-pip-version-check",
                "-r", str(self.project / "requirements.txt"),
                "requests>=2", "pydantic", "pytest>=8", "ruff",
            ],
            pip_command,
        )
        self.assertNotIn("VIRTUAL_ENV", self.commands[0][1])
        self.assertNotIn("VIRTUAL_ENV", pip_env)
        self.assertEqual(str(venv / "bin"), pip_env["PATH"].split(os.pathsep)[0])

        # Unchanged declarations do not reinstall.
        self.commands.clear()
        self.assertEqual(cli.SUCCESS, self.ensure())
        self.assertEqual([], self.commands)

        # Changing requirements.txt does.
        (self.project / "requirements.txt").write_text("attrs==25.1.0\n", encoding="utf-8")
        self.assertEqual(cli.SUCCESS, self.ensure())
        self.assertEqual(1, len(self.commands))
        self.assertIn("pip", self.commands[0][0])

        self.commands.clear()
        self.assertEqual(cli.SUCCESS, self.ensure(refresh=True))
        self.assertEqual(1, len(self.commands))

    def test_windows_venv_uses_scripts_and_python_exe(self):
        self.windows_venv = True
        (self.project / "requirements.txt").write_text("pytest\n", encoding="utf-8")
        with patch.dict(os.environ, {"VIRTUAL_ENV": str(self.root / "outer-venv"), "PYTHONHOME": "invalid"}):
            self.assertEqual(cli.SUCCESS, self.ensure())

        venv = self.project / ".venv"
        scripts = venv / "Scripts"
        self.assertEqual(str(scripts / "python.exe"), self.commands[1][0][0])
        self.assertNotIn("VIRTUAL_ENV", self.commands[0][1])
        self.assertNotIn("VIRTUAL_ENV", self.commands[1][1])
        self.assertNotIn("PYTHONHOME", self.commands[0][1])
        self.assertNotIn("PYTHONHOME", self.commands[1][1])
        self.assertEqual(str(scripts), self.commands[1][1]["PATH"].split(os.pathsep)[0])

        activated = cli._apply_project_virtualenv({}, self.project)
        self.assertEqual(str(venv), activated["VIRTUAL_ENV"])
        self.assertEqual(str(scripts / "python.exe"), activated["PYRONAUT_PYTHON_EXECUTABLE"])

    def test_adds_pytest_when_project_has_tests(self):
        (self.project / "pyproject.toml").write_text("[project]\nname = 'app'\n", encoding="utf-8")
        (self.project / "tests").mkdir()
        self.assertEqual(cli.SUCCESS, self.ensure())
        self.assertEqual("pytest", self.commands[1][0][-1])

    def test_offline_skips_pip(self):
        (self.project / "requirements.txt").write_text("attrs\n", encoding="utf-8")
        self.assertEqual(cli.SUCCESS, self.ensure(offline=True))
        self.assertEqual(1, len(self.commands))
        self.assertEqual(["-m", "venv"], self.commands[0][0][1:3])

    def test_rejects_venv_not_created_by_graalpy(self):
        (self.project / "requirements.txt").write_text("attrs\n", encoding="utf-8")
        venv_bin = self.project / ".venv" / "bin"
        venv_bin.mkdir(parents=True)
        cpython = self.root / "cpython" / "bin" / "python3"
        cpython.parent.mkdir(parents=True)
        cpython.write_text("#!/bin/sh\n", encoding="utf-8")
        (venv_bin / "python").write_text("# fake CPython interpreter\n", encoding="utf-8")
        (self.project / ".venv" / "pyvenv.cfg").write_text(f"home = {cpython.parent}\n", encoding="utf-8")
        self.assertEqual(cli.PRECONDITION_FAILED, self.ensure())
        self.assertEqual([], self.commands)

    def test_install_command_prepares_venv_before_resolving_java_dependencies(self):
        (self.project / "requirements.txt").write_text("attrs\n", encoding="utf-8")
        (self.project / "pyproject.toml").write_text("[project]\nname = 'app'\n", encoding="utf-8")
        with (
            patch.object(cli, "_delegate", side_effect=lambda *args, **kwargs: self.commands.append(("delegate", args[0])) or 0),
            redirect_stderr(io.StringIO()),
        ):
            exit_code = cli.run(
                ["install", "--project-dir", str(self.project)],
                runner_with_env=self.runner,
                resolver=lambda name: None,
                platform_name="linux",
                java_home_provider=lambda: "/tmp/graalvm",
            )
        self.assertEqual(cli.SUCCESS, exit_code)
        self.assertEqual(["-m", "venv"], self.commands[0][0][1:3])
        self.assertEqual("pip", self.commands[1][0][2])
        self.assertEqual(("delegate", "install"), self.commands[2])


if __name__ == "__main__":
    unittest.main()
