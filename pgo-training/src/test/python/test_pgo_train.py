# Copyright 2017-2026 original authors

import ast
import importlib.util
import io
import logging
import runpy
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location(
    "pgo_train", Path(__file__).parents[2] / "main/python/pgo_train.py"
)
pgo_train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pgo_train)

spec = importlib.util.spec_from_file_location(
    "pgo_benchmark", Path(__file__).parents[2] / "main/python/pgo_benchmark.py"
)
pgo_benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pgo_benchmark)


class WindowsLauncherTest(unittest.TestCase):
    def test_windows_jvm_shutdown_kills_untracked_runtime_tree(self):
        for image in ("pyronaut-run", "pyronaut-run-python"):
            with self.subTest(image=image), tempfile.TemporaryDirectory() as directory:
                trainer = pgo_train.Trainer.__new__(pgo_train.Trainer)
                trainer.jvm = True
                trainer.image = image
                trainer.pids = Path(directory)
                trainer.options = SimpleNamespace(shutdown_timeout=0)
                process = Mock(pid=123)
                process.poll.return_value = None
                self.assertEqual([], trainer._pids("runtime"))
                with patch.object(pgo_train.os, "name", "nt"), \
                        patch.object(pgo_train.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as run:
                    trainer._stop("runtime", process)
                run.assert_called_once_with(["taskkill", "/PID", "123", "/T", "/F"],
                                            capture_output=True, text=True, timeout=30)
                process.wait.assert_called_once_with()
                process.kill.assert_not_called()
                process.send_signal.assert_not_called()

    def test_windows_jvm_shutdown_reports_failed_tree_termination(self):
        trainer = pgo_train.Trainer.__new__(pgo_train.Trainer)
        trainer.jvm = True
        process = Mock(pid=123)
        process.poll.return_value = None
        with patch.object(pgo_train.os, "name", "nt"), \
                patch.object(pgo_train.subprocess, "run", return_value=SimpleNamespace(returncode=1, stderr="access denied")):
            with self.assertRaisesRegex(pgo_train.TrainingError, "cannot stop JVM process tree: access denied"):
                trainer._stop("runtime", process)
        process.kill.assert_not_called()
        process.wait.assert_not_called()

    def test_windows_jvm_shim_uses_stock_tool_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            install = Path(directory) / "install"
            (install / "lib").mkdir(parents=True)
            trainer = pgo_train.Trainer.__new__(pgo_train.Trainer)
            trainer.options = SimpleNamespace(dev_install_dir=str(install), java_home="C:\\Java")
            trainer.shims = Path(directory) / "shims"
            with patch.object(pgo_train, "os", SimpleNamespace(name="nt")), \
                 patch.object(trainer, "_write_windows_shim", return_value=Path(directory)) as write, \
                 patch.object(trainer, "_copy_manifests"):
                trainer._write_jvm_dev_shim()
            script = ast.parse(write.call_args.args[1])
            command = next(node.value for node in script.body if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Name) and target.id == "command" for target in node.targets))
            classpath = next(command.elts[index + 1].value for index, node in enumerate(command.elts)
                             if isinstance(node, ast.Constant) and node.value == "-cp")
            self.assertEqual(str(install.resolve() / "lib" / "*"), classpath)


class FixtureVersionsTest(unittest.TestCase):
    versions = {"core": "5.9.41", "platform": "5.8.31", "serde": "3.8.21", "validation": "5.8.11"}
    apps = Path(__file__).parents[3] / "apps"

    def arguments(self, work_dir="unused"):
        return [
            "--image", "pyronaut-dev", "--dev-install-dir", "unused", "--profiles-dir", "unused",
            "--work-dir", work_dir, "--apps-dir", str(self.apps), "--repository", "fixture-repo",
            "--cli-source", "unused", "--graalpy", "graalpy", "--java-home", "unused",
            *[argument for module, version in self.versions.items()
              for argument in (f"--micronaut-{module}-version", version)],
        ]

    def test_training_and_benchmark_copy_all_fixtures_with_image_versions(self):
        for driver, jvm in ((pgo_train.Trainer, False), (pgo_train.Trainer, True), (pgo_benchmark.Benchmark, False)):
            with self.subTest(driver=driver.__name__, jvm=jvm), tempfile.TemporaryDirectory() as directory:
                options = pgo_train.parse_args(["--jvm", *self.arguments(directory)])
                options.jvm = jvm
                trainer = (driver(options, "baseline", Path(__file__))
                           if driver is pgo_benchmark.Benchmark else driver(options))
                for app in ("java-maven", "java", "python"):
                    fixture_name = "pom.xml" if app == "java-maven" else "pyproject.toml"
                    source = self.apps / app / fixture_name
                    original = source.read_text()
                    copied = trainer.copy_app(app) / fixture_name
                    content = copied.read_text()
                    self.assertNotIn("@MICRONAUT_", content)
                    self.assertEqual(original, source.read_text())
                    if app == "java-maven":
                        pom = ET.fromstring(content)
                        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
                        self.assertEqual(self.versions["platform"], pom.findtext("m:parent/m:version", namespaces=ns))
                        for module in ("core", "serde", "validation"):
                            self.assertEqual(self.versions[module],
                                             pom.findtext(f"m:properties/m:micronaut.{module}.version", namespaces=ns))
                        self.assertEqual(["${micronaut.core.version}", "${micronaut.serde.version}",
                                          "${micronaut.validation.version}"],
                                         [node.text for node in pom.findall(".//m:annotationProcessorPaths/m:path/m:version", ns)])
                    else:
                        config = tomllib.loads(content)["tool"]["pyronaut"]
                        for module in ("core", "platform"):
                            self.assertEqual(self.versions[module], config[module]["version"])
                        self.assertEqual([
                            f"io.micronaut.serde:micronaut-serde-bom:{self.versions['serde']}",
                            f"io.micronaut.validation:micronaut-validation-bom:{self.versions['validation']}",
                        ], config["dependencies"]["boms"])
                        self.assertEqual("jvm" if jvm else "native", config["toolchain"]["type"])
                        self.assertEqual(str(Path(options.repository).resolve()), config["repositories"][0])

    def test_both_drivers_require_explicit_image_versions(self):
        base = self.arguments()[:-2 * len(self.versions)]
        for parse, arguments in (
            (pgo_train.parse_args, ["--jvm", *base]),
            (pgo_benchmark.main, ["--candidate", "baseline=unused", "--manifests-dir", "unused", *base]),
        ):
            with self.subTest(driver=parse.__module__), redirect_stderr(io.StringIO()) as output:
                with self.assertRaises(SystemExit) as error:
                    parse(arguments)
                self.assertEqual(2, error.exception.code)
                for module in self.versions:
                    self.assertIn(f"--micronaut-{module}-version", output.getvalue())

    def test_benchmark_parser_passes_explicit_versions_to_shared_trainer(self):
        benchmark = Mock()
        benchmark.prepare.side_effect = pgo_benchmark.TrainingError("stop before launching")
        with patch.object(pgo_benchmark, "Benchmark", return_value=benchmark) as driver, redirect_stdout(io.StringIO()):
            self.assertEqual(1, pgo_benchmark.main([
                "--candidate", "baseline=unused", "--manifests-dir", "unused", *self.arguments(),
            ]))
        options = driver.call_args.args[0]
        for module, version in self.versions.items():
            self.assertEqual(version, getattr(options, f"micronaut_{module}_version"))

    def test_python_bootstrap_configures_info_logging(self):
        with patch.object(logging, "basicConfig") as configure:
            runpy.run_path(str(self.apps / "python/src/main.py"))
        configure.assert_called_once_with(level=logging.INFO)


if __name__ == "__main__":
    unittest.main()
