from jakarta.inject import Singleton
from micronaut.core.io import ResourceResolver
from micronaut.core.io.scan import ClassPathResourceLoader
# from pydantic import BaseModel, PositiveInt
from micronaut.serde.annotation import Serdeable
from micronaut.jsonschema import JsonSchema
from dataclasses import dataclass
from jakarta.validation.constraints import NotBlank
from typing import Annotated

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

@Serdeable
@dataclass
class GreetingRequest:
   name: Annotated[str, NotBlank]

@Singleton
class ClasspathResourceService:
    """Reads classpath resources through Micronaut Core's io package via host access."""

    def __init__(self, resource_resolver: ResourceResolver):
        self.resource_resolver = resource_resolver

    def read_text(self, path: str) -> str:
        stream = self.resource_resolver.getResourceAsStream(f"classpath:{path}").orElse(None)
        if stream is None:
            raise FileNotFoundError(path)
        try:
            return bytes(stream.readAllBytes()).decode("utf-8")
        finally:
            stream.close()

    def has_classpath_loader(self) -> bool:
        return self.resource_resolver.getLoader(ClassPathResourceLoader).isPresent()

    def has_supporting_loader(self, prefix: str) -> bool:
        return self.resource_resolver.getSupportingLoader(prefix).isPresent()
