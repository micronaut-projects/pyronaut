"""Python dependencies in generated Docker contexts and runtime images."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from contextlib import contextmanager, ExitStack
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
from pyronaut_cli_v2 import cli, python_packages

SPEC = cli._GraalPySpec("graalpy3.13-25.4.4", "25.4.4.1.1", "graal-25.4.4")


class DockerPythonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.project = self.root / "app"
        self.project.mkdir()
        self.context = self.root / "context"
        self.context.mkdir()
        self.pyproject = self.project / "pyproject.toml"
        self.pyproject.write_text('[project]\nname = "demo"\nversion = "0.1.0"\n', encoding="utf-8")

    def test_requirements_include_constraints_and_local_dependencies(self):
        (self.project / "requirements.txt").write_text('-r requirements/runtime.txt\n-c constraints.txt\n', encoding="utf-8")
        (self.project / "requirements").mkdir()
        (self.project / "requirements/runtime.txt").write_text('idna==3.10\n-e ./library\n-r ../requirements.txt\n', encoding="utf-8")
        (self.project / "constraints.txt").write_text('idna<4\n', encoding="utf-8")
        (self.project / "library").mkdir()
        (self.project / "library/pyproject.toml").write_text('[project]\nname = "library"\n', encoding="utf-8")
        (self.project / "library/.env").write_text('TOKEN=private\n', encoding="utf-8")
        (self.project / "requirements-test.txt").write_text('pytest\n', encoding="utf-8")
        target = self.context / "inputs"
        self.assertEqual(Path('.'), cli._stage_python_package_inputs(self.project, target, uv=False))
        for name in ("requirements.txt", "requirements/runtime.txt", "constraints.txt", "library/pyproject.toml"):
            self.assertTrue((target / name).is_file(), name)
        self.assertFalse((target / "requirements-test.txt").exists())
        self.assertFalse((target / "library/.env").exists())
        self.assertIn('\n./library\n', (target / "requirements/runtime.txt").read_text())

    def test_requirement_cannot_copy_files_outside_project(self):
        (self.root / "private.txt").write_text('private', encoding="utf-8")
        (self.project / "requirements.txt").write_text('-r ../private.txt\n', encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "outside the project/workspace"):
            cli._stage_python_package_inputs(self.project, self.context / "inputs", uv=False)
        self.assertFalse((self.context / "private.txt").exists())

    def test_requirement_comments_and_local_project_do_not_restore_editable_installs(self):
        (self.project / "requirements.txt").write_text(
            "# Don't install development packages\n-r runtime.txt # application's packages\n-e.\n")
        (self.project / "runtime.txt").write_text("idna==3.10 # application's IDNA support\n")
        target = self.context / "inputs"
        cli._stage_python_package_inputs(self.project, target, uv=False)
        self.assertIn("\n.\n", (target / "requirements.txt").read_text())
        self.assertNotIn("-e.", (target / "requirements.txt").read_text())
        self.assertTrue((target / "runtime.txt").is_file())

    def test_relative_find_links_directory_is_staged(self):
        (self.project / "wheels").mkdir()
        (self.project / "wheels/library.whl").touch()
        (self.project / "requirements.txt").write_text("--find-links ./wheels\nlibrary==1\n")
        target = self.context / "inputs"
        cli._stage_python_package_inputs(self.project, target, uv=False)
        self.assertTrue((target / "wheels/library.whl").is_file())

    def test_local_package_cannot_copy_symlinks_outside_workspace(self):
        (self.root / "private.txt").write_text("private")
        (self.project / "library").mkdir()
        (self.project / "library/secret.txt").symlink_to(self.root / "private.txt")
        (self.project / "requirements.txt").write_text("./library\n")
        with self.assertRaisesRegex(RuntimeError, "outside the project/workspace"):
            cli._stage_python_package_inputs(self.project, self.context / "inputs", uv=False)

    def test_requirements_symlink_keeps_its_declared_name(self):
        (self.project / "runtime.txt").write_text('idna\n', encoding="utf-8")
        (self.project / "requirements.txt").symlink_to('runtime.txt')
        target = self.context / "inputs"
        cli._stage_python_package_inputs(self.project, target, uv=False)
        self.assertEqual('idna\n', (target / "requirements.txt").read_text())

    def test_uv_workspace_preserves_member_and_path_sources(self):
        (self.root / "pyproject.toml").write_text(
            '[tool.uv.workspace]\nmembers = ["app", "library"]\n', encoding="utf-8")
        (self.root / "uv.lock").write_text('version = 1\n', encoding="utf-8")
        self.pyproject.write_text(
            '[project]\nname = "demo"\ndependencies = ["library"]\n'
            '[tool.uv.sources]\nlibrary = { workspace = true }\n', encoding="utf-8")
        library = self.root / "library"
        library.mkdir()
        (library / "pyproject.toml").write_text('[project]\nname = "library"\nversion = "1"\n', encoding="utf-8")
        (library / "library.py").write_text('VALUE = 42\n', encoding="utf-8")
        (library / ".venv").mkdir()
        (library / ".venv/host-package.py").touch()
        target = self.context / "inputs"
        self.assertEqual(Path('app'), cli._stage_python_package_inputs(self.project, target, uv=True))
        self.assertTrue((target / "uv.lock").is_file())
        self.assertTrue((target / "app/pyproject.toml").is_file())
        self.assertTrue((target / "library/library.py").is_file())
        self.assertFalse((target / "library/.venv").exists())

    def test_uv_project_requires_lock_file(self):
        with self.assertRaisesRegex(RuntimeError, "Run uv lock"):
            cli._stage_python_package_inputs(self.project, self.context / "inputs", uv=True)

    def test_common_context_includes_dependency_manifests(self):
        for name in ("requirements.txt", "uv.lock", "uv.toml"):
            (self.project / name).touch()
        app = cli._prepare_common_docker_app_context(self.project, self.context)
        for name in ("pyproject.toml", "requirements.txt", "uv.lock", "uv.toml"):
            self.assertTrue((app / name).is_file(), name)

    def test_dependency_stage_is_used_by_every_generated_image_format(self):
        (self.project / "requirements.txt").write_text('idna==3.10\n', encoding="utf-8")
        writers = {
            "jvm": lambda target: cli._write_jvm_dockerfile(target=target, base_image="jdk", runner_name="run", runtime_copies=[]),
            "native": lambda target: cli._write_native_dockerfile(target=target, builder_image="builder", runtime_image="runtime",
                project_name="demo", main_class="", include_python=True, verbose=False, static_native=False,
                passthrough_args=[], runtime_copies=[], resource_copies=[]),
            "bundled": lambda target: cli._write_bundled_application_dockerfile(target=target, runtime_image="runtime",
                runner_name="run", runtime_copies=[], resource_copies=[]),
            "crema": lambda target: cli._write_crema_application_dockerfile(target=target, base_image="base", runner_name="run", resource_copies=[]),
            "crema-base": lambda target: cli._write_crema_base_dockerfile(target=target, builder_image="builder", runtime_image="runtime",
                runner_name="run", include_python=True, verbose=False, static_native=False, passthrough_args=[], resource_copies=[]),
        }
        for name, write in writers.items():
            with self.subTest(format=name):
                context = self.context / name
                context.mkdir()
                target = context / "Dockerfile"
                write(target)
                with patch.object(cli, "_required_graalpy", return_value=SPEC):
                    cli._add_docker_python_dependencies(self.project, context, target, static_native=False)
                text = target.read_text(encoding="utf-8")
                self.assertTrue(text.startswith("FROM python:"))
                self.assertNotIn("\\\nFROM", text)
                self.assertIn("ENV VIRTUAL_ENV=/opt/pyronaut/python/venv", text)
                self.assertIn("COPY --from=pyronaut-python-dependencies /opt/pyronaut/python/", text)
                self.assertTrue((context / "python-dependencies/project/requirements.txt").is_file())
                self.assertTrue((context / "python-dependencies/builder/pyronaut_cli_v2/python_packages.py").is_file())
                self.assertNotIn("astral-sh/uv", text)

    def test_uv_builder_is_independent_of_host_uv_availability(self):
        self.pyproject.write_text('[project]\nname = "demo"\ndependencies = ["idna"]\n', encoding="utf-8")
        (self.project / "uv.lock").write_text('version = 1\n', encoding="utf-8")
        target = self.context / "Dockerfile"
        target.write_text('FROM runtime\nWORKDIR /app\nEXPOSE 8080\n', encoding="utf-8")
        with patch.object(cli, "_required_graalpy", return_value=SPEC), patch.object(cli, "_find_uv", return_value=None):
            cli._add_docker_python_dependencies(self.project, self.context, target, static_native=False)
        self.assertIn('COPY --from=ghcr.io/astral-sh/uv:0.13.6', target.read_text(encoding="utf-8"))
        config = json.loads((self.context / "python-dependencies/builder/dependencies.json").read_text())
        self.assertTrue(config["uv"])

    def test_projects_with_only_test_dependencies_do_not_add_a_runtime(self):
        (self.project / "requirements-test.txt").write_text('pytest\n', encoding="utf-8")
        target = self.context / "Dockerfile"
        target.write_text('FROM runtime\n', encoding="utf-8")
        cli._add_docker_python_dependencies(self.project, self.context, target, static_native=False)
        self.assertEqual('FROM runtime\n', target.read_text())

    def test_static_python_dependencies_fail_with_an_actionable_error(self):
        (self.project / "requirements.txt").write_text('idna\n', encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "omit --static"):
            cli._add_docker_python_dependencies(self.project, self.context, self.context / "Dockerfile", static_native=True)

    def test_installer_uses_graalpy_pip_and_excludes_test_requirements(self):
        (self.project / "requirements.txt").write_text('idna\n', encoding="utf-8")
        with self.installer_patches() as run:
            python_packages.install(self.project, ["packaging==25.0"], uv=False)
        args = run.call_args.args[0]
        self.assertEqual([str(self.root / "venv/bin/python"), "-m", "pip", "install"], args[:4])
        self.assertIn("requirements.txt", args)
        self.assertIn("packaging==25.0", args)
        self.assertNotIn("requirements-test.txt", args)
        self.assertEqual(self.project, run.call_args.kwargs["cwd"])

    def test_installer_uses_locked_uv_with_graalpy_and_no_development_groups(self):
        with self.installer_patches() as run:
            python_packages.install(self.project, ["idna"], uv=True)
        command = run.call_args.args[0]
        for argument in ("--locked", "--no-dev", "--no-default-groups", "--no-editable", "--no-install-project"):
            self.assertIn(argument, command)
        self.assertEqual(["--python", str(self.root / "venv/bin/python")], command[-2:])
        self.assertEqual(str(self.root / "venv"), run.call_args.kwargs["env"]["UV_PROJECT_ENVIRONMENT"])
        self.assertEqual("never", run.call_args.kwargs["env"]["UV_PYTHON_DOWNLOADS"])

    def test_installer_failure_is_not_ignored(self):
        with self.installer_patches() as run:
            run.side_effect = subprocess.CalledProcessError(1, "pip")
            with self.assertRaises(subprocess.CalledProcessError):
                python_packages.install(self.project, ["idna"], uv=False)

    def test_uv_cannot_replace_the_graalpy_interpreter(self):
        with self.installer_patches(), patch.object(cli, "_virtualenv_is_graalpy", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "replaced GraalPy"):
                python_packages.install(self.project, ["idna"], uv=True)

    def test_provision_reuses_the_matching_interpreter(self):
        installation = cli._GraalPyInstallation(self.root / "graalpy", self.root, None, "GraalPy")
        with self.installer_patches(), patch.object(cli, "_find_graalpy", return_value=installation):
            self.assertEqual(installation, python_packages.provision())
            cli._download_and_install_graalpy.assert_not_called()

    def test_runtime_libraries_keep_the_base_images_glibc(self):
        library = self.root / "libstdc++.so.6"
        library.write_text("C++ runtime", encoding="utf-8")
        output = f'\tlibstdc++.so.6 => {library} (0x123)\n\tlibc.so.6 => /lib/libc.so.6 (0x456)\n'
        with patch.object(cli, "_data_home", return_value=self.root), \
                patch.object(python_packages.subprocess, "check_output", return_value=output), \
                patch.object(python_packages, "cli", cli):
            python_packages._copy_runtime_libraries(self.root / "graalpy")
        self.assertEqual("C++ runtime", (self.root / "lib/libstdc++.so.6").read_text())
        self.assertFalse((self.root / "lib/libc.so.6").exists())

    @contextmanager
    def installer_patches(self):
        with ExitStack() as stack:
            stack.enter_context(patch.object(python_packages, "cli", cli))
            stack.enter_context(patch.object(python_packages, "_copy_runtime_libraries"))
            stack.enter_context(patch.object(cli, "_required_graalpy", return_value=SPEC))
            stack.enter_context(patch.object(cli, "_find_graalpy", return_value=None))
            installation = cli._GraalPyInstallation(self.root / "graalpy", self.root, None, "GraalPy")
            stack.enter_context(patch.object(cli, "_download_and_install_graalpy", return_value=installation))
            stack.enter_context(patch.object(cli, "_data_home", return_value=self.root))
            stack.enter_context(patch.object(cli, "_virtualenv_is_graalpy", return_value=True))
            yield stack.enter_context(patch.object(python_packages.subprocess, "run"))


@unittest.skipUnless(os.environ.get("PYRONAUT_DOCKER_PYTHON_E2E") == "true",
                     "Set PYRONAUT_DOCKER_PYTHON_E2E=true to build and run Python dependency images")
class DockerPythonE2ETest(unittest.TestCase):
    def test_pip_and_uv_packages_are_importable_in_distroless_images(self):
        for installer in ("pip", "uv"):
            with self.subTest(installer=installer), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                project = root / "app"
                project.mkdir()
                pyproject = '[project]\nname = "demo"\nversion = "0.1.0"\nrequires-python = ">=3.13"\n'
                if installer == "uv":
                    pyproject += 'dependencies = ["idna==3.10", "xxhash==3.6.0"]\n[dependency-groups]\ndev = ["pytest"]\n'
                (project / "pyproject.toml").write_text(pyproject, encoding="utf-8")
                if installer == "pip":
                    (project / "requirements.txt").write_text('idna==3.10\nxxhash==3.6.0\n', encoding="utf-8")
                else:
                    subprocess.run(["uv", "lock", "--project", str(project), "--python", sys.executable], check=True)
                context = root / "context"
                context.mkdir()
                dockerfile = context / "Dockerfile"
                dockerfile.write_text('FROM gcr.io/distroless/base\nWORKDIR /app\nEXPOSE 8080\n', encoding="utf-8")
                with patch.object(cli, "_required_graalpy", return_value=SPEC):
                    cli._add_docker_python_dependencies(project, context, dockerfile, static_native=False)
                image = f"pyronaut-python-dependencies-test:{uuid.uuid4().hex}"
                try:
                    subprocess.run(["docker", "build", "-t", image, str(context)], check=True, timeout=1200)
                    result = subprocess.run([
                        "docker", "run", "--rm", "--entrypoint", "/opt/pyronaut/python/venv/bin/python", image,
                        "-c", "import idna, importlib.util, sys, xxhash; assert sys.implementation.name == 'graalpy'; "
                        "assert xxhash.xxh64(b'').hexdigest() == 'ef46db3751d8e999'; "
                        "assert importlib.util.find_spec('pytest') is None; print(idna.encode('bücher.example').decode())",
                    ], capture_output=True, text=True, timeout=120)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual("xn--bcher-kva.example", result.stdout.strip())
                finally:
                    subprocess.run(["docker", "image", "rm", image], check=False, capture_output=True)
