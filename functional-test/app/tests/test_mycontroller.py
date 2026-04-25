import pytest
import requests

from pyronaut.test import *
from helloworld.services import MessageService
from micronaut.runtime.server import EmbeddedServer

@pytest.fixture
def my_context(request):
    fixture = micronaut_test_fixture(request, MicronautTest(environments=["foo"], transactional=False,
                                                            properties={"custom.property": "test_value"}))
    yield fixture
    fixture.stop()

@pytest.fixture
def base_url(my_context):
    server = my_context[EmbeddedServer]
    return f"http://localhost:{server.getPort()}"

def test_hello_world(base_url):
    r = requests.get(f"{base_url}/hello/John")
    assert r.json()['message'] == "Hello John!!!!!!"
    assert r.status_code == 200

@pytest.fixture
def my_service(my_context) -> MessageService:
    # beans are exposed via their package names for lookup
    return my_context["helloworld.MessageService"]


def test_my_service(my_service : MessageService):
    assert my_service is not None
    assert my_service.say_hello("John") == "Hello John!!!!!!"

def test_context(my_context):
    assert my_context.isRunning()
