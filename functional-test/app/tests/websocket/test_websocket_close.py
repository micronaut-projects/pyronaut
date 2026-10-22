from abc import ABC, abstractmethod
from time import sleep

import pytest
from java.lang import AutoCloseable
from micronaut.context.annotation import Requires
from micronaut.runtime.server import EmbeddedServer
from micronaut.websocket import WebSocketClient
from micronaut.websocket.annotation import ClientWebSocket, OnMessage
from pyronaut.test import MicronautTest, micronaut_test_fixture
from reactor.core.publisher import Flux


# Closing a client WebSocket goes from the generated Java close() through the introduction
# proxy to the interceptor; under the native launcher it once recursed through the Python
# bridge until RecursionError (micronaut-projects/pyronaut#123). Both ways a Python client
# gets its close() are covered: declared abstract, and inherited from a Java interface.


@Requires(property="spec.name", value="WebSocketCloseTest")
@ClientWebSocket("/ws/room/{username}")
class AbstractCloseClient(ABC):

    def __init__(self):
        self.messages: list[str] = []

    @OnMessage
    def on_message(self, message: str) -> None:
        self.messages.append(message)

    def send(self, message: str) -> None:
        ...

    @abstractmethod
    def close(self) -> None:
        ...


@Requires(property="spec.name", value="WebSocketCloseTest")
@ClientWebSocket("/ws/room/{username}")
class InheritedCloseClient(ABC, AutoCloseable):

    def __init__(self):
        self.messages: list[str] = []

    @OnMessage
    def on_message(self, message: str) -> None:
        self.messages.append(message)

    @abstractmethod
    def send(self, message: str) -> None:
        ...


@pytest.fixture
def websocket_context(request):
    fixture = micronaut_test_fixture(
        request,
        MicronautTest(transactional=False, properties={"spec.name": "WebSocketCloseTest"}),
    )
    yield fixture
    fixture.stop()


@pytest.fixture
def websocket_client(websocket_context):
    server = websocket_context[EmbeddedServer]
    client = server.getApplicationContext().createBean(WebSocketClient, server.getURI())
    yield client
    client.close()


def connect(websocket_client, client_type, username: str):
    return Flux.from_(websocket_client.connect(client_type, {"username": username})).blockFirst()


def await_condition(condition) -> None:
    for _ in range(100):
        if condition():
            return
        sleep(0.1)
    assert condition()


@pytest.mark.parametrize("client_type", [AbstractCloseClient, InheritedCloseClient])
def test_close_reaches_the_server_and_is_idempotent(websocket_client, client_type):
    watcher = connect(websocket_client, client_type, "watcher")
    await_condition(lambda: watcher.messages == ["[watcher] joined"])
    leaver = connect(websocket_client, client_type, "leaver")
    await_condition(lambda: "[leaver] joined" in watcher.messages)

    leaver.send("bye")
    await_condition(lambda: "[leaver] bye" in watcher.messages)

    leaver.close()
    leaver.close()
    await_condition(lambda: "[leaver] left" in watcher.messages)

    watcher.close()
    watcher.close()
