from dataclasses import dataclass
from typing import Annotated

from jakarta.validation.constraints import Min, NotBlank
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
