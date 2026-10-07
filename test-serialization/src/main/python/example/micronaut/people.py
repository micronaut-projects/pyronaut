from dataclasses import dataclass
from typing import Annotated

from pyronaut import http, inject, serde, validation
from pyronaut.serde import serdeable as json_model, serializable, deserializable
from micronaut.serde.config.naming import SnakeCaseStrategy

# constraints written once, in a type alias type checkers understand
type PersonName = Annotated[str, validation.NotBlank(), validation.Size(min=1, max=30)]


@serde.serdeable
@dataclass
class Person:
    name: PersonName
    age: Annotated[int, serde.JsonProperty("person_age")]
    telephone: Annotated[str | None, validation.Pattern(regexp=r"\d{10}")] = None


@inject.singleton
class PeopleService:
    def find(self, name: str) -> Person | None:
        return Person(name=name, age=42) if name != "nobody" else None


@json_model()
@dataclass
class Household:
    people: list[Person]


@json_model(validate=False, naming=SnakeCaseStrategy)
@dataclass
class NamedPayload:
    firstName: str


@serializable()
@dataclass
class OutputPayload:
    name: str


@deserializable
@dataclass
class InputPayload:
    name: str


@inject.Introspected
@dataclass
class InternalPayload:
    name: str


@http.Controller("/people")
class PeopleController:
    def __init__(self, people: PeopleService):
        self.people = people

    @http.Get(uri="/{name}", produces=http.APPLICATION_JSON)
    def find(self, name: str) -> http.HttpResponse[Person]:
        person = self.people.find(name)
        if person is None:
            return http.HttpResponse.status(http.status.NOT_FOUND)
        return http.ok(person)

    @http.Get(uri="/{name}/trace", produces=http.TEXT_PLAIN)
    def trace(self, name: str, trace: str | None = http.Header("X-Trace")) -> str:
        # the header is bound from the request: the default only marks the parameter
        return f"{name}:{trace}"

    @http.Get(uri="/running", produces=http.TEXT_PLAIN)
    def running(self, ctx: inject.ApplicationContext = inject.Inject()) -> str:
        # a bean, not a request value
        return str(ctx.isRunning())

    @http.Post("/")
    @http.Status(http.status.CREATED)
    def create(self, person: Annotated[Person, http.Body]) -> Person:
        return person
