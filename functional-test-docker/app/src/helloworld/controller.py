from micronaut.http.annotation import Get, Post, Body
from micronaut.http import HttpResponse
from jakarta.inject import Inject
from micronaut.validation.validator import Validator
from typing import Annotated
from .services import GreetingRequest, MessageService, Person

message_service : Annotated[MessageService, Inject]
validator : Annotated[Validator, Inject]

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

@Post(value="/validated-greeting")
def validated_greeting(request : Annotated[GreetingRequest, Body]) -> HttpResponse:
    violations = validator.validate(request)
    if not violations.isEmpty():
        return HttpResponse.badRequest({
            "errors": [violation.getMessage() for violation in violations]
        })
    return HttpResponse.ok({ "message": message_service.say_hello(request.name) })
