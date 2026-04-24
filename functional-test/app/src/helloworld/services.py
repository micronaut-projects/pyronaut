from jakarta.inject import Singleton
# from pydantic import BaseModel, PositiveInt
from micronaut.serde.annotation import Serdeable
from micronaut.jsonschema import JsonSchema
from dataclasses import dataclass

@Singleton
class MessageService:
    def say_hello(self, name : str) -> str:

        return f"Hello {name}!!!!!!"

@Serdeable
@dataclass
@JsonSchema
class Person:
   age: int
   name: str = 'John Doe'
