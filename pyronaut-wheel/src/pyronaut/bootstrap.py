from __future__ import annotations

import contextlib
import dataclasses
import datetime as _dt
import json
import os
import platform as _platform
import time
from pathlib import Path
from typing import IO

from types import TracebackType
from typing import cast


class PyronautBootstrapError(RuntimeError):
    pass


def resolve_cache_root(
    *, env: dict[str, str] | None = None, cwd: Path | None = None
) -> Path:
    effective_env = os.environ if env is None else env
    effective_cwd = Path.cwd() if cwd is None else cwd

    override = effective_env.get("PYRONAUT_CACHE_DIR")
    if override:
        p = Path(override)
        return (effective_cwd / p).resolve() if not p.is_absolute() else p.resolve()

    venv = effective_env.get("VIRTUAL_ENV")
    if not venv:
        raise PyronautBootstrapError(
            "Pyronaut bootstrap requires a virtualenv. Set VIRTUAL_ENV or provide PYRONAUT_CACHE_DIR."
        )
    return (Path(venv) / "lib" / "pyronaut").resolve()


class CacheLockTimeoutError(PyronautBootstrapError):
    pass


@dataclasses.dataclass(frozen=True)
class CacheLock:
    lock_path: Path
    _fh: IO[bytes]

    def release(self) -> None:
        _release_file_lock(self._fh)

    def __enter__(self) -> CacheLock:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.release()


def acquire_cache_lock(
    lock_path: Path, *, timeout_s: float = 30.0, poll_interval_s: float = 0.1
) -> CacheLock:
    if timeout_s <= 0:
        raise ValueError("timeout_s must be > 0")
    if poll_interval_s <= 0:
        raise ValueError("poll_interval_s must be > 0")

    lock_path.parent.mkdir(parents=True, exist_ok=True)

    deadline = time.monotonic() + timeout_s
    fh: IO[bytes] = lock_path.open("a+b")
    try:
        while True:
            if _try_acquire_file_lock(fh):
                return CacheLock(lock_path=lock_path, _fh=fh)
            if time.monotonic() >= deadline:
                raise CacheLockTimeoutError(
                    f"Timed out after {timeout_s:.1f}s waiting for lock: {lock_path}"
                )
            time.sleep(poll_interval_s)
    except BaseException:
        with contextlib.suppress(Exception):
            fh.close()
        raise


def _try_acquire_file_lock(fh: IO[bytes]) -> bool:
    if os.name == "nt":
        import msvcrt

        try:
            msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
            return True
        except OSError:
            return False
    else:
        import fcntl

        try:
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            return False


def _release_file_lock(fh: IO[bytes]) -> None:
    try:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
    finally:
        with contextlib.suppress(Exception):
            fh.close()


@dataclasses.dataclass(frozen=True)
class InstallMarker:
    kind: str
    createdAt: str
    platform: str


def write_install_marker(path: Path, *, kind: str) -> InstallMarker:
    path.parent.mkdir(parents=True, exist_ok=True)
    marker = InstallMarker(
        kind=kind,
        createdAt=_dt.datetime.now(tz=_dt.timezone.utc).isoformat(),
        platform=_platform.platform(),
    )
    tmp = path.with_suffix(path.suffix + ".tmp")
    _ = tmp.write_text(
        json.dumps(dataclasses.asdict(marker), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    _ = tmp.replace(path)
    return marker


def read_install_marker(path: Path) -> InstallMarker | None:
    if not path.exists():
        return None
    raw = path.read_text(encoding="utf-8")
    data: dict[str, str] | object = cast(
        dict[str, str] | object,
        json.loads(
            raw,
            object_hook=_install_marker_object_hook,
            parse_int=_fail_json_number,
            parse_float=_fail_json_number,
            parse_constant=_fail_json_constant,
        ),
    )
    if not isinstance(data, dict):
        raise PyronautBootstrapError(f"Invalid install marker format at {path}")
    if not all(
        k in data and isinstance(data[k], str)
        for k in ("kind", "createdAt", "platform")
    ):
        raise PyronautBootstrapError(f"Invalid install marker format at {path}")
    try:
        kind = cast(str, data["kind"])
        created_at = cast(str, data["createdAt"])
        plat = cast(str, data["platform"])
    except KeyError as e:
        raise PyronautBootstrapError(
            f"Missing key in install marker at {path}: {e}"
        ) from e
    return InstallMarker(kind=kind, createdAt=created_at, platform=plat)


def _install_marker_object_hook(obj: object) -> object:
    if not isinstance(obj, dict):
        return obj
    obj = cast(dict[object, object], obj)
    if not all(k in obj for k in ("kind", "createdAt", "platform")):
        return obj
    if not all(isinstance(obj.get(k), str) for k in ("kind", "createdAt", "platform")):
        return obj
    return cast(dict[str, str], obj)


def _fail_json_number(_: str) -> object:
    raise PyronautBootstrapError("Invalid install marker JSON")


def _fail_json_constant(_: str) -> object:
    raise PyronautBootstrapError("Invalid install marker JSON")
