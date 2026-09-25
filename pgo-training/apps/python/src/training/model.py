from dataclasses import dataclass
from typing import Annotated

from jakarta.validation.constraints import Min, NotBlank
from micronaut.data.annotation import GeneratedValue, Id, MappedEntity
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
