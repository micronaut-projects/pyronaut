from micronaut.data.jdbc.annotation import JdbcRepository
from abc import ABC, abstractmethod
from typing import List, Annotated
from jakarta.data.repository import Save
from .Book import Book

@JdbcRepository(dialect = "MYSQL")
class BookRepository(ABC):

    @Save
    @abstractmethod
    def saveBook(self, book : Book) -> None:
        pass

    @abstractmethod
    def findAll(self) -> List[Book]:
        pass

    @abstractmethod
    def findById(self, id: int) -> Book:
        pass

    @abstractmethod
    def findByTitle(self, title: str) -> Book:
        pass
