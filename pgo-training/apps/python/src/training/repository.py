from typing import List

from micronaut.data.jdbc.annotation import JdbcRepository
from micronaut.data.repository import CrudRepository

from .model import Pet


@JdbcRepository(dialect="H2")
class PetRepository(CrudRepository[Pet, int]):

    def findBySpecies(self, species: str) -> List[Pet]: ...

    def findByNameContains(self, fragment: str) -> List[Pet]: ...

    def countBySpecies(self, species: str) -> int: ...
