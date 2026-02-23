from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false

import os
from pathlib import Path

import pytest

import pathlib

import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from pyronaut.bootstrap import write_install_marker  # noqa: E402


def _write_jdk_cache_marker(*, cache_root: Path, graal_key: str) -> Path:
    install_root = cache_root / "graalvm" / graal_key
    home = install_root / "home"
    (home / "bin").mkdir(parents=True, exist_ok=True)
    _ = write_install_marker(install_root / ".installed.json", kind="graalvm")
    return home


def test_offline_with_preinstalled_jdk_proceeds_and_exec_is_called(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import pyronaut.cli as cli

    cache_root = tmp_path / "cache"
    monkeypatch.setattr(cli, "resolve_cache_root", lambda: cache_root)
    monkeypatch.setenv("PYRONAUT_OFFLINE", "1")

    java_home = _write_jdk_cache_marker(
        cache_root=cache_root, graal_key="graalvm-community-jdk-25.0.0+1_linux-x64"
    )

    cli_home = tmp_path / "cli"
    launcher = cli_home / "bin" / "pyronaut"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.write_text("#!/bin/sh\n", encoding="utf-8")
    monkeypatch.setattr(cli, "ensure_cli_home", lambda cache_root: cli_home)

    captured: dict[str, object] = {}

    def fake_execve(path: str, args: list[str], env: dict[str, str]) -> None:
        captured["path"] = path
        captured["args"] = args
        captured["env"] = env
        raise SystemExit(0)

    monkeypatch.setattr(cli.os, "name", "posix", raising=False)
    monkeypatch.setattr(cli.os, "execve", fake_execve)

    with pytest.raises(SystemExit):
        _ = cli.bootstrap_and_exec(["--version"])

    assert captured["path"] == str(launcher.resolve())
    env = captured["env"]
    assert isinstance(env, dict)
    assert env["JAVA_HOME"] == str(java_home)
    assert env["PATH"].split(os.pathsep)[0] == str(java_home / "bin")
