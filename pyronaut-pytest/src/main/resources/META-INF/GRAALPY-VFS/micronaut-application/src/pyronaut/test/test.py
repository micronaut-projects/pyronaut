"""
Micronaut test support for pytest.

This module provides pytest fixtures for testing Micronaut applications.
"""

import sys
import java
import re
from typing import Optional, Dict, Any, List, Union

JAVA_CLASS_RE = re.compile(r"JavaClass\[([^\]]+)\]")

class MicronautTest:
    """
    Value object representing Micronaut test configuration.

    Similar to the Java MicronautTestValue used in JUnit5 extension.
    """

    def __init__(self,
                 environments: List[str] = ["test"],
                 packages: List[str] = [],
                 transactional: bool = False,
                 rollback: bool = True,
                 rebuild_context: bool = False,
                 start_application: bool = True,
                 resolve_parameters: bool = True,
                 context_builder: Any = None,
                 properties: Dict[str, Any] = {},
                 sql: Any = None):
        self.environments = environments or []
        self.packages = packages or []
        self.transactional = transactional
        self.rollback = rollback
        self.rebuild_context = rebuild_context
        self.start_application = start_application
        self.resolve_parameters = resolve_parameters
        self.context_builder = context_builder
        self.properties = properties or {}
        self.sql = sql


class Sql:
    """
    SQL scripts to execute during Micronaut pytest lifecycle phases.
    """

    class Phase:
        BEFORE_ALL = "BEFORE_ALL"
        BEFORE_EACH = "BEFORE_EACH"
        AFTER_ALL = "AFTER_ALL"
        AFTER_EACH = "AFTER_EACH"

    def __init__(self,
                 scripts: Union[str, List[str]],
                 phase: str = Phase.BEFORE_ALL,
                 data_source_name: str = "default",
                 resource_type: str = "javax.sql.DataSource"):
        if isinstance(scripts, str):
            scripts = [scripts]
        self.scripts = scripts or []
        self.phase = phase
        self.data_source_name = data_source_name
        self.resource_type = resource_type

    def as_dict(self) -> Dict[str, Any]:
        return {
            "scripts": self.scripts,
            "phase": self.phase,
            "dataSourceName": self.data_source_name,
            "resourceType": self.resource_type,
        }


# Convenience function for pytest fixtures
def micronaut_test_fixture(request,
                            micronaut_test: MicronautTest = None):
    """
    Create a MicronautTest configuration for pytest fixtures.

    This is a convenience function that can be used directly in pytest fixtures:

    @pytest.fixture
    def my_context(request):
        fixture = micronaut_test_fixture(request, MicronautTest(environments="test",
                                                                properties={"custom.property": "test_value"}))
        yield fixture
        fixture.stop()
    """

    if micronaut_test is None:
        micronaut_test = MicronautTest()

    PytestMicronautExtension = java.type("io.micronaut.test.pytest.extension.PytestMicronautExtension")
    bootstrap = PytestMicronautExtension.bootstrapFixture(
        micronaut_test.properties,
        request.node,
        to_java_array(micronaut_test.environments),
        to_java_array(micronaut_test.packages),
        to_java_array([]), # propertySources
        to_sql_configs(micronaut_test.sql),
        micronaut_test.rollback,
        micronaut_test.transactional,
        micronaut_test.rebuild_context,
        micronaut_test.start_application,
        micronaut_test.resolve_parameters
    )

    start_error = bootstrap.getError()
    if start_error:
        pytest = __import__("pytest")
        pytest.fail(f"Micronaut Fixture Setup Failed: {start_error}", pytrace=False)
    return ApplicationContextWrapper(bootstrap.getContext())


def to_java_array(list):
    StringArray = java.type("java.lang.String[]")
    arr = StringArray(len(list))
    for i, name in enumerate(list):
        arr[i] = name

    return arr


def to_sql_configs(sql):
    if sql is None:
        return []
    if isinstance(sql, Sql):
        sql = [sql]
    configs = []
    for item in sql:
        if isinstance(item, Sql):
            configs.append(item.as_dict())
        else:
            configs.append(item)
    return configs

class ApplicationContextWrapper:
    def __init__(self, java_app_context):
        """
        :param java_app_context: The Java ApplicationContext instance
                                 (accessed via polyglot, e.g., polyglot_context.eval("java", "new your.package.ApplicationContext()"))
        """
        self.java_ctx = java_app_context

    def stop(self):
        return self._invoke_lifecycle("stop")

    def close(self):
        return self._invoke_lifecycle("close")

    def _invoke_lifecycle(self, method_name):
        method = getattr(self.java_ctx, method_name, None)
        if method is None:
            raise AttributeError(
                f"'{type(self).__name__}' object has no attribute '{method_name}' (not found on Java context)"
            )

        PytestFunctionInvoker = java.type("io.micronaut.test.pytest.execution.PytestFunctionInvoker")
        result = PytestFunctionInvoker.call(method)
        if not getattr(result, "success", False):
            raise RuntimeError(self._format_lifecycle_failure(method_name, result))
        return self

    def _format_lifecycle_failure(self, method_name, result):
        stack = getattr(result, "stack", None)
        if stack:
            return f"Micronaut application context {method_name} failed:\n{stack}"

        message = getattr(result, "message", None)
        exception_class = getattr(result, "exceptionClass", None)
        if message and exception_class:
            return f"Micronaut application context {method_name} failed: {exception_class}: {message}"
        if message:
            return f"Micronaut application context {method_name} failed: {message}"
        if exception_class:
            return f"Micronaut application context {method_name} failed: {exception_class}"
        return f"Micronaut application context {method_name} failed"

    def __getitem__(self, key):
        """
        Supports ctx["Foo"] notation.
        Delegates to the Java method (e.g., getBean(String)) and raises KeyError if not found.
        """
        bean_class, lookup_key = self._resolve_bean_key(key)
        result = self._find_bean(bean_class, lookup_key)
        if hasattr(result, 'asPolyglotValue'):
            return result.asPolyglotValue()
        return result

    def _resolve_bean_key(self, key):
        if isinstance(key, str):
            try:
                return java.type(key), key
            except BaseException:
                raise KeyError(f"Key '{key}' not found in context")

        if isinstance(key, type):
            lookup_key = self._python_type_to_lookup_key(key)
            try:
                return java.type(lookup_key), lookup_key
            except BaseException:
                class_name = key.__name__
                if lookup_key == f"{class_name}.{class_name}":
                    fallback_key = f"python.{class_name}"
                    try:
                        return java.type(fallback_key), fallback_key
                    except BaseException:
                        pass
                raise KeyError(f"Key '{lookup_key}' not found in context")

        lookup_key = self._foreign_java_class_name(key)
        if lookup_key is not None:
            try:
                return java.type(lookup_key), lookup_key
            except BaseException:
                return key, lookup_key

        raise TypeError(f"Unsupported key type: {type(key)}")

    def _python_type_to_lookup_key(self, key):
        module_name = key.__module__
        qualname = key.__qualname__
        class_name = key.__name__
        if module_name == '__main__' or module_name.startswith('__'):
            raise ValueError(f"Cannot resolve FQN for class '{key.__name__}' in module '{module_name}'")

        fqn = f"{module_name}.{qualname}"
        if module_name.endswith(f'.{class_name}'):
            stripped_module = module_name[:-len(f'.{class_name}')]
            fqn = f"{stripped_module}.{qualname}" if stripped_module else qualname
        else:
            snake_class_name = self._camel_to_snake(class_name)
            if module_name.endswith(f'.{snake_class_name}'):
                stripped_module = module_name[:-len(f'.{snake_class_name}')]
                fqn = f"{stripped_module}.{qualname}" if stripped_module else qualname
        return fqn

    def _camel_to_snake(self, name):
        value = re.sub("(.)([A-Z][a-z]+)", r"\1_\2", name)
        return re.sub("([a-z0-9])([A-Z])", r"\1_\2", value).lower()

    def _foreign_java_class_name(self, key):
        key_type = type(key)
        if key_type.__module__ == "polyglot":
            match = JAVA_CLASS_RE.search(repr(key))
            return match.group(1) if match is not None else None
        module_name = getattr(key, "__module__", None)
        class_name = getattr(key, "__name__", None)
        if module_name and class_name and str(module_name).startswith("micronaut."):
            return f"io.{module_name}.{class_name}"
        match = JAVA_CLASS_RE.search(repr(key))
        return match.group(1) if match is not None else None

    def _find_bean(self, bean_class, lookup_key):
        try:
            findBean = getattr(self.java_ctx, 'findBean', None)
            if findBean is not None:
                opt = findBean(bean_class)
                present = opt is not None and (not hasattr(opt, 'isPresent') or opt.isPresent())
                if not present:
                    raise KeyError(f"Key '{lookup_key}' not found in context")
                return opt.get() if hasattr(opt, 'get') else opt
            return self.java_ctx.getBean(bean_class)
        except BaseException:
            raise KeyError(f"Key '{lookup_key}' not found in context")

    def __getattr__(self, name):
        """
        Delegates all other attribute/method accesses to the Java ApplicationContext.
        This enables transparent proxying: wrapper.getBeanCount() -> java_ctx.getBeanCount()
        Avoids infinite recursion by not delegating Python dunder methods (e.g., __getattr__ itself).
        """
        if name.startswith("__") and name.endswith("__"):
            # Skip Python special methods to prevent recursion or unexpected behavior
            raise AttributeError(f"'{type(self).__name__}' object has no attribute '{name}'")

        # Forward to Java object
        attr = getattr(self.java_ctx, name, None)
        if attr is None:
            raise AttributeError(f"'{type(self).__name__}' object has no attribute '{name}' (not found on Java context)")
        return attr

    # Optional: Add other Pythonic methods for completeness (these override delegation if needed)
    def __contains__(self, key):
        """Supports 'if "Foo" in ctx:'"""
        try:
            self[key]  # Will raise KeyError if missing
            return True
        except KeyError:
            return False

    def get(self, key, default=None):
        """Python dict-like get() for optional default"""
        try:
            return self[key]
        except KeyError:
            return default

# Export the function for use in pytest fixtures
__all__ = ['MicronautTest', 'Sql', 'micronaut_test_fixture']
