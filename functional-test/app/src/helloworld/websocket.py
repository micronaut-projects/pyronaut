from micronaut.websocket import WebSocketBroadcaster, WebSocketSession
from micronaut.websocket.annotation import OnClose, OnMessage, OnOpen, ServerWebSocket


@ServerWebSocket("/ws/room/{username}")
class RoomServerWebSocket:

    def __init__(self, broadcaster: WebSocketBroadcaster):
        self.broadcaster = broadcaster

    @OnOpen
    def on_open(self, username: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync(f"[{username}] joined")

    @OnMessage
    def on_message(self, username: str, message: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync(f"[{username}] {message}")

    @OnClose
    def on_close(self, username: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync(f"[{username}] left")
