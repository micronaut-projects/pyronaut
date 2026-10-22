import pytest

from jakarta.inject import Singleton
from micronaut.context.annotation import Requires
from pyronaut.test import *


@Singleton
@Requires(property="spec.name", value="TopLevelBeanModuleTest")
class TopLevelTestBean:
    def greet(self) -> str:
        return "declared in a top-level test module"


@pytest.fixture
def context(request):
    fixture = micronaut_test_fixture(request, MicronautTest(properties={"spec.name": "TopLevelBeanModuleTest"}))
    yield fixture
    fixture.stop()


def test_bean_declared_in_top_level_test_module_is_injectable(context):
    # The context imports this module from the application VFS before pytest
    # collects the mirrored copy under __pyronaut__/test-sources; collection must
    # reuse that module rather than fail with an import file mismatch.
    bean = context[TopLevelTestBean]
    assert isinstance(bean, TopLevelTestBean)
    assert bean.greet() == "declared in a top-level test module"
