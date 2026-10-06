from dataclasses import dataclass
from typing import Annotated

from pyronaut import http, serde, validation


@serde.Serdeable
@dataclass
class Item:
    name: Annotated[str, validation.NotBlank]
    price: Annotated[float, validation.Positive]
    is_offer: bool | None = None


@http.Get("/")
def read_root() -> dict:
    return {"Hello": "World"}


@http.Get("/items/{item_id}")
def read_item(item_id: int, q: str | None = None) -> dict:
    return {"item_id": item_id, "q": q}


@http.Put("/items/{item_id}")
def update_item(
    item_id: int,
    item: Annotated[Item, http.Body, validation.Valid],
) -> dict:
    return {"item_name": item.name, "item_id": item_id}
