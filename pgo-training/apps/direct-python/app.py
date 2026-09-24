"""Direct-source PGO training app for pyronaut-dev: `pyronaut run app.py`."""
from dataclasses import dataclass
from typing import Annotated, List

from pyronaut.build import AppConfig, Dependency

Dependency(group="io.micronaut.data", module="micronaut-data-jdbc")
Dependency(group="io.micronaut.sql", module="micronaut-jdbc-hikari")
Dependency(group="jakarta.data", module="jakarta.data-api")
Dependency(group="com.h2database", module="h2")
Dependency(group="io.micronaut.data", module="micronaut-data-processor", scope=Dependency.Scope.BUILD)
AppConfig(name="datasources.default.url", value="jdbc:h2:mem:direct;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
AppConfig(name="datasources.default.username", value="sa")
AppConfig(name="datasources.default.password", value="")
AppConfig(name="datasources.default.driver-class-name", value="org.h2.Driver")
AppConfig(name="datasources.default.dialect", value="H2")
AppConfig(name="datasources.default.schema-generate", value="CREATE_DROP")

from jakarta.validation import Valid
from jakarta.validation.constraints import Min, NotBlank
from micronaut.data.annotation import GeneratedValue, Id, MappedEntity
from micronaut.data.jdbc.annotation import JdbcRepository
from micronaut.data.repository import CrudRepository
from micronaut.http import HttpResponse
from micronaut.http.annotation import Body, Controller, Delete, Get, Post, Put
from micronaut.serde.annotation import Serdeable


@dataclass
@MappedEntity
@Serdeable
class Pet:
    id: Annotated[int | None, Id, GeneratedValue]
    name: str
    species: str
    age: int
    vaccinated: bool


@Serdeable
@dataclass
class PetCommand:
    name: Annotated[str, NotBlank]
    species: Annotated[str, NotBlank]
    age: Annotated[int, Min(0)]
    vaccinated: bool = False


@JdbcRepository(dialect="H2")
class PetRepository(CrudRepository[Pet, int]):

    def findBySpecies(self, species: str) -> List[Pet]: ...

    def findByNameContains(self, fragment: str) -> List[Pet]: ...

    def countBySpecies(self, species: str) -> int: ...


@Get(value="/hello", produces="text/plain")
def hello() -> str:
    return "Hello PGO"


@Controller("/pets")
class PetController:

    def __init__(self, repository: PetRepository):
        self.repository = repository

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
    def create(self, command: Annotated[PetCommand, Body, Valid]) -> HttpResponse:
        return HttpResponse.created(self.repository.save(
            Pet(None, command.name, command.species, command.age, command.vaccinated)))

    @Put("/{id}")
    def update(self, id: int, command: Annotated[PetCommand, Body, Valid]) -> HttpResponse:
        if not self.repository.existsById(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(self.repository.update(
            Pet(id, command.name, command.species, command.age, command.vaccinated)))

    @Delete("/{id}")
    def delete(self, id: int) -> HttpResponse:
        if not self.repository.existsById(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        self.repository.deleteById(id)
        return HttpResponse.noContent()
