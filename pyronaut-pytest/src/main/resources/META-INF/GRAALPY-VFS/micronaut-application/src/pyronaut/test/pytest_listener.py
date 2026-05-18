"""
Pytest plugin for Micronaut pytest-engine integration.

This plugin hooks into pytest's event system to communicate test execution
events back to Java code via the PytestTestListener interface.
"""

import pytest
from typing import Optional, Any
import sys
import traceback
import java
import inspect
from typing import get_origin, get_args

PytestFunctionInvoker = java.type("io.micronaut.test.pytest.execution.PytestFunctionInvoker")


_INTERNAL_TRACE_MARKERS = (
    "com.oracle.truffle.",
    "com.oracle.graal.python.",
    "org.graalvm.polyglot.",
    "org.graalvm.python.embedding.",
    "at java.base/",
)


def _filter_internal_traceback_frames(text: str) -> str:
    if not text:
        return text

    filtered = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("at ") and any(marker in stripped for marker in _INTERNAL_TRACE_MARKERS):
            continue
        filtered.append(line)

    return "\n".join(filtered)


class _ExpectedFailure:
    def __init__(self, reason: str):
        self.reason = reason or "Expected failure"


def _is_foreign_exception(exc: BaseException) -> bool:
    name = getattr(getattr(exc, "__class__", type(exc)), "__name__", "")
    return name == "ForeignException" or "Foreign" in name or (
        isinstance(exc, TypeError)
        and "exceptions must be classes or instances deriving from BaseException, not ForeignException" in str(exc)
    )


def _format_call_failure(result) -> str:
    message = getattr(result, "message", None)
    exception_class = getattr(result, "exceptionClass", None)
    stack = getattr(result, "stack", None)
    if stack:
        return _filter_internal_traceback_frames(str(stack))
    if message:
        return str(message)
    if exception_class:
        return str(exception_class)
    return "Python test failed with a foreign exception"


class MicronautPytestPlugin:
    """
    Pytest plugin that communicates with Java PytestTestListener.
    """

    def __init__(self, listener: Any):
        """
        Initialize the plugin with a Java listener object.

        Args:
            listener: Java object implementing PytestTestListener interface
        """
        self.listener = listener
        self.current_file = None
        self.test_results = {}  # Store test results by test id
        self._failure_reported = set()  # Track tests for which failure block was forwarded

    def pytest_sessionstart(self, session):
        """Called when pytest session starts."""
        pass

    @pytest.hookimpl(hookwrapper=True, tryfirst=True)
    def pytest_fixture_setup(self, fixturedef, request):
        outcome = yield
        excinfo = getattr(outcome, 'excinfo', None)
        if excinfo is not None:
            exc = None
            if isinstance(excinfo, tuple):
                if len(excinfo) >= 2:
                    exc = excinfo[1]
                elif len(excinfo) == 1:
                    exc = excinfo[0]
            else:
                exc = getattr(excinfo, 'value', excinfo)
            if exc is not None:
                name = getattr(exc, '__class__', type(exc)).__name__
                if name == 'ForeignException' or 'Foreign' in name:
                    outcome.force_result(None)
                    pytest.fail(f"{exc}", pytrace=False)

    def pytest_sessionfinish(self, session, exitstatus):
        """Called when pytest session finishes."""
        # Convert pytest exit status to JUnit TestExecutionResult
        if exitstatus == pytest.ExitCode.OK:
            result = self.listener.successfulResult()
        else:
            result = self.listener.failedResult(f"Pytest session failed with exit code: {exitstatus}")

        self.listener.onResult(result)

    def pytest_collectstart(self, collector):
        """Called when collection starts for a file."""
        if hasattr(collector, 'fspath') and collector.fspath:
            self.current_file = collector.fspath
            self.listener.beforeFile(f"{collector.fspath}")

    @pytest.hookimpl(tryfirst=True)
    def pytest_pyfunc_call(self, pyfuncitem):
        testfunction = pyfuncitem.obj
        if inspect.iscoroutinefunction(testfunction):
            return None
        fixtureinfo = getattr(pyfuncitem, "_fixtureinfo", None)
        argnames = getattr(fixtureinfo, "argnames", ())
        testargs = {arg: pyfuncitem.funcargs[arg] for arg in argnames}

        def invoke():
            testfunction(**testargs)

        result = PytestFunctionInvoker.call(invoke)
        if getattr(result, "success", False):
            return True
        pytest.fail(_format_call_failure(result), pytrace=False)

    @pytest.hookimpl(hookwrapper=True, tryfirst=True)
    def pytest_runtest_call(self, item):
        """Wrap the test call to capture stdout/stderr reliably and forward to Java.
        Avoid double emission with pytest capture by not forwarding here when pytest has already captured output.
        """
        test_id = self._get_test_id(item)
        # Notify Java before executing the test body so the engine can mark it started
        self.listener.beforeTest(test_id, item)
        import io, contextlib
        buf_out = io.StringIO()
        buf_err = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
                outcome = yield
        except BaseException as exc:
            if _is_foreign_exception(exc):
                pytest.fail(f"{exc}", pytrace=False)
            raise
        # Heuristic: if pytest capture is active, capstdout/capstderr will be non-empty in logreport(call),
        # so skip forwarding here to avoid duplicates. We only forward here if buffers are non-empty
        # AND we detect that pytest capture is likely disabled (-s) by checking for empty capstdout later.
        out = buf_out.getvalue()
        err = buf_err.getvalue()
        # Only forward here if capture is off (best-effort): if both out/err exist, they'll be forwarded later by logreport
        # so we do nothing here to avoid duplicates.
        # If user ran with -s (capture=no), logreport capstdout/capstderr will be empty → forward here.
        self._pending_wrap = (test_id, out, err)

    def pytest_runtest_makereport(self, item, call):
        """Called when test report is created."""
        test_id = self._get_test_id(item)

        # Store the test result for later use in teardown
        if call.excinfo is not None:
            # Test failed
            self.test_results[test_id] = call.excinfo.value
        else:
            # Test passed
            self.test_results[test_id] = None

    def pytest_runtest_logreport(self, report):
        """Forward captured stdout/stderr and framework-formatted failure details per test phase.
        Also handle -s (capture=no) by emitting wrap-captured buffers if pytest didn't capture.
        """
        test_id = getattr(report, "nodeid", None)
        if not test_id:
            return

        phase = getattr(report, "when", None)
        # Forward captured streams only for the call phase to avoid duplicates
        if phase == "call":
            out = getattr(report, "capstdout", "") or ""
            err = getattr(report, "capstderr", "") or ""
            if out:
                try:
                    self.listener.onOutput(test_id, "stdout", out)
                except Exception:
                    import traceback; traceback.print_exc()
            if err:
                try:
                    self.listener.onOutput(test_id, "stderr", err)
                except Exception:
                    import traceback; traceback.print_exc()

            # If capture is disabled (-s) capstdout/err are empty; emit from wrap buffers once at 'call'
            if not out and not err:
                pend = getattr(self, "_pending_wrap", None)
                if pend and pend[0] == test_id:
                    _, wout, werr = pend
                    if wout:
                        try:
                            self.listener.onOutput(test_id, "stdout", wout)
                        except Exception:
                            import traceback; traceback.print_exc()
                    if werr:
                        try:
                            self.listener.onOutput(test_id, "stderr", werr)
                        except Exception:
                            import traceback; traceback.print_exc()
                    self._pending_wrap = None

        # On failure, forward framework-formatted details similar to terminal output (once per test)
        was_xfail = getattr(report, "wasxfail", None)
        if phase == "call" and was_xfail:
            if getattr(report, "skipped", False):
                self.test_results[test_id] = _ExpectedFailure(str(was_xfail))
                return
            if getattr(report, "failed", False):
                # Strict XPASS is a real failure: preserve it so PendingFeature-style tests fail when fixed.
                self.test_results[test_id] = f"XPASS(strict): {was_xfail}"

        if getattr(report, "failed", False) and test_id not in self._failure_reported:
            self._failure_reported.add(test_id)
            failure_text = getattr(report, "longreprtext", None)
            if not failure_text and getattr(report, "longrepr", None) is not None:
                try:
                    failure_text = str(report.longrepr)
                except Exception:
                    failure_text = None
            if not failure_text:
                failure_text = ""
            else:
                failure_text = _filter_internal_traceback_frames(failure_text)
            sections = getattr(report, "sections", []) or []
            parts = []
            sep = "_" * 53
            parts.append(f"{sep} {test_id} {sep}")
            if failure_text:
                parts.append(failure_text)
            for (name, content) in sections:
                # sections content often already includes trailing newlines; keep as-is
                if content is not None:
                    parts.append(f"{name}\n{content}")
            text = "\n".join(parts)
            try:
                self.listener.onOutput(test_id, "log", text)
            except Exception:
                import traceback; traceback.print_exc()

    def pytest_runtest_teardown(self, item):
        """Called after test teardown."""
        test_id = self._get_test_id(item)
        exception = self.test_results.get(test_id)

        if isinstance(exception, _ExpectedFailure):
            result = self.listener.abortedResult(exception.reason)
        elif exception is not None:
            result = self.listener.failedAssertionResult(f"{exception}")
        else:
            result = self.listener.successfulResult()

        self.listener.afterTest(test_id, item, result)

        # Clean up stored result
        self.test_results.pop(test_id, None)

    def pytest_collectreport(self, report):
        """Called when collection report is generated."""
        if report.failed:
            result = self.listener.failedAssertionResult(f"Collection failed: {report.longrepr}")
            if self.current_file:
                self.listener.afterFile(f"{self.current_file}", result)
                raise Exception(f"Collection failed: {report.longrepr}")
        else:
            if self.current_file:
                self.listener.afterFile(f"{self.current_file}", self.listener.successfulResult())

    def _resolve_bean_for_type(self, context, python_type):
        """Resolve a bean based on Python type hint."""
        if self._is_list_type(python_type):
            # Handle List[SomeType]
            element_type = get_args(python_type)[0]
            java_class_name = self._python_type_to_java_class(element_type)
            if java_class_name:
                try:
                    java_class = java.type(java_class_name)
                    collection = context.getBeansOfType(java_class)
                    return list(collection)  # Convert to Python list
                except:
                    pass
        elif self._is_dict_type(python_type):
            # Handle Dict[str, SomeType]
            key_type, value_type = get_args(python_type)
            if key_type == str:
                java_class_name = self._python_type_to_java_class(value_type)
                if java_class_name:
                    try:
                        java_class = java.type(java_class_name)
                        java_map = context.mapOfType(java_class)
                        return dict(java_map)  # Convert to Python dict
                    except:
                        pass
        else:
            # Handle single bean
            java_class_name = self._python_type_to_java_class(python_type)
            if java_class_name:
                try:
                    return context.getBean(java.type(java_class_name))
                except:
                    pass

        return None

    def _is_list_type(self, python_type):
        """Check if type is List[T]."""
        return get_origin(python_type) is list

    def _is_dict_type(self, python_type):
        """Check if type is Dict[K, V]."""
        return get_origin(python_type) is dict

    def _python_type_to_java_class(self, python_type):
        """Convert Python type to fully qualified Java class name."""
        if hasattr(python_type, '__module__') and hasattr(python_type, '__qualname__'):
            module = python_type.__module__
            qualname = python_type.__qualname__
            return f"{module}.{qualname}"
        return None

    def _get_test_id(self, item) -> str:
        """
        Generate a unique test ID from pytest item.

        Args:
            item: pytest test item

        Returns:
            Unique test identifier string
        """
        if hasattr(item, 'nodeid'):
            return item.nodeid
        else:
            # Fallback for older pytest versions
            return f"{item.parent.name}::{item.name}"


def create_plugin(listener: Any) -> MicronautPytestPlugin:
    """
    Factory function to create the pytest plugin.

    Args:
        listener: Java object implementing PytestTestListener interface

    Returns:
        Configured MicronautPytestPlugin instance
    """
    return MicronautPytestPlugin(listener)
