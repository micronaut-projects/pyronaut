from micronaut.http.annotation import Controller, Get
from .BookRepository import BookRepository
from .Book import Book

@Controller("/books")
class BookController:
     def __init__(self, repository: BookRepository):
        self.repository = repository
        self.repository.saveBook(Book(None, "it"))

     @Get("/{title}")
     def show(self, title: str) -> Book:
         book = self.repository.findByTitle(title)
         print(f"Book title?: {book.title}")
         return book
