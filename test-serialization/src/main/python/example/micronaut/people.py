from dataclasses import dataclass
from typing import Annotated

from pyronaut import context, http, serde, validation


@serde.Serdeable
@dataclass
class Person:
    name: Annotated[str, validation.NotBlank, validation.Size(min=1, max=30)]
    age: Annotated[int, serde.JsonProperty("person_age")]
    telephone: Annotated[str | None, validation.Pattern(regexp=r"\d{10}")] = None


@context.Singleton
class PeopleService:
    def find(self, name: str) -> Person | None:
        return Person(name=name, age=42) if name != "nobody" else None


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

    @http.Post("/")
    @http.Status(http.status.CREATED)
    def create(self, person: Annotated[Person, http.Body]) -> Person:
        return person
