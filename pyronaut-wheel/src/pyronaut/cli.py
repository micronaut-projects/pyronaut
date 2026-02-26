from __future__ import annotations

import importlib.resources
import json
import os
import platform as _platform
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from .bootstrap import (
    acquire_cache_lock,
    read_install_marker,
    resolve_cache_root,
    write_install_marker,
)
from .graalvm import ensure_graalvm_ce_jdk25_latest


class PyronautCliBootstrapError(RuntimeError):
    pass


def _is_quiet() -> bool:
    return os.environ.get("PYRONAUT_QUIET") == "1"


def _is_verbose() -> bool:
    return os.environ.get("PYRONAUT_VERBOSE") == "1"


def _progress(msg: str, *, cached_hit: bool) -> None:
    if _is_quiet():
        return
    if cached_hit and not _is_verbose():
        return
    print(msg, file=sys.stderr, flush=True)


def detect_cli_platform(
    *, system: str | None = None, machine: str | None = None
) -> str:
    sys_name = (system or _platform.system()).lower()
    mach = (machine or _platform.machine()).lower()

    if sys_name.startswith("linux"):
        if mach in ("x86_64", "amd64"):
            return "linux-x64"
        if mach in ("aarch64", "arm64"):
            return "linux-aarch64"
    if sys_name.startswith("darwin") or sys_name.startswith("mac"):
        if mach in ("x86_64", "amd64"):
            return "macos-x64"
        if mach in ("aarch64", "arm64"):
            return "macos-aarch64"
    if sys_name.startswith("windows"):
        if mach in ("x86_64", "amd64"):
            return "windows-x64"

    raise PyronautCliBootstrapError(
        f"Unsupported platform for Pyronaut CLI bootstrap: system={sys_name!r}, machine={mach!r}"
    )


def bundled_cli_zip_path() -> Path:
    try:
        traversable = (
            importlib.resources.files("pyronaut") / "_assets" / "pyronaut-cli.zip"
        )
    except Exception as e:
        raise PyronautCliBootstrapError(f"Failed to locate bundled CLI zip: {e}") from e

    if not traversable.is_file():
        raise PyronautCliBootstrapError(
            "Bundled CLI zip not found: pyronaut/_assets/pyronaut-cli.zip"
        )
    return Path(str(traversable))


def _bundled_exploded_cli_home() -> Path | None:
    try:
        runtime_root = importlib.resources.files("pyronaut") / "_runtime" / "cli"
    except Exception:
        return None

    try:
        runtime_root_path = Path(str(runtime_root))
    except Exception:
        return None

    if not runtime_root_path.exists() or not runtime_root_path.is_dir():
        return None

    extracted_home = runtime_root_path
    children = [p for p in runtime_root_path.iterdir() if p.is_dir()]
    if len(children) == 1:
        extracted_home = children[0]

    bin_name = "pyronaut.bat" if os.name == "nt" else "pyronaut"
    if not (extracted_home / "bin" / bin_name).exists():
        return None

    return extracted_home


def _read_wheel_version() -> str:
    try:
        from . import __version__

        return str(__version__)
    except Exception:
        return "0.0.0"


def ensure_cli_home(
    *,
    cache_root: Path,
    lock_timeout_s: float = 30.0,
    zip_path: Path | None = None,
    cli_platform: str | None = None,
) -> Path:
    plat = detect_cli_platform() if cli_platform is None else cli_platform
    wheel_version = _read_wheel_version()
    cli_key = f"{wheel_version}-{plat}"

    install_root = cache_root / "cli" / cli_key
    home_dir = install_root / "home"
    marker_path = install_root / ".installed.json"
    lock_path = cache_root / "cli" / ".lock"

    with acquire_cache_lock(lock_path, timeout_s=lock_timeout_s):
        marker = read_install_marker(marker_path)
        bin_name = "pyronaut.bat" if os.name == "nt" else "pyronaut"
        expected_bin = home_dir / "bin" / bin_name
        if marker is not None and expected_bin.exists():
            _progress("pyronaut: using cached CLI", cached_hit=True)
            return home_dir

        if install_root.exists():
            shutil.rmtree(install_root)
        _ = install_root.mkdir(parents=True, exist_ok=True)

        exploded_home = _bundled_exploded_cli_home()
        if exploded_home is not None:
            _ = shutil.copytree(exploded_home, home_dir, dirs_exist_ok=False)
            effective_zip_path = None
        else:
            effective_zip_path = (
                bundled_cli_zip_path() if zip_path is None else zip_path
            )
            with tempfile.TemporaryDirectory() as td:
                tmp_dir = Path(td)
                extract_root = tmp_dir / "extract"
                extract_root.mkdir(parents=True, exist_ok=True)

                _progress("pyronaut: extracting CLI", cached_hit=False)
                with zipfile.ZipFile(effective_zip_path) as zf:
                    zf.extractall(extract_root)

                extracted_home = extract_root
                children = [p for p in extract_root.iterdir() if p.is_dir()]
                if len(children) == 1 and not (extract_root / "bin").exists():
                    extracted_home = children[0]

                _ = shutil.move(str(extracted_home), str(home_dir))

        if os.name != "nt":
            posix_launcher = home_dir / "bin" / "pyronaut"
            if posix_launcher.exists():
                current_mode = posix_launcher.stat().st_mode
                posix_launcher.chmod(current_mode | 0o111)

        _ = write_install_marker(marker_path, kind="cli")
        try:
            marker_extra = {
                "cliKey": cli_key,
                "zip": str(effective_zip_path)
                if effective_zip_path is not None
                else None,
            }
            _ = (install_root / ".installed.extra.json").write_text(
                json.dumps(marker_extra, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        except Exception:
            pass

        _progress("pyronaut: CLI ready", cached_hit=False)
        return home_dir


def bootstrap_and_exec(argv: list[str]) -> int:
    cache_root = resolve_cache_root()
    java_home = ensure_graalvm_ce_jdk25_latest(cache_root=cache_root)
    cli_home = ensure_cli_home(cache_root=cache_root)

    bin_name = "pyronaut.bat" if os.name == "nt" else "pyronaut"
    launcher = cli_home / "bin" / bin_name

    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_home)
    java_bin = java_home / "bin"
    existing_path = env.get("PATH", "")
    env["PATH"] = str(java_bin) + (os.pathsep + existing_path if existing_path else "")

    args = [str(launcher), *argv]
    if os.name == "nt":
        result = int(subprocess.call(args, env=env))
        return result

    launcher_path = str(launcher.resolve())
    execve = getattr(os, "execve", None)
    if callable(execve):
        _ = execve(launcher_path, args, env)
        raise AssertionError("unreachable")

    return int(subprocess.call(args, env=env))


def main(argv: list[str] | None = None) -> int:
    effective_argv = list(sys.argv[1:] if argv is None else argv)
    return bootstrap_and_exec(effective_argv)
