import threading
from typing import List

from jakarta.inject import Singleton

from .model import Pet


@Singleton
class PetStore:
    """An in-memory pet store, safe for concurrent requests."""

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

    def exists(self, id: int) -> bool:
        with self._lock:
            return id in self._pets

    def delete(self, id: int) -> None:
        with self._lock:
            self._pets.pop(id, None)

    def all(self) -> List[Pet]:
        with self._lock:
            return list(self._pets.values())

    def by_species(self, species: str) -> List[Pet]:
        return [pet for pet in self.all() if pet.species == species]

    def name_contains(self, fragment: str) -> List[Pet]:
        return [pet for pet in self.all() if fragment in pet.name]

    def count_species(self, species: str) -> int:
        return len(self.by_species(species))
