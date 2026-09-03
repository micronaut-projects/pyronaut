from __future__ import annotations

import argparse
import io
import re
import urllib.parse
import zipfile
from pathlib import Path
from typing import Iterable


class WheelAuditError(RuntimeError):
    pass


def _path_variants(path: str) -> set[bytes]:
    value = path.rstrip("/\\")
    forward = value.replace("\\", "/")
    backward = value.replace("/", "\\")
    encoded = urllib.parse.quote(forward, safe="/:")
    variants = {value, forward, backward, encoded}
    if re.match(r"^[A-Za-z]:/", forward):
        variants.update({f"file:///{forward}", f"file:/{forward}"})
    elif forward.startswith("/"):
        variants.update({f"file://{forward}", f"file://{encoded}"})
    return {variant.encode("utf-8").lower() for variant in variants if variant}


def _validate_native_descriptor(name: str, payload: bytes) -> None:
    try:
        lines = payload.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise WheelAuditError(
            f"Invalid native classpath descriptor in wheel: {name} (not UTF-8)"
        ) from exc
    entries = [line for line in lines if line.strip() and not line.lstrip().startswith("#")]
    if not entries:
        raise WheelAuditError(
            f"Invalid native classpath descriptor in wheel: {name} (contains no entries)"
        )
    for entry in entries:
        fields = entry.split("\t")
        if len(fields) != 7 or fields[0] != "maven":
            raise WheelAuditError(
                f"Invalid native classpath descriptor in wheel: {name} "
                f"(expected a Maven coordinate entry, got {entry!r})"
            )
        if any(not field or "/" in field or "\\" in field for field in fields[1:5]):
            raise WheelAuditError(
                f"Invalid native classpath descriptor in wheel: {name} "
                f"(invalid Maven coordinate in {entry!r})"
            )
        if "/" in fields[5] or "\\" in fields[5]:
            raise WheelAuditError(
                f"Invalid native classpath descriptor in wheel: {name} "
                f"(invalid classifier in {entry!r})"
            )
        filename = fields[6]
        classifier = f"-{fields[5]}" if fields[5] else ""
        expected_filename = f"{fields[2]}-{fields[3]}{classifier}.{fields[4]}"
        if (
            not filename
            or Path(filename).name != filename
            or "/" in filename
            or "\\" in filename
            or filename != expected_filename
        ):
            raise WheelAuditError(
                f"Invalid native classpath descriptor in wheel: {name} "
                f"(filename must be {expected_filename!r}, got {filename!r})"
            )


def _scan_zip(
    archive: zipfile.ZipFile,
    forbidden: set[bytes],
    *,
    prefix: str = "",
    depth: int = 0,
) -> None:
    for entry in archive.infolist():
        name = f"{prefix}{entry.filename}"
        lowered_name = entry.filename.encode("utf-8", errors="surrogateescape").lower()
        if any(value in lowered_name for value in forbidden):
            raise WheelAuditError(f"Wheel entry name contains a build-machine path: {name}")
        if entry.is_dir():
            continue
        payload = archive.read(entry)
        lowered_payload = payload.lower()
        if any(value in lowered_payload for value in forbidden):
            raise WheelAuditError(f"Wheel entry content contains a build-machine path: {name}")
        if entry.filename.endswith("native-compile-classpath.txt"):
            _validate_native_descriptor(name, payload)
        if depth < 4 and entry.filename.lower().endswith((".jar", ".zip", ".whl")):
            try:
                with zipfile.ZipFile(io.BytesIO(payload)) as nested:
                    _scan_zip(nested, forbidden, prefix=f"{name}!/", depth=depth + 1)
            except zipfile.BadZipFile:
                pass


def audit_wheel(wheel: Path, forbidden_paths: Iterable[str | Path]) -> None:
    forbidden = {
        variant
        for path in forbidden_paths
        for variant in _path_variants(str(path))
    }
    if not forbidden:
        raise WheelAuditError("Wheel audit requires at least one forbidden path")
    try:
        with zipfile.ZipFile(wheel) as archive:
            _scan_zip(archive, forbidden)
    except zipfile.BadZipFile as exc:
        raise WheelAuditError(f"Invalid wheel archive: {wheel}") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit a Pyronaut wheel for build-machine path leaks")
    parser.add_argument("wheel", type=Path)
    parser.add_argument("forbidden_path", nargs="+")
    args = parser.parse_args()
    try:
        audit_wheel(args.wheel, args.forbidden_path)
    except WheelAuditError as exc:
        parser.exit(1, f"{exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
