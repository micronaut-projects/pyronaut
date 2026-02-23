from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false

import hashlib
import io
import pathlib
import sys
import tarfile
import zipfile

from contextlib import redirect_stderr
from io import StringIO

import pytest


sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from pyronaut.bootstrap import write_install_marker  # noqa: E402
from pyronaut.graalvm import (  # noqa: E402
    GraalVMBootstrapError,
    build_graalvm_ce_download_urls,
    detect_graalvm_platform,
    ensure_graalvm_ce_jdk25_latest,
    extract_archive,
    parse_sha256sum_text,
    sha256_of_file,
    verify_sha256,
)


@pytest.mark.parametrize(
    ("system", "machine", "expected"),
    [
        ("Linux", "x86_64", "linux-x64"),
        ("Linux", "aarch64", "linux-aarch64"),
        ("Darwin", "x86_64", "macos-x64"),
        ("Darwin", "arm64", "macos-aarch64"),
        ("Windows", "AMD64", "windows-x64"),
    ],
)
def test_platform_mapping(system: str, machine: str, expected: str) -> None:
    assert detect_graalvm_platform(system=system, machine=machine) == expected


def test_checksum_verify_pass_and_fail(tmp_path: pathlib.Path) -> None:
    p = tmp_path / "file.bin"
    p.write_bytes(b"hello")

    ok = sha256_of_file(p)
    verify_sha256(path=p, expected_hex=ok)

    with pytest.raises(GraalVMBootstrapError, match=r"SHA256 mismatch"):
        verify_sha256(path=p, expected_hex=("0" * 64))


def test_parse_sha256sum_text() -> None:
    parsed = parse_sha256sum_text(
        "0123456789abcdef" * 4 + "  graalvm-community-jdk-25_linux-x64_bin.tar.gz\n"
    )
    assert parsed == ("0123456789abcdef" * 4)

    with pytest.raises(GraalVMBootstrapError, match=r"Invalid \.sha256"):
        _ = parse_sha256sum_text("not a sha")


def _make_fake_jdk_tar_gz(archive_path: pathlib.Path, top_dir: str) -> None:
    with tarfile.open(archive_path, mode="w:gz") as tf:
        payload = b"x"
        info = tarfile.TarInfo(name=f"{top_dir}/release")
        info.size = len(payload)
        tf.addfile(info, io.BytesIO(payload))


def _make_fake_jdk_zip(archive_path: pathlib.Path, top_dir: str) -> None:
    with zipfile.ZipFile(archive_path, mode="w") as zf:
        zf.writestr(f"{top_dir}/release", "x")


def test_extract_archive_creates_expected_structure_tar(tmp_path: pathlib.Path) -> None:
    archive = tmp_path / "jdk.tar.gz"
    _make_fake_jdk_tar_gz(archive, "graalvm-community-jdk-25.0.0+1")

    out = tmp_path / "out"
    extract_archive(archive_path=archive, dest_dir=out)
    assert (out / "graalvm-community-jdk-25.0.0+1" / "release").exists()


def test_extract_archive_creates_expected_structure_zip(tmp_path: pathlib.Path) -> None:
    archive = tmp_path / "jdk.zip"
    _make_fake_jdk_zip(archive, "graalvm-community-jdk-25.0.0+1")

    out = tmp_path / "out"
    extract_archive(archive_path=archive, dest_dir=out)
    assert (out / "graalvm-community-jdk-25.0.0+1" / "release").exists()


def test_ensure_graalvm_installs_to_cache_home_and_writes_marker(
    tmp_path: pathlib.Path,
) -> None:
    cache_root = tmp_path / "cache"

    version = "25.0.0+1"
    platform = "linux-x64"
    url, sha_url = build_graalvm_ce_download_urls(version=version, platform=platform)

    archive_payload = tmp_path / "fixture.tar.gz"
    top_dir = f"graalvm-community-jdk-{version}"
    _make_fake_jdk_tar_gz(archive_payload, top_dir)

    digest = hashlib.sha256(archive_payload.read_bytes()).hexdigest()
    sha_payload = tmp_path / "fixture.tar.gz.sha256"
    sha_payload.write_text(f"{digest}  {archive_payload.name}\n", encoding="utf-8")

    def downloader(u: str, dest: pathlib.Path) -> None:
        if u == url:
            dest.write_bytes(archive_payload.read_bytes())
            return
        if u == sha_url:
            dest.write_text(sha_payload.read_text(encoding="utf-8"), encoding="utf-8")
            return
        raise AssertionError(f"Unexpected URL: {u}")

    def resolver() -> str:
        return version

    buf = StringIO()
    with redirect_stderr(buf):
        home = ensure_graalvm_ce_jdk25_latest(
            cache_root=cache_root,
            platform=platform,
            version_resolver=resolver,
            downloader=downloader,
        )
    assert buf.getvalue() != ""

    assert (
        home
        == cache_root
        / "graalvm"
        / f"graalvm-community-jdk-{version}_{platform}"
        / "home"
    )
    assert (home / "release").exists()
    assert (home.parent / ".installed.json").exists()


def test_ensure_graalvm_cache_hit_is_silent(
    tmp_path: pathlib.Path,
) -> None:
    cache_root = tmp_path / "cache"
    install_root = cache_root / "graalvm" / "graalvm-community-jdk-25.0.0+1_linux-x64"
    home_dir = install_root / "home"
    home_dir.mkdir(parents=True, exist_ok=True)
    (home_dir / "release").write_text("x", encoding="utf-8")
    _ = write_install_marker(install_root / ".installed.json", kind="graalvm")

    def resolver() -> str:
        raise AssertionError("version resolver should not be called on cache hit")

    def downloader(_: str, __: pathlib.Path) -> None:
        raise AssertionError("downloader should not be called on cache hit")

    buf = StringIO()
    with redirect_stderr(buf):
        home = ensure_graalvm_ce_jdk25_latest(
            cache_root=cache_root,
            platform="linux-x64",
            version_resolver=resolver,
            downloader=downloader,
        )

    assert home == home_dir
    assert buf.getvalue() == ""


def test_ensure_graalvm_does_not_hold_lock_while_downloading(
    tmp_path: pathlib.Path,
) -> None:
    cache_root = tmp_path / "cache"

    version = "25.0.0+1"
    platform = "linux-x64"
    url, sha_url = build_graalvm_ce_download_urls(version=version, platform=platform)

    archive_payload = tmp_path / "fixture.tar.gz"
    top_dir = f"graalvm-community-jdk-{version}"
    _make_fake_jdk_tar_gz(archive_payload, top_dir)
    digest = hashlib.sha256(archive_payload.read_bytes()).hexdigest()
    sha_payload = tmp_path / "fixture.tar.gz.sha256"
    sha_payload.write_text(f"{digest}  {archive_payload.name}\n", encoding="utf-8")

    def downloader(u: str, dest: pathlib.Path) -> None:
        from pyronaut.bootstrap import acquire_cache_lock

        lock_path = cache_root / "graalvm" / ".lock"
        with acquire_cache_lock(lock_path, timeout_s=0.2, poll_interval_s=0.01):
            pass

        if u == url:
            dest.write_bytes(archive_payload.read_bytes())
            return
        if u == sha_url:
            dest.write_text(sha_payload.read_text(encoding="utf-8"), encoding="utf-8")
            return
        raise AssertionError(f"Unexpected URL: {u}")

    def resolver() -> str:
        return version

    _ = ensure_graalvm_ce_jdk25_latest(
        cache_root=cache_root,
        platform=platform,
        version_resolver=resolver,
        downloader=downloader,
    )


def test_offline_missing_jdk_fails_fast_and_does_not_call_network(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cache_root = tmp_path / "cache"
    cache_root.mkdir(parents=True, exist_ok=True)

    def resolver() -> str:
        raise AssertionError("version resolver should not be called in offline mode")

    def downloader(_: str, __: pathlib.Path) -> None:
        raise AssertionError("downloader should not be called in offline mode")

    monkeypatch.setenv("PYRONAUT_OFFLINE", "1")
    with pytest.raises(GraalVMBootstrapError, match=r"PYRONAUT_OFFLINE=1"):
        _ = ensure_graalvm_ce_jdk25_latest(
            cache_root=cache_root,
            platform="linux-x64",
            version_resolver=resolver,
            downloader=downloader,
        )
