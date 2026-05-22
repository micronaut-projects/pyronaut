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
                 properties: Dict[str, Any] = {}):
        self.environments = environments or []
        self.packages = packages or []
        self.transactional = transactional
        self.rollback = rollback
        self.rebuild_context = rebuild_context
        self.start_application = start_application
        self.resolve_parameters = resolve_parameters
        self.context_builder = context_builder
        self.properties = properties or {}


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

class ApplicationContextWrapper:
    def __init__(self, java_app_context):
        """
        :param java_app_context: The Java ApplicationContext instance
                                 (accessed via polyglot, e.g., polyglot_context.eval("java", "new your.package.ApplicationContext()"))
        """
        self.java_ctx = java_app_context

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
        return fqn

    def _foreign_java_class_name(self, key):
        if type(key).__module__ != "polyglot":
            return None
        match = JAVA_CLASS_RE.search(repr(key))
        if match is None:
            return None
        return match.group(1)

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
__all__ = ['MicronautTest', 'micronaut_test_fixture']
