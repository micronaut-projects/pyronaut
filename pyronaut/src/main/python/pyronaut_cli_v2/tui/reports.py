from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class TestReportSummary:
    passed: int
    failed: int
    skipped: int
    total: int
    failing_tests: tuple[str, ...]
    last_nodeid: str | None


def summarize_test_reports(reports_dir: Path) -> TestReportSummary:
    junit = reports_dir / "junit.xml"
    last_nodeid_file = reports_dir / ".pyronaut-last-nodeid.txt"

    last_nodeid = None
    if last_nodeid_file.exists():
        try:
            last_nodeid = last_nodeid_file.read_text(encoding="utf-8").strip() or None
        except OSError:
            last_nodeid = None

    if not junit.exists():
        return TestReportSummary(
            passed=0,
            failed=0,
            skipped=0,
            total=0,
            failing_tests=(),
            last_nodeid=last_nodeid,
        )

    try:
        root = ET.fromstring(junit.read_text(encoding="utf-8"))
    except Exception:
        return TestReportSummary(
            passed=0,
            failed=0,
            skipped=0,
            total=0,
            failing_tests=(),
            last_nodeid=last_nodeid,
        )

    total, failures, skipped, passed, failing = _aggregate_junit(root)
    return TestReportSummary(
        passed=passed,
        failed=failures,
        skipped=skipped,
        total=total,
        failing_tests=tuple(failing),
        last_nodeid=last_nodeid,
    )


def render_summary_line(reports_dir: Path) -> str | None:
    summary = summarize_test_reports(reports_dir)
    if summary.total == 0:
        return "[tui] no test reports found"
    base = f"[tui] tests: {summary.passed} passed, {summary.failed} failed, {summary.skipped} skipped"
    if summary.last_nodeid:
        base += f" (last: {summary.last_nodeid})"
    if summary.failing_tests:
        base += "\n[tui] failing:\n" + "\n".join(f"  - {name}" for name in summary.failing_tests[:20])
        if len(summary.failing_tests) > 20:
            base += f"\n  ... ({len(summary.failing_tests) - 20} more)"
    return base


def _aggregate_junit(root: ET.Element) -> tuple[int, int, int, int, list[str]]:
    suites = []
    if root.tag == "testsuite":
        suites = [root]
    elif root.tag == "testsuites":
        suites = list(root.findall("testsuite"))

    total = 0
    failures = 0
    skipped = 0
    failing: list[str] = []

    for suite in suites:
        total += _int_attr(suite, "tests")
        failures += _int_attr(suite, "failures") + _int_attr(suite, "errors")
        skipped += _int_attr(suite, "skipped")
        for case in suite.findall("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            classname = (case.get("classname") or "").strip()
            name = (case.get("name") or "").strip()
            failing.append(_format_test_name(classname, name))

    passed = max(0, total - failures - skipped)
    failing = _stable_unique(failing)
    return total, failures, skipped, passed, failing


def _format_test_name(classname: str, name: str) -> str:
    if classname and name:
        return f"{classname}::{name}"
    return name or classname or "<unknown>"


def _int_attr(elem: ET.Element, attr: str) -> int:
    raw = (elem.get(attr) or "").strip()
    if not raw:
        return 0
    if re.fullmatch(r"\d+", raw):
        return int(raw)
    try:
        return int(float(raw))
    except Exception:
        return 0


def _stable_unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for v in values:
        if v in seen:
            continue
        seen.add(v)
        out.append(v)
    return out
