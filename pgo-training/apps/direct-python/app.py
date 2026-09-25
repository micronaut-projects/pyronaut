"""Direct-source PGO training app for pyronaut-dev: `pyronaut run app.py`."""
import threading
from dataclasses import dataclass
from typing import Annotated, List

from jakarta.inject import Singleton
from jakarta.validation import Valid
from jakarta.validation.constraints import Min, NotBlank
from micronaut.http import HttpResponse
from micronaut.http.annotation import Body, Controller, Delete, Get, Post, Put
from micronaut.serde.annotation import Serdeable


@Serdeable
@dataclass
class Pet:
    id: int | None
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


@Singleton
class PetStore:

    def __init__(self):
        self._lock = threading.Lock()
        self._pets = {}
        self._next_id = 1

    def save(self, pet: Pet) -> Pet:
        with self._lock:
            if pet.id is None:
                pet = Pet(self._next_id, pet.name, pet.species, pet.age, pet.vaccinated)
                self._next_id += 1
            self._pets[pet.id] = pet
            return pet

    def find(self, id: int) -> Pet | None:
        with self._lock:
            return self._pets.get(id)

    def delete(self, id: int) -> bool:
        with self._lock:
            return self._pets.pop(id, None) is not None

    def all(self) -> List[Pet]:
        with self._lock:
            return list(self._pets.values())


@Get(value="/hello", produces="text/plain")
def hello() -> str:
    return "Hello PGO"


@Controller("/pets")
class PetController:

    def __init__(self, store: PetStore):
        self.store = store

    @Get("/")
    def list(self) -> List[Pet]:
        return self.store.all()

    @Get("/species/{species}")
    def by_species(self, species: str) -> List[Pet]:
        return [pet for pet in self.store.all() if pet.species == species]

    @Get("/search/{fragment}")
    def search(self, fragment: str) -> List[Pet]:
        return [pet for pet in self.store.all() if fragment in pet.name]

    @Get("/count/{species}")
    def count(self, species: str) -> dict:
        return {"species": species, "count": len(self.by_species(species))}

    @Get("/{id}")
    def show(self, id: int) -> HttpResponse:
        found = self.store.find(id)
        if found is None:
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(found)

    @Post("/")
    def create(self, command: Annotated[PetCommand, Body, Valid]) -> HttpResponse:
        return HttpResponse.created(self.store.save(
            Pet(None, command.name, command.species, command.age, command.vaccinated)))

    @Put("/{id}")
    def update(self, id: int, command: Annotated[PetCommand, Body, Valid]) -> HttpResponse:
        if self.store.find(id) is None:
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.ok(self.store.save(
            Pet(id, command.name, command.species, command.age, command.vaccinated)))

    @Delete("/{id}")
    def delete(self, id: int) -> HttpResponse:
        if not self.store.delete(id):
            return HttpResponse.notFound({"message": f"No pet {id}"})
        return HttpResponse.noContent()
