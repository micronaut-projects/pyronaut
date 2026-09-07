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
    "com.oracle.svm.",
    "com.oracle.graal.python.",
    "org.graalvm.polyglot.",
    "org.graalvm.nativeimage.builder/",
    "org.graalvm.python.embedding.",
    "org.graalvm.truffle.runtime.svm/",
    "org.junit.platform.",
    "picocli.",
    "io.micronaut.pyronaut.test.PyronautTestMain",
    "io.micronaut.test.pytest.",
    "at java.base/",
)


def _foreign_exception_failure(outcome):
    excinfo = getattr(outcome, 'excinfo', None)
    if excinfo is None:
        return None

    if isinstance(excinfo, tuple):
        if len(excinfo) >= 2:
            exc = excinfo[1]
        elif len(excinfo) == 1:
            exc = excinfo[0]
        else:
            exc = None
    else:
        exc = getattr(excinfo, 'value', excinfo)

    if exc is None:
        return None

    message = _foreign_exception_message(exc)
    if message is not None:
        return message
    name = getattr(getattr(exc, '__class__', type(exc)), '__name__', '')
    text = f"{exc}"
    if name == 'TypeError' and 'ForeignException' in text:
        return f"Java exception raised during pytest execution: {text}"
    return None


def _foreign_exception_message(exc):
    if exc is None:
        return None
    name = getattr(getattr(exc, '__class__', type(exc)), '__name__', '')
    module = getattr(getattr(exc, '__class__', type(exc)), '__module__', '')
    if name == 'ForeignException' or 'Foreign' in name or module == 'polyglot':
        message = f"{exc}"
        return message if message else "Java exception raised during pytest execution"
    return None


def _force_python_failure(outcome, message: str) -> bool:
    if message is None:
        return False
    outcome.force_exception(AssertionError(message))
    return True


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


def _compact_assertion_failure(result) -> str:
    if isinstance(result, str):
        filtered_stack = _filter_internal_traceback_frames(result)
        lines = [line.rstrip() for line in filtered_stack.splitlines() if line.strip()]
        return lines[0] if lines else "Python test function failed"
    message = getattr(result, "message", None)
    stack = getattr(result, "stack", None)
    if stack:
        filtered_stack = _filter_internal_traceback_frames(stack)
        lines = [line.rstrip() for line in filtered_stack.splitlines() if line.strip()]
        if lines:
            return "\n".join(lines)
    return message or getattr(result, "exceptionClass", None) or "Python test function failed"


def _is_foreign_exception(result) -> bool:
    exception_class = getattr(result, "exceptionClass", None) or ""
    stack = getattr(result, "stack", None) or ""
    if (exception_class.startswith("java.")
            or exception_class.startswith("javax.")
            or exception_class.startswith("io.micronaut.")):
        return False
    return (
        "ForeignException" in exception_class
        or "PolyglotException" in exception_class
        or "ForeignException" in stack
        or "org.graalvm.polyglot" in stack
    )


def _format_call_failure(result) -> str:
    message = getattr(result, "message", None)
    if _is_foreign_exception(result):
        return message or "Java exception raised during pytest execution"
    exception_class = getattr(result, "exceptionClass", None) or ""
    if message and (exception_class.startswith("java.")
                    or exception_class.startswith("javax.")
                    or exception_class.startswith("io.micronaut.")):
        return f"{exception_class}: {message}"
    return _compact_assertion_failure(result)


class _ExpectedFailure:
    def __init__(self, reason: str):
        self.reason = reason or "Expected failure"


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
        self.failure_messages = {}  # Store pytest-rendered failure details by test id
        self._failure_reported = set()  # Track tests for which failure block was forwarded

    def pytest_sessionstart(self, session):
        """Called when pytest session starts."""
        pass

    @pytest.hookimpl(hookwrapper=True, tryfirst=True)
    def pytest_fixture_setup(self, fixturedef, request):
        outcome = yield
        failure = _foreign_exception_failure(outcome)
        _force_python_failure(outcome, failure)

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
        """Run Python test functions behind the Java foreign-exception boundary."""
        testfunction = pyfuncitem.obj
        if inspect.iscoroutinefunction(testfunction):
            return None
        fixtureinfo = getattr(pyfuncitem, "_fixtureinfo", None)
        if fixtureinfo is None:
            return None

        testargs = {
            arg: pyfuncitem.funcargs[arg]
            for arg in fixtureinfo.argnames
        }

        result = PytestFunctionInvoker.call(lambda: testfunction(**testargs))
        if getattr(result, "success", False):
            return True
        pytest.fail(_format_call_failure(result), pytrace=False)
        return True

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
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            outcome = yield
        failure = _foreign_exception_failure(outcome)
        if _force_python_failure(outcome, failure):
            self._pending_wrap = (test_id, "", "")
            return
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
            if failure_text:
                self.failure_messages[test_id] = failure_text
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
            result = self.listener.failedAssertionResult(
                self.failure_messages.get(test_id) or f"{exception}"
            )
        else:
            result = self.listener.successfulResult()

        self.listener.afterTest(test_id, item, result)

        # Clean up stored result
        self.test_results.pop(test_id, None)
        self.failure_messages.pop(test_id, None)

    def pytest_collectreport(self, report):
        """Called when collection report is generated."""
        if report.failed:
            file_id = f"{self.current_file or getattr(report, 'fspath', None) or getattr(report, 'nodeid', 'collection')}"
            failure_text = _filter_internal_traceback_frames(f"{report.longrepr}")
            message = f"Collection failed: {failure_text}"
            result = self.listener.failedAssertionResult(message)
            self.listener.onOutput(file_id, "log", message)
            self.listener.afterFile(file_id, result)
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
