from dataclasses import dataclass
from typing import Annotated

from jakarta.validation import Valid
from jakarta.validation.constraints import NotBlank, Positive
from micronaut.http.annotation import Body, Controller, Get, Put
from micronaut.serde.annotation import Serdeable


@Serdeable
@dataclass
class Item:
    name: Annotated[str, NotBlank]
    price: Annotated[float, Positive]
    is_offer: bool | None = None


@Controller("/items")
class ItemController:
    @Get("/{item_id}")
    def read_item(self, item_id: int, q: str | None = None) -> dict:
        return {"item_id": item_id, "q": q}

    @Put("/{item_id}")
    def update_item(self, item_id: int, item: Annotated[Item, Body, Valid]) -> dict:
        return {"item_name": item.name, "item_id": item_id}
