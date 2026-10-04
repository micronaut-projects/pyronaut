import pytest

from pyronaut.test import *
from helloworld.services import ClasspathResourceService


@pytest.fixture
def resource_context(request):
    fixture = micronaut_test_fixture(request, MicronautTest())
    yield fixture
    fixture.stop()


@pytest.fixture
def resource_service(resource_context) -> ClasspathResourceService:
    return resource_context["helloworld.ClasspathResourceService"]


def test_resource_resolver_reads_classpath_resource(resource_service: ClasspathResourceService):
    # Native launchers must register io.micronaut.core.io for reflection so that
    # Python can call ResourceResolver through host access.
    assert '[micronaut.application]' in resource_service.read_text("application.toml")


def test_resource_resolver_finds_classpath_loader(resource_service: ClasspathResourceService):
    assert resource_service.has_classpath_loader()
    assert resource_service.has_supporting_loader("classpath:")
