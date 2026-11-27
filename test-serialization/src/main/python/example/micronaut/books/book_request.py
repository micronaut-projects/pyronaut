import enum
from dataclasses import dataclass

from micronaut.serde.annotation import Serdeable


class ReadingStatus(enum.Enum):
    WANT_TO_READ = "WANT_TO_READ"
    READING = "READING"
    FINISHED = "FINISHED"


@Serdeable
@dataclass
class BookRequest:
    name: str
    count: int
    note: str | None = None
    status: ReadingStatus = ReadingStatus.WANT_TO_READ
    label: str = "default-label"
