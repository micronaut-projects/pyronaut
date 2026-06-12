import pytest
import java

from helloworld.Book import Book
from pyronaut.test import MicronautTest, micronaut_test_fixture


ArrayList = java.type("java.util.ArrayList")

def java_list(*values):
    result = ArrayList()
    for value in values:
        result.add(value)
    return result


def unwrap_optional(value):
    if hasattr(value, "isPresent"):
        assert value.isPresent()
        return value.get()
    assert value is not None
    return value


@pytest.fixture
def my_context(request):
    fixture = micronaut_test_fixture(request, MicronautTest(environments=["foo"]))
    yield fixture
    fixture.stop()


def test_python_jdbc_repository_custom_save_method(my_context):
    repository = my_context["helloworld.BookRepository"]

    repository.saveBook(Book(None, "Compiler ClassLoader"))

    custom_saved = repository.findByTitle("Compiler ClassLoader")
    assert custom_saved is not None
    assert custom_saved.title == "Compiler ClassLoader"

    books = repository.findAll()
    titles = {book.title for book in books}
    assert "Compiler ClassLoader" in titles


def test_python_jdbc_repository_save_count_and_find_all(my_context):
    repository = my_context["helloworld.BookRepository"]

    crud_saved = repository.save(Book(None, "Crud Save"))
    assert crud_saved.id > 0
    assert crud_saved.title == "Crud Save"

    assert repository.count() == 1

    books = repository.findAll()
    titles = {book.title for book in books}
    assert "Crud Save" in titles


def test_python_jdbc_repository_exists_by_id(my_context):
    repository = my_context["helloworld.BookRepository"]

    crud_saved = repository.save(Book(None, "Crud Exists"))
    assert repository.existsById(crud_saved.id)


def test_python_jdbc_repository_find_by_id(my_context):
    repository = my_context["helloworld.BookRepository"]

    crud_saved = repository.save(Book(None, "Crud Find By Id"))
    found_by_id = unwrap_optional(repository.findById(crud_saved.id))
    assert found_by_id.title == "Crud Find By Id"


def test_python_jdbc_repository_update(my_context):
    repository = my_context["helloworld.BookRepository"]

    crud_saved = repository.save(Book(None, "Crud Save"))
    updated = repository.update(Book(crud_saved.id, "Crud Update"))
    assert updated.id == crud_saved.id
    assert repository.findByTitle("Crud Update").id == crud_saved.id


def test_python_jdbc_repository_delete_by_id(my_context):
    repository = my_context["helloworld.BookRepository"]

    updated = repository.save(Book(None, "Crud Delete By Id"))
    repository.deleteById(updated.id)
    assert repository.count() == 0


def test_python_jdbc_repository_delete_entity(my_context):
    repository = my_context["helloworld.BookRepository"]

    crud_saved = repository.save(Book(None, "Crud Delete"))
    repository.delete(crud_saved)
    assert repository.count() == 0


def test_python_jdbc_repository_delete_all(my_context):
    repository = my_context["helloworld.BookRepository"]

    repository.save(Book(None, "Crud Delete All A"))
    repository.save(Book(None, "Crud Delete All B"))
    assert repository.count() == 2
    repository.deleteAll()
    assert repository.count() == 0


def test_python_jdbc_repository_bulk_crud_methods(my_context):
    repository = my_context["helloworld.BookRepository"]

    batch_saved = repository.saveAll(java_list(
        Book(None, "Crud Save All A"),
        Book(None, "Crud Save All B"),
    ))
    assert len(batch_saved) == 2

    batch_saved[0].title = "Crud Update All A"
    batch_saved[1].title = "Crud Update All B"
    batch_updated = repository.updateAll(batch_saved)
    batch_updated_titles = {book.title for book in batch_updated}
    assert batch_updated_titles == {"Crud Update All A", "Crud Update All B"}

    repository.delete(batch_updated[0])
    assert repository.count() == 1

    repository.deleteAll(java_list(batch_updated[1]))
    assert repository.count() == 0
