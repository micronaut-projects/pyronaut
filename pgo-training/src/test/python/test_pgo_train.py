# Copyright 2017-2026 original authors

import ast
import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "pgo_train", Path(__file__).parents[2] / "main/python/pgo_train.py"
)
pgo_train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pgo_train)


class WindowsLauncherTest(unittest.TestCase):
    def test_core_override_precedes_jvm_tool_jars(self):
        for override in (None, "C:\\Core fix\\override.jar"):
            with self.subTest(override=override), tempfile.TemporaryDirectory() as directory:
                install = Path(directory) / "install"
                (install / "lib").mkdir(parents=True)
                trainer = pgo_train.Trainer.__new__(pgo_train.Trainer)
                trainer.options = SimpleNamespace(dev_install_dir=str(install), java_home="C:\\Java")
                trainer.shims = Path(directory) / "shims"
                environment = {"PYRONAUT_WINDOWS_GRAALPY_OVERRIDE_JAR": override} if override else {}
                with patch.object(pgo_train, "os", SimpleNamespace(name="nt", environ=environment)), \
                     patch.object(trainer, "_write_windows_shim", return_value=Path(directory)) as write, \
                     patch.object(trainer, "_copy_manifests"):
                    trainer._write_jvm_dev_shim()
                script = ast.parse(write.call_args.args[1])
                command = next(node.value for node in script.body if isinstance(node, ast.Assign)
                               and any(isinstance(target, ast.Name) and target.id == "command" for target in node.targets))
                classpath = next(command.elts[index + 1].value for index, node in enumerate(command.elts)
                                 if isinstance(node, ast.Constant) and node.value == "-cp")
                expected = str(install.resolve() / "lib" / "*")
                self.assertEqual((override + ";" if override else "") + expected, classpath)


if __name__ == "__main__":
    unittest.main()
