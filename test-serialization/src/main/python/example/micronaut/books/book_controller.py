from typing import Annotated

from micronaut.http.annotation import Body, Controller, Post

from example.micronaut.books.book_request import BookRequest
from example.micronaut.books.book_service import BookService, describe


@Controller("/books")
class BookController:
    def __init__(self, service: BookService):
        self.service = service

    @Post("/")
    def save(self, book: Annotated[BookRequest, Body]) -> dict[str, str]:
        return {
            "name": book.name,
            "count": str(book.count),
            "status": book.status.name,
            "label": book.label,
        }

    @Post("/asdict")
    def as_dict(self, book: Annotated[BookRequest, Body]) -> dict[str, str]:
        result = {"route_" + key: value for key, value in describe(book).items()}
        result.update({"service_" + key: value for key, value in self.service.describe(book).items()})
        return result
