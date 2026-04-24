from micronaut.http.annotation import Get, Post, Body
from micronaut.http import HttpResponse
from jakarta.inject import Inject
from typing import Annotated
from .services import MessageService, Person

message_service : Annotated[MessageService, Inject]

@Get(value="/", produces="text/plain")
def index() -> str:
    import logging

    root = logging.getLogger()
    another = logging.getLogger("MyController")
    print("Root handlers:", root.handlers)

    for h in root.handlers:
        print(h, "level:", h.level)

    root.error("doing hello world")
    another.warning("bad things")
    # raise Exception("bad")
    return "Hello, world Pyronaut!!!!"

@Get(value="/hello/{name}")
def hello(name : str) -> dict:
    return { "message": message_service.say_hello(name) }

@Post(value="/hello/{name}")
def create(name : str, person : Annotated[Person, Body]) -> Person:
    return person
