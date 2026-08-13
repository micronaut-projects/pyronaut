from typing import Annotated

import requests

from jakarta.inject import Inject
from micronaut.context import ApplicationContext
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test
from pyronaut.build import Dependency


Dependency(
    group="io.micronaut.pyronaut",
    module="micronaut-pyronaut-requests",
    scope=Dependency.Scope.TEST,
)


@MicronautTest
class AppTest:
    context: Annotated[ApplicationContext, Inject]

    def client(self):
        return requests.with_context(self.context)

    @Test
    def test_root(self) -> None:
        response = self.client().get("/")
        assert response.json() == {"Hello": "World"}

    @Test
    def test_read_item(self) -> None:
        response = self.client().get("/items/5", params={"q": "somequery"})
        assert response.json() == {
            "item_id": 5,
            "q": "somequery",
        }

    @Test
    def test_update_item(self) -> None:
        response = self.client().put(
            "/items/5",
            json={"name": "Foo", "price": 42.0, "is_offer": True},
        )
        assert response.json() == {
            "item_name": "Foo",
            "item_id": 5,
        }

    @Test
    def test_item_validation(self) -> None:
        response = self.client().put(
            "/items/5",
            json={"name": "", "price": -1},
        )
        assert response.status_code == 400
