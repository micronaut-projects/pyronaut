from typing import Annotated

from micronaut.http.annotation import Body, Controller, Post

from example.micronaut.books.book_request import BookRequest


@Controller("/books")
class BookController:
    @Post("/")
    def save(self, book: Annotated[BookRequest, Body]) -> dict[str, str]:
        return {
            "name": book.name,
            "count": str(book.count),
            "status": book.status.name,
            "label": book.label,
        }
