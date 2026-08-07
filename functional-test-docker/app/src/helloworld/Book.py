from dataclasses import dataclass
from micronaut.data.annotation import *
from typing import Annotated
from micronaut.serde.annotation import Serdeable

@dataclass
@MappedEntity
@Serdeable
class Book:
    id : Annotated[int | None, Id, GeneratedValue]
    title : str
