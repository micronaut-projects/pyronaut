from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false

import os
from pathlib import Path

import pytest


def test_bootstrap_and_exec_posix_execve_sets_env_and_path(
    monkeypatch, tmp_path: Path
) -> None:
    import pyronaut.cli as cli

    cache_root = tmp_path / "cache"
    java_home = tmp_path / "jdk"
    cli_home = tmp_path / "cli"
    launcher = cli_home / "bin" / "pyronaut"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.write_text("#!/bin/sh\n", encoding="utf-8")

    monkeypatch.setattr(cli.os, "name", "posix", raising=False)
    monkeypatch.setattr(cli, "resolve_cache_root", lambda: cache_root)
    monkeypatch.setattr(
        cli, "ensure_graalvm_ce_jdk25_latest", lambda cache_root: java_home
    )
    monkeypatch.setattr(cli, "ensure_cli_home", lambda cache_root: cli_home)

    monkeypatch.setenv("PATH", "p1")
    monkeypatch.setenv("SOME", "x")

    captured: dict[str, object] = {}

    def fake_execve(path: str, args: list[str], env: dict[str, str]) -> None:
        captured["path"] = path
        captured["args"] = args
        captured["env"] = env
        raise SystemExit(0)

    monkeypatch.setattr(cli.os, "execve", fake_execve)

    with pytest.raises(SystemExit):
        _ = cli.bootstrap_and_exec(["a", "b"])

    assert captured["path"] == str(launcher.resolve())
    assert captured["args"] == [str(launcher), "a", "b"]
    env = captured["env"]
    assert isinstance(env, dict)
    assert env["JAVA_HOME"] == str(java_home)
    assert env["SOME"] == "x"
    assert env["PATH"].split(os.pathsep)[0] == str(java_home / "bin")


def test_bootstrap_and_exec_posix_missing_execve_falls_back_to_subprocess(
    monkeypatch, tmp_path: Path
) -> None:
    import pyronaut.cli as cli

    cache_root = tmp_path / "cache"
    java_home = tmp_path / "jdk"
    cli_home = tmp_path / "cli"
    launcher = cli_home / "bin" / "pyronaut"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.write_text("#!/bin/sh\n", encoding="utf-8")

    monkeypatch.setattr(cli.os, "name", "posix", raising=False)
    monkeypatch.setattr(cli, "resolve_cache_root", lambda: cache_root)
    monkeypatch.setattr(
        cli, "ensure_graalvm_ce_jdk25_latest", lambda cache_root: java_home
    )
    monkeypatch.setattr(cli, "ensure_cli_home", lambda cache_root: cli_home)

    monkeypatch.setenv("PATH", "p1")

    monkeypatch.delattr(cli.os, "execve", raising=False)

    captured: dict[str, object] = {}

    def fake_call(args: list[str], env: dict[str, str]) -> int:
        captured["args"] = args
        captured["env"] = env
        return 7

    monkeypatch.setattr(cli.subprocess, "call", fake_call)

    rc = cli.bootstrap_and_exec(["--version"])
    assert rc == 7
    assert captured["args"] == [str(launcher), "--version"]
    env = captured["env"]
    assert isinstance(env, dict)
    assert env["JAVA_HOME"] == str(java_home)


def test_bootstrap_and_exec_windows_calls_subprocess_and_returns_exit_code(
    monkeypatch, tmp_path: Path
) -> None:
    import pyronaut.cli as cli

    cache_root = tmp_path / "cache"
    java_home = tmp_path / "jdk"
    cli_home = tmp_path / "cli"
    launcher = cli_home / "bin" / "pyronaut.bat"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.write_text("@echo off\r\n", encoding="utf-8")

    monkeypatch.setattr(cli.os, "name", "nt", raising=False)
    monkeypatch.setattr(cli, "resolve_cache_root", lambda: cache_root)
    monkeypatch.setattr(
        cli, "ensure_graalvm_ce_jdk25_latest", lambda cache_root: java_home
    )
    monkeypatch.setattr(cli, "ensure_cli_home", lambda cache_root: cli_home)

    monkeypatch.setenv("PATH", "p1")

    captured: dict[str, object] = {}

    def fake_call(args: list[str], env: dict[str, str]) -> int:
        captured["args"] = args
        captured["env"] = env
        return 42

    monkeypatch.setattr(cli.subprocess, "call", fake_call)

    rc = cli.bootstrap_and_exec(["--version"])
    assert rc == 42

    assert captured["args"] == [str(launcher), "--version"]
    env = captured["env"]
    assert isinstance(env, dict)
    assert env["JAVA_HOME"] == str(java_home)
    assert env["PATH"].split(os.pathsep)[0] == str(java_home / "bin")
