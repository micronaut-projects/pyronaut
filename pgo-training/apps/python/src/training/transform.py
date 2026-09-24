"""Pure-Python work so that training exercises the GraalPy interpreter, not just Java code."""
import hashlib
import json
import re
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta

WORD = re.compile(r"[a-z]+")


@dataclass
class Visit:
    pet: str
    when: datetime
    cost: float


def summarize(text: str) -> dict:
    document = json.loads(text)
    words = Counter(WORD.findall(document.get("notes", "").lower()))
    visits = [
        Visit(item["pet"], datetime.fromisoformat(item["when"]), float(item["cost"]))
        for item in document.get("visits", [])
    ]
    visits.sort(key=lambda visit: (visit.when, visit.pet))
    first = visits[0].when if visits else datetime(1970, 1, 1)
    span = (visits[-1].when - first) if visits else timedelta()
    by_pet = {}
    for visit in visits:
        by_pet[visit.pet] = round(by_pet.get(visit.pet, 0.0) + visit.cost, 2)
    return {
        "topWords": [word for word, _ in words.most_common(5)],
        "visits": len(visits),
        "spanDays": span.days,
        "costByPet": by_pet,
        "latest": json.dumps(asdict(visits[-1]), default=str) if visits else None,
        "digest": hashlib.sha256(text.encode("utf-8")).hexdigest()[:16],
    }
