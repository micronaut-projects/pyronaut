from pyronaut import http


@http.Get("/")
def read_root() -> dict:
    return {"Hello": "World"}


@http.Get("/items/{item_id}")
def read_item(item_id: int, q: str | None = None) -> dict:
    return {"item_id": item_id, "q": q}
