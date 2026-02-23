from __future__ import annotations

import pathlib
import sys

import pytest


sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from pyronaut.bootstrap import (  # noqa: E402
    CacheLockTimeoutError,
    PyronautBootstrapError,
    acquire_cache_lock,
    resolve_cache_root,
)


def test_resolve_cache_root_uses_env_override_relative(tmp_path: pathlib.Path) -> None:
    cwd = tmp_path / "cwd"
    cwd.mkdir()

    resolved = resolve_cache_root(
        env={"PYRONAUT_CACHE_DIR": "rel/cache"},
        cwd=cwd,
    )

    assert resolved == (cwd / "rel/cache").resolve()


def test_resolve_cache_root_requires_venv_if_no_override() -> None:
    with pytest.raises(PyronautBootstrapError, match=r"VIRTUAL_ENV"):
        resolve_cache_root(env={})


def test_lock_prevents_concurrent_acquisition(tmp_path: pathlib.Path) -> None:
    lock_path = tmp_path / "cache" / ".lock"

    lock1 = acquire_cache_lock(lock_path, timeout_s=1.0)
    try:
        with pytest.raises(CacheLockTimeoutError):
            acquire_cache_lock(lock_path, timeout_s=0.2, poll_interval_s=0.05)
    finally:
        lock1.release()
