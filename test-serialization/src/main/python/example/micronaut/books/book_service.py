import dataclasses

from jakarta.inject import Singleton

from example.micronaut.books.book_request import BookRequest


def describe(book: BookRequest) -> dict[str, str]:
    data = dataclasses.asdict(book)
    return {
        "type": type(book).__name__,
        "is_dataclass": str(dataclasses.is_dataclass(book)),
        "note": repr(data["note"]),
        "status": data["status"].name,
    }


@Singleton
class BookService:
    def describe(self, book: BookRequest) -> dict[str, str]:
        return describe(book)
