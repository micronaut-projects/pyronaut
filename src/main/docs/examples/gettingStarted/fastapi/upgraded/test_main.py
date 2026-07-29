import json
from typing import Annotated

from jakarta.inject import Inject
from micronaut.http import HttpRequest, HttpStatus, MediaType
from micronaut.http.client import HttpClient
from micronaut.http.client.annotation import Client
from micronaut.http.client.exceptions import HttpClientResponseException
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test


@MicronautTest
class AppTest:
    client: Annotated[HttpClient, Client("/"), Inject]

    @Test
    def test_root(self) -> None:
        body = self.client.toBlocking().retrieve("/")
        assert json.loads(body) == {"Hello": "World"}

    @Test
    def test_read_item(self) -> None:
        body = self.client.toBlocking().retrieve("/items/5?q=somequery")
        assert json.loads(body) == {
            "item_id": 5,
            "q": "somequery",
        }

    @Test
    def test_update_item(self) -> None:
        request = HttpRequest.PUT(
            "/items/5",
            '{"name":"Foo","price":42.0,"is_offer":true}',
        ).contentType(MediaType.APPLICATION_JSON_TYPE)
        body = self.client.toBlocking().retrieve(request)
        assert json.loads(body) == {
            "item_name": "Foo",
            "item_id": 5,
        }

    @Test
    def test_item_validation(self) -> None:
        request = HttpRequest.PUT(
            "/items/5",
            '{"name":"","price":-1}',
        ).contentType(MediaType.APPLICATION_JSON_TYPE)
        try:
            self.client.toBlocking().retrieve(request)
            assert False, "Expected validation to reject the request"
        except HttpClientResponseException as error:
            assert error.getStatus() == HttpStatus.BAD_REQUEST
