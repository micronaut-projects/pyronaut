import importlib.util
import json
import sys
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


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
