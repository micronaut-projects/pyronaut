from __future__ import annotations

import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, Sequence

SUCCESS = 0
USAGE_ERROR = 2
PRECONDITION_FAILED = 8
PLATFORM_UNSUPPORTED = 9
INTERNAL_ERROR = 10

SUPPORTED_COMMANDS = {"install", "process", "run", "test"}
COMMAND_TO_EXECUTABLE = {
    "install": "pyronaut-install",
    "process": "pyronaut-processor",
    "run": "pyronaut-run",
    "test": "pyronaut-test",
}


def main() -> None:
    code = run(sys.argv[1:])
    raise SystemExit(code)


def run(
    argv: Sequence[str],
    runner: Callable[[list[str]], int] | None = None,
    resolver: Callable[[str], str | None] | None = None,
    platform_name: str | None = None,
) -> int:
    execute = runner or _run_subprocess
    locate = resolver or _resolve_executable
    current_platform = platform_name or sys.platform

    if not argv:
        _print_usage()
        return USAGE_ERROR

    if argv[0] in {"-h", "--help"}:
        _print_usage()
        return SUCCESS

    if argv[0] in {"-V", "--version"}:
        print("pyronaut 2")
        return SUCCESS

    command = argv[0]
    forwarded_args = _normalize_project_flag(list(argv[1:]))

    if command not in SUPPORTED_COMMANDS:
        print(f"Unknown command: {command}", file=sys.stderr)
        _print_usage(stream=sys.stderr)
        return USAGE_ERROR

    if not _is_supported_platform(current_platform):
        print("Pyronaut CLI v2 phase 1 supports macOS and Linux only.", file=sys.stderr)
        return PLATFORM_UNSUPPORTED

    project_dir = _extract_project_dir(forwarded_args)

    if command in {"run", "test"}:
        project_path = Path(project_dir).resolve()
        if _install_required(project_path):
            install_code = _delegate("install", ["--project-dir", project_dir], execute, locate)
            if install_code != SUCCESS:
                return install_code
        if _process_required(project_path):
            process_code = _delegate("process", ["--project-dir", project_dir], execute, locate)
            if process_code != SUCCESS:
                return process_code

    return _delegate(command, forwarded_args, execute, locate)


def _delegate(
    command: str,
    args: Sequence[str],
    runner: Callable[[list[str]], int],
    resolver: Callable[[str], str | None],
) -> int:
    executable_name = COMMAND_TO_EXECUTABLE[command]
    executable_path = resolver(executable_name)
    if executable_path is None:
        print(f"Missing delegated executable: {executable_name}", file=sys.stderr)
        return PRECONDITION_FAILED
    command_line = [executable_path, *args]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    return runner(command_line)


def _normalize_project_flag(args: list[str]) -> list[str]:
    normalized: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--project":
            normalized.append("--project-dir")
            index += 1
            continue
        if token.startswith("--project="):
            normalized.append("--project-dir=" + token.split("=", 1)[1])
            index += 1
            continue
        normalized.append(token)
        index += 1
    return normalized


def _extract_project_dir(args: Sequence[str]) -> str:
    for index, token in enumerate(args):
        if token == "--project-dir" and index + 1 < len(args):
            return args[index + 1]
        if token.startswith("--project-dir="):
            return token.split("=", 1)[1]
    return "."


def _install_required(project_dir: Path) -> bool:
    cache_dir = project_dir / "__pyronaut__"
    required_manifests = (
        cache_dir / "resolved-build-dependencies",
        cache_dir / "resolved-runtime-dependencies",
        cache_dir / "resolved-test-dependencies",
    )
    return not all(path.exists() for path in required_manifests)


def _process_required(project_dir: Path) -> bool:
    return not (project_dir / "__pyronaut__" / "classes").is_dir()


def _run_subprocess(command_line: list[str]) -> int:
    try:
        completed = subprocess.run(command_line, check=False)
        return int(completed.returncode)
    except KeyboardInterrupt:
        return 130
    except OSError as exception:
        print(f"Failed executing delegated command: {exception}", file=sys.stderr)
        return INTERNAL_ERROR


def _resolve_executable(command_name: str) -> str | None:
    env_key = command_name.upper().replace("-", "_") + "_EXECUTABLE"
    override = _read_env(env_key)
    if override:
        return override

    bundled = _bundled_executable(command_name)
    if bundled is not None and bundled.exists():
        return str(bundled)

    discovered = shutil.which(command_name)
    if discovered:
        return discovered
    return None


def _bundled_executable(command_name: str) -> Path | None:
    if sys.platform.startswith("linux") or sys.platform == "darwin":
        package_root = Path(__file__).resolve().parent
        return package_root / "tools" / command_name / "bin" / command_name
    return None


def _read_env(name: str) -> str | None:
    value = __import__("os").environ.get(name)
    if value and value.strip():
        return value.strip()
    return None


def _delegation_trace_enabled() -> bool:
    value = _read_env("PYRONAUT_TRACE_DELEGATION")
    if value is None:
        return False
    return value.lower() in {"1", "true", "yes", "on"}


def _is_supported_platform(platform_name: str) -> bool:
    return platform_name.startswith("linux") or platform_name == "darwin"


def _print_usage(stream=sys.stdout) -> None:
    stream.write("Usage: pyronaut [--version] <install|process|run|test> [args...]\n")
