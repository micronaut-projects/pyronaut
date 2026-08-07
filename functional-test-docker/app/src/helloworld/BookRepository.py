from micronaut.data.jdbc.annotation import JdbcRepository
from typing import List
from jakarta.data.repository import Save
from micronaut.data.repository import CrudRepository
from .Book import Book

@JdbcRepository(dialect = "MYSQL")
class BookRepository(CrudRepository[Book, int]):

    @Save
    def saveBook(self, book : Book) -> None: ...

    def findAll(self) -> List[Book]: ...

    def findById(self, id: int) -> Book: ...

    def findByTitle(self, title: str) -> Book: ...
