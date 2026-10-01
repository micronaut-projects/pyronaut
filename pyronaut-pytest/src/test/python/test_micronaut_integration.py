"""
Test file demonstrating Micronaut test integration with pytest.

This test verifies that the MicronautTest fixture works correctly
and provides access to the ApplicationContext.
"""

import pytest
from pyronaut.test import *
import java
from micronaut.context.env import Environment
from micronaut.context import ApplicationContext
from test import Foo, Baz
from Bar import Bar

@pytest.fixture
def my_context(request):
    fixture = micronaut_test_fixture(request, MicronautTest(environments=["foo"],
                                                            properties={"custom.property": "test_value"}))
    yield fixture
    fixture.stop()


def test_micronaut_test_defaults_to_non_transactional():
    assert MicronautTest().transactional is False


def test_micronaut_test_accepts_sql_config():
    sql = Sql("classpath:sql/seed-data.sql", phase=Sql.Phase.BEFORE_EACH)
    config = MicronautTest(sql=sql)

    assert config.sql is sql
    assert sql.as_dict()["scripts"] == ["classpath:sql/seed-data.sql"]
    assert sql.as_dict()["phase"] == "BEFORE_EACH"
    assert sql.as_dict()["dataSourceName"] == "default"
    assert sql.as_dict()["resourceType"] == "javax.sql.DataSource"


@pytest.fixture
def env(my_context) -> ApplicationContext:
    return my_context["io.micronaut.context.env.Environment"]

@pytest.fixture
def foo(my_context : ApplicationContext) -> Foo:
    return my_context[Foo]

@pytest.fixture
def bar(my_context : ApplicationContext) -> Bar:
    return my_context[Bar]

def test_micronaut_context_creation(
        my_context: ApplicationContext,
        env: Environment,
        foo: Foo,
        bar: Bar):
    """
    Test that the Micronaut ApplicationContext is created and accessible.

    This test verifies that the fixture provides access to the context
    and that it's properly configured.
    """
    ctx = my_context

    # Verify the context is created
    assert ctx is not None
    assert env is not None
    assert foo is not None
    assert bar is not None
    assert ctx.isRunning() is True

    # Verify environments are set
    assert "test" in env.getActiveNames()
    assert "test" in ctx.getEnvironment().getActiveNames()
    assert "foo" in ctx.getEnvironment().getActiveNames()


def test_named_python_bean_lookup(my_context: ApplicationContext):
    assert my_context[Foo] is not None
    bean = my_context.get_bean(Baz, name="test")
    assert bean is not None, "qualified lookup did not resolve the @Named('test') bean"
    with pytest.raises(KeyError):
        my_context.get_bean(Baz, name="missing")


def test_micronaut_context_creation2(my_context):
    """
    Test that the Micronaut ApplicationContext is created and accessible.

    This test verifies that the fixture provides access to the context
    and that it's properly configured.
    """
    ctx = my_context

    # Verify the context is created
    assert ctx is not None
    assert ctx.isRunning() is True

    # Verify environments are set
    assert "test" in ctx.getEnvironment().getActiveNames()
    assert ctx.getEnvironment().containsProperty("custom.property")
    assert ctx.getEnvironment().getProperty("custom.property", java.type("java.lang.String")).get() == "test_value"


def test_context_lookup_with_java_type(my_context):
    env = my_context[java.type("io.micronaut.context.env.Environment")]
    assert env is not None
    assert "test" in env.getActiveNames()
