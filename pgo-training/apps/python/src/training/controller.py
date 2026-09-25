from typing import Annotated, List

from micronaut.http import HttpResponse
from micronaut.http.annotation import Body, Controller, Delete, Get, Post, Put
from micronaut.validation.validator import Validator

from .model import Pet, PetCommand
from .store import PetStore
from .transform import summarize


@Controller("/pets")
class PetController:

    def __init__(self, store: PetStore, validator: Validator):
        self.store = store
        self.validator = validator

    @Get("/")
    def list(self) -> List[Pet]:
        return self.store.all()

    @Get("/species/{species}")
    def by_species(self, species: str) -> List[Pet]:
        return self.store.by_species(species)

    @Get("/search/{fragment}")
    def search(self, fragment: str) -> List[Pet]:
        return self.store.name_contains(fragment)

    @Get("/count/{species}")
    def count(self, species: str) -> dict:
        return {"species": species, "count": self.store.count_species(species)}

    @Get("/{id}")
    def show(self, id: int) -> HttpResponse:
        found = self.store.find(id)
        if found is None:
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(found)

    @Post("/")
    def create(self, command: Annotated[PetCommand, Body]) -> HttpResponse:
        errors = self._errors(command)
        if errors:
            return HttpResponse.badRequest({"errors": errors})
        pet = self.store.save(Pet(None, command.name, command.species, command.age, command.vaccinated))
        return HttpResponse.created(pet)

    @Put("/{id}")
    def update(self, id: int, command: Annotated[PetCommand, Body]) -> HttpResponse:
        errors = self._errors(command)
        if errors:
            return HttpResponse.badRequest({"errors": errors})
        if not self.store.exists(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(self.store.save(Pet(id, command.name, command.species, command.age, command.vaccinated)))

    @Delete("/{id}")
    def delete(self, id: int) -> HttpResponse:
        if not self.store.exists(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        self.store.delete(id)
        return HttpResponse.noContent()

    @Post(value="/summary", consumes="text/plain")
    def summary(self, text: Annotated[str, Body]) -> dict:
        return summarize(text)

    def _errors(self, command: PetCommand) -> list:
        return sorted(violation.getMessage() for violation in self.validator.validate(command))
