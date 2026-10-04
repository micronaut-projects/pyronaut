# Copyright 2017-2026 original authors

import importlib.util
import io
import json
import sys
import tempfile
import threading
import time
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, call, patch


_SCRIPT = Path(__file__).resolve().parents[4] / "pgo-training/src/main/python/pgo_train.py"
_SPEC = importlib.util.spec_from_file_location("pgo_train", _SCRIPT)
training = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(training)


class PgoTrainingTest(unittest.TestCase):
    def test_workload_closes_connections_without_waiting_for_garbage_collection(self):
        connections = []  # Keep references alive to model GraalPy's non-refcounted cleanup.
        lock = threading.Lock()

        class Connection:
            def __init__(self, *args, **kwargs):
                with lock:
                    if sum(not connection.closed for connection in connections) >= 64:
                        raise BlockingIOError(115, "message size")
                    self.closed = False
                    connections.append(self)

            def request(self, method, path, body=None, headers=None):
                self.assert_open()
                time.sleep(0.002)  # Give all eight workers requests on each batch.
                status = 200
                if path == "/pets/999999999":
                    status = 404
                elif method == "POST" and path == "/pets":
                    try:
                        status = 201 if json.loads(body)["name"] else 400
                    except ValueError:
                        status = 400
                elif method == "DELETE":
                    status = 204
                self.response = types.SimpleNamespace(status=status, read=lambda: b'{"id": 1}')

            def assert_open(self):
                if self.closed:
                    raise AssertionError("Reused a closed connection")

            def getresponse(self):
                return self.response

            def close(self):
                self.closed = True

        with patch.object(training.http.client, "HTTPConnection", Connection):
            training.Workload(0.1, has_summary=True).run(training.Client(8080))
        self.assertGreater(len(connections), 64)
        self.assertTrue(all(connection.closed for connection in connections))

    def test_failed_batch_closes_connections(self):
        connection = Mock()
        client = training.Client(8080)

        def fail(_):
            client._connection()
            raise training.TrainingError("workload failed")

        with patch.object(training.http.client, "HTTPConnection", return_value=connection):
            with self.assertRaisesRegex(training.TrainingError, "workload failed"):
                training.Workload(1, False)._parallel(client, 8, 8, fail)
        connection.close.assert_called()
        self.assertEqual([], client.connections)

    def test_ready_probe_closes_connection(self):
        connection = Mock()
        connection.getresponse.return_value.status = 200
        trainer = object.__new__(training.Trainer)
        trainer.options = types.SimpleNamespace(startup_timeout=1)
        process = Mock()
        process.poll.return_value = None
        with patch.object(training.http.client, "HTTPConnection", return_value=connection):
            trainer._wait_ready(8080, process, Path("unused.log"))
        connection.close.assert_called_once()

    def test_shutdown_failure_does_not_mask_workload_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            trainer = object.__new__(training.Trainer)
            trainer.logs = Path(directory)
            trainer.skipped = Mock(return_value=False)
            trainer.cli = Mock(return_value=["graalpy", "-m", "pyronaut_cli_v2"])
            trainer.env = Mock(return_value={})
            trainer._wait_ready = Mock()
            trainer._stop = Mock(side_effect=training.TrainingError("shutdown failed"))
            trainer._record = Mock()
            workload = Mock()
            for workload_error, expected in ((training.TrainingError("HTTP workload failed"), "HTTP workload failed"),
                                             (None, "shutdown failed")):
                with self.subTest(workload_error=workload_error):
                    workload.run.side_effect = workload_error
                    output = io.StringIO()
                    with patch.object(training.subprocess, "Popen") as popen, redirect_stdout(output):
                        with self.assertRaisesRegex(training.TrainingError, expected):
                            trainer.serve("dev-run", Path(directory), ["dev"], 8080, workload)
                    self.assertTrue(popen.call_args.kwargs["stdout"].closed)
                    if workload_error:
                        self.assertIn("shutdown also failed: shutdown failed", output.getvalue())
                    else:
                        self.assertIn("workload completed", output.getvalue())
            trainer._record.assert_not_called()

    def test_windows_launchers_get_private_consoles_and_register_their_pids(self):
        scripts = (
            (training.WINDOWS_INSTRUMENTED_SHIM.replace("@EXECUTABLE@", repr("native.exe")), "native.exe"),
            (training.WINDOWS_JVM_DEV_SHIM.replace("@JAVA@", repr("java.exe"))
             .replace("@CLASSPATH@", repr("lib/*")), "java.exe"),
        )
        for script, executable in scripts:
            with self.subTest(executable=executable), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                trainer = object.__new__(training.Trainer)
                trainer.options = types.SimpleNamespace(graalpy="graalpy.exe")
                shim = trainer._write_windows_shim(root / "launcher", script)
                script = shim.with_suffix(".py").read_text()
                kernel32 = Mock()
                ctypes = types.SimpleNamespace(WinDLL=Mock(return_value=kernel32),
                                              get_last_error=lambda: 5,
                                              WinError=lambda code: OSError(code, "console failed"))
                process = Mock(pid=123)
                process.wait.return_value = 0
                env = {"PGO_SCENARIO": "dev-run", "PGO_PROFILES_DIR": str(root / "profiles"),
                       "PGO_PIDS_DIR": str(root / "pids")}
                with patch.dict(sys.modules, {"ctypes": ctypes}), patch.dict(training.os.environ, env), \
                        patch.object(sys, "argv", ["shim", "-Dtest=true", "dev"]), \
                        patch.object(training.subprocess, "CREATE_NEW_CONSOLE", 16, create=True), \
                        patch.object(training.subprocess, "Popen", return_value=process) as popen:
                    order = Mock()
                    order.attach_mock(kernel32.SetConsoleCtrlHandler, "enable_ctrl_c")
                    order.attach_mock(popen, "launch")
                    with self.assertRaises(SystemExit) as exit:
                        exec(script, {})
                    self.assertEqual(call.enable_ctrl_c(None, False), order.mock_calls[0])
                    launch = popen.call_args
                    kernel32.SetConsoleCtrlHandler.return_value = 0
                    popen.reset_mock()
                    with self.assertRaisesRegex(OSError, "console failed"):
                        exec(script, {})
                    popen.assert_not_called()
                self.assertEqual(0, exit.exception.code)
                self.assertEqual({"creationflags": 16, "stdin": training.subprocess.DEVNULL,
                                  "stdout": sys.stdout, "stderr": sys.stderr}, launch.kwargs)
                command = launch.args[0]
                self.assertEqual(executable, command[0])
                self.assertIn("-Dtest=true", command)
                self.assertEqual("dev", command[-1])
                self.assertEqual("123\n", (root / "pids/dev-run.children.pids").read_text())
                if executable == "native.exe":
                    profile = next(argument for argument in command if argument.startswith("-XX:ProfilesDumpFile="))
                    profile_name = Path(profile.split("=", 1)[1]).name
                    self.assertEqual(f"123\t{profile_name}\n", (root / "pids/dev-run.pids").read_text())

    def test_ctrl_c_helper_only_broadcasts_after_attaching_to_child_console(self):
        for failure in (None, "AttachConsole", "SetConsoleCtrlHandler", "GenerateConsoleCtrlEvent"):
            with self.subTest(failure=failure):
                kernel32 = Mock()
                if failure:
                    getattr(kernel32, failure).return_value = 0
                ctypes = types.SimpleNamespace(WinDLL=Mock(return_value=kernel32), c_uint32=int,
                                              c_void_p=int, c_int=int, get_last_error=lambda: 5,
                                              WinError=lambda code: OSError(code, "console failed"))
                with patch.dict(sys.modules, {"ctypes": ctypes}), patch.object(sys, "argv", ["helper", "123"]):
                    if failure:
                        with self.assertRaisesRegex(OSError, "console failed"):
                            exec(training.WINDOWS_CTRL_C_HELPER, {})
                    else:
                        exec(training.WINDOWS_CTRL_C_HELPER, {})
                ctypes.WinDLL.assert_called_once_with("kernel32", use_last_error=True)
                expected = [call.FreeConsole(), call.AttachConsole(123)]
                if failure != "AttachConsole":
                    expected.append(call.SetConsoleCtrlHandler(None, True))
                    if failure != "SetConsoleCtrlHandler":
                        expected.append(call.GenerateConsoleCtrlEvent(0, 0))
                    expected.append(call.FreeConsole())
                self.assertEqual(expected, kernel32.mock_calls)

    def test_windows_shutdown_preserves_native_ctrl_c_and_stops_jvm_tree(self):
        for jvm in (False, True):
            with self.subTest(jvm=jvm):
                trainer = object.__new__(training.Trainer)
                trainer.jvm = jvm
                trainer._stop_windows_children = Mock()
                process = Mock()
                process.poll.return_value = None
                order = Mock()
                order.attach_mock(process, "cli")
                order.attach_mock(trainer._stop_windows_children, "children")
                run = Mock(return_value=types.SimpleNamespace(returncode=0))
                order.attach_mock(run, "taskkill")
                with patch.object(training.os, "name", "nt"), patch.object(training.subprocess, "run", run):
                    trainer._stop("dev-run", process)
                stop = (call.taskkill(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                      capture_output=True, text=True, timeout=30) if jvm else call.cli.kill())
                self.assertEqual([call.cli.poll(), stop, call.cli.wait(), call.children("dev-run")],
                                 order.mock_calls)

    def test_windows_shutdown_requires_children_to_exit_and_profiles_to_exist(self):
        failures = (None, types.SimpleNamespace(returncode=1, stderr="cannot attach"),
                    OSError("cannot start helper"), training.subprocess.TimeoutExpired("helper", 30))
        for failure in failures:
            with self.subTest(failure=failure):
                trainer = object.__new__(training.Trainer)
                trainer.options = types.SimpleNamespace(graalpy="graalpy.exe", shutdown_timeout=0)
                trainer._pids = Mock(return_value=[123])
                run = Mock(return_value=types.SimpleNamespace(returncode=0, stderr=""))
                if isinstance(failure, Exception):
                    run.side_effect = failure
                elif failure is not None:
                    run.return_value = failure
                with patch.object(training, "pid_alive", side_effect=[True, failure is not None]), \
                        patch.object(training.subprocess, "run", run), \
                        patch.object(training.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
                        patch.object(training.os, "kill") as kill, patch.object(training, "log"):
                    if failure is None:
                        trainer._stop_windows_children("dev-run")
                        kill.assert_not_called()
                    else:
                        with self.assertRaisesRegex(training.TrainingError, "ignored Ctrl\\+C"):
                            trainer._stop_windows_children("dev-run")
                        kill.assert_called_once_with(123, training.signal.SIGTERM)
                run.assert_called_once_with(["graalpy.exe", "-c", training.WINDOWS_CTRL_C_HELPER, "123"],
                                            creationflags=0x08000000, capture_output=True, text=True, timeout=30)
        with tempfile.TemporaryDirectory() as directory:
            trainer.jvm = False
            trainer.image = "pyronaut-dev"
            trainer.profiles = Path(directory)
            trainer._launcher_profiles = Mock(return_value=[(123, trainer.profiles / "dev-run-123.iprof")])
            with self.assertRaisesRegex(training.TrainingError, "no profile written"):
                trainer._record("dev-run")

    def test_windows_liveness_waits_without_sending_signals(self):
        api = types.SimpleNamespace(SYNCHRONIZE=0x100000, WAIT_TIMEOUT=258,
                                    OpenProcess=Mock(return_value=2**40),
                                    WaitForSingleObject=Mock(), CloseHandle=Mock())
        with patch.dict(sys.modules, {"_winapi": api}), patch.object(training.os, "name", "nt"), \
                patch.object(training.os, "kill") as kill:
            for result, expected in ((258, True), (0, False)):
                api.WaitForSingleObject.return_value = result
                self.assertEqual(expected, training.pid_alive(123))
                api.OpenProcess.assert_called_with(api.SYNCHRONIZE, False, 123)
                api.WaitForSingleObject.assert_called_with(2**40, 0)
            self.assertEqual(2, api.CloseHandle.call_count)
            self.assertFalse(training.pid_alive(0))
            kill.assert_not_called()

    def test_windows_liveness_handles_missing_pid_and_closes_failed_wait(self):
        api = types.SimpleNamespace(SYNCHRONIZE=0x100000, WAIT_TIMEOUT=258,
                                    OpenProcess=Mock(), WaitForSingleObject=Mock(), CloseHandle=Mock())
        missing = OSError(22, "Invalid argument")
        missing.winerror = 87
        with patch.dict(sys.modules, {"_winapi": api}), patch.object(training.os, "name", "nt"):
            api.OpenProcess.side_effect = missing
            self.assertFalse(training.pid_alive(123))
            api.OpenProcess.side_effect = PermissionError("Access denied")
            self.assertTrue(training.pid_alive(123))
            api.OpenProcess.side_effect = OSError("Unexpected failure")
            with self.assertRaisesRegex(OSError, "Unexpected failure"):
                training.pid_alive(123)
            api.CloseHandle.assert_not_called()
            api.OpenProcess.side_effect = None
            api.OpenProcess.return_value = 1234
            api.WaitForSingleObject.side_effect = OSError("Wait failed")
            with self.assertRaisesRegex(OSError, "Wait failed"):
                training.pid_alive(123)
            api.CloseHandle.assert_called_once_with(1234)

    def test_posix_liveness_keeps_signal_zero_probe(self):
        with patch.object(training.os, "name", "posix"), patch.object(training.os, "kill") as kill:
            self.assertTrue(training.pid_alive(123))
            kill.assert_called_once_with(123, 0)
            kill.side_effect = ProcessLookupError()
            self.assertFalse(training.pid_alive(123))


if __name__ == "__main__":
    unittest.main()
