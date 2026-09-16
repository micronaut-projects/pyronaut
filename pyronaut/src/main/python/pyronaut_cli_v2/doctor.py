"""Report model and rendering for ``pyronaut doctor``.

The checks themselves live next to the discovery code they reuse in
:mod:`pyronaut_cli_v2.cli`; this module only knows how to run a list of
checks safely, print the pass/warn/fail rows through the shared progress
console, and serialise the same report as JSON for tooling.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Callable, Sequence

from .progress import Console

PASS = "pass"
WARN = "warn"
FAIL = "fail"
_STATUSES = (PASS, WARN, FAIL)


@dataclass
class CheckResult:
    """Outcome of one doctor check."""

    id: str
    title: str
    status: str
    detail: str
    fix: str | None = None
    data: dict[str, object] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.status not in _STATUSES:
            raise ValueError(f"Unknown doctor check status: {self.status}")

    def to_json(self) -> dict[str, object]:
        return {
            "id": self.id,
            "title": self.title,
            "status": self.status,
            "detail": self.detail,
            "fix": self.fix,
            "data": self.data,
        }


Check = Callable[[], CheckResult]


def run_checks(
    checks: Sequence[tuple[str, str, Check]],
    *,
    on_start: Callable[[str], None] | None = None,
    on_result: Callable[[CheckResult], None] | None = None,
) -> list[CheckResult]:
    """Run ``(id, title, callable)`` checks in order.

    A check that raises never aborts the report: the exception becomes a
    failed row so the remaining checks still run. ``on_start`` receives each
    title before its check runs and ``on_result`` each result as it lands, so
    rows can be printed while later checks are still probing.
    """
    results: list[CheckResult] = []
    for check_id, title, check in checks:
        if on_start is not None:
            on_start(title)
        try:
            result = check()
        except Exception as exc:  # noqa: BLE001 - a broken check must not hide the others
            result = CheckResult(
                check_id,
                title,
                FAIL,
                f"check crashed: {exc.__class__.__name__}: {exc}",
                "Report this as a Pyronaut bug with the output of pyronaut --version",
            )
        results.append(result)
        if on_result is not None:
            on_result(result)
    return results


def summarize(results: Sequence[CheckResult]) -> dict[str, int]:
    return {status: sum(1 for result in results if result.status == status) for status in _STATUSES}


def overall_status(results: Sequence[CheckResult]) -> str:
    if any(result.status == FAIL for result in results):
        return FAIL
    if any(result.status == WARN for result in results):
        return WARN
    return PASS


def render_row(console: Console, result: CheckResult) -> None:
    """Print one permanent row for ``result`` (plus its fix) through ``console``."""
    message = f"{result.title}: {result.detail}"
    if result.status == PASS:
        console.success(message)
    elif result.status == WARN:
        console.warn(message)
    else:
        console.fail(message)
    if result.fix:
        console.hint(f"fix: {result.fix}")


def render(console: Console, results: Sequence[CheckResult]) -> None:
    for result in results:
        render_row(console, result)


def summary_line(results: Sequence[CheckResult]) -> str:
    counts = summarize(results)
    parts = [f"{counts[PASS]} passed"]
    if counts[WARN]:
        parts.append(f"{counts[WARN]} warning{'s' if counts[WARN] != 1 else ''}")
    if counts[FAIL]:
        parts.append(f"{counts[FAIL]} failed")
    return "Pyronaut doctor: " + ", ".join(parts)


def to_json(
    results: Sequence[CheckResult],
    *,
    version: str,
    platform: str,
    project_dir: str | None,
) -> str:
    report = {
        "pyronaut": version,
        "platform": platform,
        "project": project_dir,
        "status": overall_status(results),
        "summary": summarize(results),
        "checks": [result.to_json() for result in results],
    }
    return json.dumps(report, indent=2, sort_keys=False) + "\n"
