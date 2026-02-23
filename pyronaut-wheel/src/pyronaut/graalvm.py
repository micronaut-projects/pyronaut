from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false

import dataclasses
import hashlib
import io
import os
import platform as _platform
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import Callable, Protocol

from .bootstrap import (
    PyronautBootstrapError,
    acquire_cache_lock,
    read_install_marker,
    resolve_cache_root,
    write_install_marker,
)


class GraalVMBootstrapError(PyronautBootstrapError):
    pass


GraalPlatform = str


def detect_graalvm_platform(
    *, system: str | None = None, machine: str | None = None
) -> GraalPlatform:
    sys_name = (system or _platform.system()).lower()
    mach = (machine or _platform.machine()).lower()

    if sys_name.startswith("linux"):
        if mach in ("x86_64", "amd64"):
            return "linux-x64"
        if mach in ("aarch64", "arm64"):
            return "linux-aarch64"
    elif sys_name.startswith("darwin") or sys_name.startswith("mac"):
        if mach in ("x86_64", "amd64"):
            return "macos-x64"
        if mach in ("aarch64", "arm64"):
            return "macos-aarch64"
    elif sys_name.startswith("windows"):
        if mach in ("x86_64", "amd64"):
            return "windows-x64"

    raise GraalVMBootstrapError(
        f"Unsupported platform for GraalVM CE bootstrap: system={sys_name!r}, machine={mach!r}"
    )


def archive_ext_for_platform(platform: GraalPlatform) -> str:
    return "zip" if platform.startswith("windows-") else "tar.gz"


def build_graalvm_ce_download_urls(
    *,
    version: str,
    platform: GraalPlatform,
    base_url: str = "https://github.com/graalvm/graalvm-ce-builds/releases/download",
) -> tuple[str, str]:
    ext = archive_ext_for_platform(platform)
    filename = f"graalvm-community-jdk-{version}_{platform}_bin.{ext}"
    url = f"{base_url}/jdk-{version}/{filename}"
    return url, url + ".sha256"


class LatestVersionResolver(Protocol):
    def __call__(self) -> str: ...


def resolve_latest_graalvm_ce_jdk25_version(
    *,
    urlopen: Callable[..., io.BufferedIOBase] | None = None,
    latest_url: str = "https://github.com/graalvm/graalvm-ce-builds/releases/latest",
) -> str:
    opener = urllib.request.urlopen if urlopen is None else urlopen
    try:
        with opener(latest_url) as resp:
            final_url = getattr(resp, "geturl", lambda: latest_url)()
    except OSError as e:
        raise GraalVMBootstrapError(
            f"Failed to resolve latest GraalVM CE version: {e}"
        ) from e

    m = re.search(r"/tag/jdk-([^/]+)$", str(final_url))
    if not m:
        raise GraalVMBootstrapError(
            f"Unexpected latest release URL for GraalVM CE: {final_url}"
        )
    version = m.group(1)
    if not version.startswith("25"):
        raise GraalVMBootstrapError(
            f"Resolved GraalVM CE latest version does not look like JDK 25: {version}"
        )
    return version


class Downloader(Protocol):
    def __call__(self, url: str, dest_path: Path) -> None: ...


def _is_quiet() -> bool:
    return os.environ.get("PYRONAUT_QUIET") == "1"


def _is_verbose() -> bool:
    return os.environ.get("PYRONAUT_VERBOSE") == "1"


def _should_print_progress(*, cached_hit: bool) -> bool:
    if _is_quiet():
        return False
    if cached_hit and not _is_verbose():
        return False
    return True


def _progress(msg: str) -> None:
    if _is_quiet():
        return
    print(msg, file=sys.stderr, flush=True)


def default_downloader(url: str, dest_path: Path) -> None:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as resp:
        with dest_path.open("wb") as out:
            shutil.copyfileobj(resp, out)


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_sha256sum_text(text: str) -> str:
    m = re.search(r"\b([0-9a-fA-F]{64})\b", text)
    if not m:
        raise GraalVMBootstrapError("Invalid .sha256 file format")
    return m.group(1).lower()


def verify_sha256(*, path: Path, expected_hex: str) -> None:
    expected = expected_hex.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise GraalVMBootstrapError("Expected sha256 must be 64 hex chars")
    actual = sha256_of_file(path)
    if actual != expected:
        raise GraalVMBootstrapError(
            f"SHA256 mismatch for {path.name}: expected {expected}, got {actual}"
        )


def extract_archive(*, archive_path: Path, dest_dir: Path) -> None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    name = archive_path.name.lower()
    if name.endswith(".zip"):
        with zipfile.ZipFile(archive_path) as zf:
            zf.extractall(dest_dir)
        return
    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        with tarfile.open(archive_path, mode="r:gz") as tf:
            tf.extractall(dest_dir)
        return
    raise GraalVMBootstrapError(f"Unsupported archive format: {archive_path}")


@dataclasses.dataclass(frozen=True)
class GraalVMInstallSpec:
    version: str
    platform: GraalPlatform

    @property
    def graal_key(self) -> str:
        return f"graalvm-community-jdk-{self.version}_{self.platform}"


def ensure_graalvm_ce_jdk25_latest(
    *,
    cache_root: Path | None = None,
    platform: GraalPlatform | None = None,
    version_resolver: LatestVersionResolver | None = None,
    downloader: Downloader | None = None,
    lock_timeout_s: float = 30.0,
    offline: bool | None = None,
) -> Path:
    effective_cache_root = resolve_cache_root() if cache_root is None else cache_root
    plat = detect_graalvm_platform() if platform is None else platform
    effective_offline = (
        (os.environ.get("PYRONAUT_OFFLINE") == "1") if offline is None else offline
    )
    resolver = (
        resolve_latest_graalvm_ce_jdk25_version
        if version_resolver is None
        else version_resolver
    )
    dl = default_downloader if downloader is None else downloader

    if effective_offline:
        graal_root = effective_cache_root / "graalvm"
        if graal_root.exists():
            for install_root in sorted(
                (p for p in graal_root.iterdir() if p.is_dir() and p.name != ".lock"),
                key=lambda p: p.name,
            ):
                home_dir = install_root / "home"
                marker_path = install_root / ".installed.json"
                marker = read_install_marker(marker_path)
                if marker is not None and home_dir.exists():
                    return home_dir

        raise GraalVMBootstrapError(
            "PYRONAUT_OFFLINE=1 but no cached GraalVM JDK installation was found. "
            "Either run once without offline mode to populate the cache, or set JAVA_HOME "
            "to an existing JDK installation."
        )

    graal_root = effective_cache_root / "graalvm"
    if graal_root.exists():
        for install_root in sorted(
            (p for p in graal_root.iterdir() if p.is_dir() and p.name != ".lock"),
            key=lambda p: p.name,
            reverse=True,
        ):
            home_dir = install_root / "home"
            marker_path = install_root / ".installed.json"
            marker = read_install_marker(marker_path)
            if marker is not None and home_dir.exists():
                if _should_print_progress(cached_hit=True):
                    _progress("pyronaut: using cached GraalVM")
                return home_dir

    if _should_print_progress(cached_hit=False):
        _progress("pyronaut: resolving latest GraalVM JDK version")
    version = resolver()
    spec = GraalVMInstallSpec(version=version, platform=plat)

    install_root = effective_cache_root / "graalvm" / spec.graal_key
    home_dir = install_root / "home"
    marker_path = install_root / ".installed.json"

    url, sha_url = build_graalvm_ce_download_urls(version=version, platform=plat)
    archive_ext = archive_ext_for_platform(plat)

    with tempfile.TemporaryDirectory() as td:
        tmp_dir = Path(td)
        archive_path = tmp_dir / f"graalvm.bin.{archive_ext}"
        sha_path = tmp_dir / "graalvm.sha256"

        if _should_print_progress(cached_hit=False):
            _progress("pyronaut: downloading GraalVM archive")
        dl(url, archive_path)
        if _should_print_progress(cached_hit=False):
            _progress("pyronaut: downloading GraalVM checksum")
        dl(sha_url, sha_path)

        if _should_print_progress(cached_hit=False):
            _progress("pyronaut: verifying GraalVM checksum")
        expected = parse_sha256sum_text(sha_path.read_text(encoding="utf-8"))
        verify_sha256(path=archive_path, expected_hex=expected)

        if _should_print_progress(cached_hit=False):
            _progress("pyronaut: extracting GraalVM")
        extract_root = tmp_dir / "extract"
        extract_archive(archive_path=archive_path, dest_dir=extract_root)

        children = [p for p in extract_root.iterdir() if p.is_dir()]
        if len(children) != 1:
            raise GraalVMBootstrapError(
                f"Unexpected archive layout: expected 1 top directory, got {len(children)}"
            )
        extracted_home = children[0]

        lock_path = effective_cache_root / "graalvm" / ".lock"
        with acquire_cache_lock(lock_path, timeout_s=lock_timeout_s):
            marker = read_install_marker(marker_path)
            if marker is not None and home_dir.exists():
                if _should_print_progress(cached_hit=True):
                    _progress("pyronaut: using cached GraalVM")
                return home_dir

            if install_root.exists():
                shutil.rmtree(install_root)
            _ = install_root.mkdir(parents=True, exist_ok=True)

            shutil.move(str(extracted_home), str(home_dir))
            _ = write_install_marker(marker_path, kind="graalvm")

        if _should_print_progress(cached_hit=False):
            _progress("pyronaut: GraalVM ready")
        return home_dir
