import pytest
import requests

from pyronaut.test import MicronautTest, micronaut_test_fixture


@pytest.fixture(scope="module")
def context(request):
    context = micronaut_test_fixture(request, MicronautTest())
    yield context
    context.stop()


@pytest.fixture
def client(context):
    return requests.with_context(context)


def test_root(client):
    response = client.get("/")
    assert response.json() == {"Hello": "World"}


def test_read_item(client):
    response = client.get("/items/5", params={"q": "somequery"})
    assert response.json() == {"item_id": 5, "q": "somequery"}


def test_update_item(client):
    response = client.put(
        "/items/5",
        json={"name": "Foo", "price": 42.0, "is_offer": True},
    )
    assert response.json() == {"item_name": "Foo", "item_id": 5}


def test_item_validation(client):
    response = client.put("/items/5", json={"name": "", "price": -1})
    assert response.status_code == 400
