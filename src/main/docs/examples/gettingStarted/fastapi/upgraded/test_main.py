from typing import Annotated

import requests

from jakarta.inject import Inject
from micronaut.context import ApplicationContext
from micronaut.test.extensions.junit5.annotation import MicronautTest
from pyronaut.build import Dependency


Dependency(
    group="io.micronaut.pyronaut",
    module="micronaut-pyronaut-requests",
    scope=Dependency.Scope.TEST,
)


MicronautTest()

context: Annotated[ApplicationContext, Inject]

def client():
    return requests.with_context(context)

def test_root():
    response = client().get("/")
    assert response.json() == {"Hello": "World"}

def test_read_item():
    response = client().get("/items/5", params={"q": "somequery"})
    assert response.json() == {
        "item_id": 5,
        "q": "somequery",
    }

def test_update_item():
    response = client().put(
        "/items/5",
        json={"name": "Foo", "price": 42.0, "is_offer": True},
    )
    assert response.json() == {
        "item_name": "Foo",
        "item_id": 5,
    }

def test_item_validation():
    response = client().put(
        "/items/5",
        json={"name": "", "price": -1},
    )
    assert response.status_code == 400
