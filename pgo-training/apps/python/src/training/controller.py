from typing import Annotated, List

from micronaut.http import HttpResponse
from micronaut.http.annotation import Body, Controller, Delete, Get, Post, Put
from micronaut.validation.validator import Validator

from .model import Pet, PetCommand
from .repository import PetRepository
from .transform import summarize


@Controller("/pets")
class PetController:

    def __init__(self, repository: PetRepository, validator: Validator):
        self.repository = repository
        self.validator = validator

    @Get("/")
    def list(self) -> List[Pet]:
        return self.repository.findAll()

    @Get("/species/{species}")
    def by_species(self, species: str) -> List[Pet]:
        return self.repository.findBySpecies(species)

    @Get("/search/{fragment}")
    def search(self, fragment: str) -> List[Pet]:
        return self.repository.findByNameContains(fragment)

    @Get("/count/{species}")
    def count(self, species: str) -> dict:
        return {"species": species, "count": self.repository.countBySpecies(species)}

    @Get("/{id}")
    def show(self, id: int) -> HttpResponse:
        found = self.repository.findById(id)
        if not found.isPresent():
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(found.get())

    @Post("/")
    def create(self, command: Annotated[PetCommand, Body]) -> HttpResponse:
        errors = self._errors(command)
        if errors:
            return HttpResponse.badRequest({"errors": errors})
        pet = self.repository.save(Pet(None, command.name, command.species, command.age, command.vaccinated))
        return HttpResponse.created(pet)

    @Put("/{id}")
    def update(self, id: int, command: Annotated[PetCommand, Body]) -> HttpResponse:
        errors = self._errors(command)
        if errors:
            return HttpResponse.badRequest({"errors": errors})
        if not self.repository.existsById(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(self.repository.update(Pet(id, command.name, command.species, command.age, command.vaccinated)))

    @Delete("/{id}")
    def delete(self, id: int) -> HttpResponse:
        if not self.repository.existsById(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        self.repository.deleteById(id)
        return HttpResponse.noContent()

    @Post(value="/summary", consumes="text/plain")
    def summary(self, text: Annotated[str, Body]) -> dict:
        return summarize(text)

    def _errors(self, command: PetCommand) -> list:
        return sorted(violation.getMessage() for violation in self.validator.validate(command))
