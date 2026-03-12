from __future__ import annotations

import shlex
import shutil
import subprocess
import sys
import socket
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

_JDWP_FLAGS = "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"


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
    forwarded_args = _normalize_no_cache_flag(forwarded_args)
    debug_vm = _extract_debug_vm(forwarded_args)
    forwarded_args = _remove_debug_vm(forwarded_args)

    if command not in SUPPORTED_COMMANDS:
        print(f"Unknown command: {command}", file=sys.stderr)
        _print_usage(stream=sys.stderr)
        return USAGE_ERROR

    if not _is_supported_platform(current_platform):
        print("Pyronaut CLI v2 phase 1 supports macOS and Linux only.", file=sys.stderr)
        return PLATFORM_UNSUPPORTED

    project_dir = _extract_project_dir(forwarded_args)

    no_cache = _extract_no_cache(forwarded_args)

    if debug_vm:
        port_ok, error = _check_port_available(5005)
        if not port_ok:
            print(
                "Cannot enable --debug-vm because port 5005 is already in use." + (f" ({error})" if error else ""),
                file=sys.stderr,
            )
            return PRECONDITION_FAILED

    if command in {"run", "test"}:
        install_args = ["--project-dir", project_dir]
        if no_cache:
            install_args.append("--refresh")
        install_code = _delegate("install", install_args, execute, locate)
        if install_code != SUCCESS:
            return install_code

        process_args = ["--project-dir", project_dir]
        if no_cache:
            process_args.append("--no-cache")
        process_code = _delegate("process", process_args, execute, locate)
        if process_code != SUCCESS:
            return process_code

    return _delegate(command, forwarded_args, execute, locate, debug_vm=debug_vm)


def _delegate(
    command: str,
    args: Sequence[str],
    runner: Callable[[list[str]], int],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
) -> int:
    executable_name = COMMAND_TO_EXECUTABLE[command]
    executable_path = resolver(executable_name)
    if executable_path is None:
        print(f"Missing delegated executable: {executable_name}", file=sys.stderr)
        return PRECONDITION_FAILED
    command_line = [executable_path, *args]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    if not debug_vm:
        return runner(command_line)

    java_tool_options = _merge_java_tool_options(_read_env("JAVA_TOOL_OPTIONS"), _JDWP_FLAGS)
    return runner(["__env__", f"JAVA_TOOL_OPTIONS={java_tool_options}", *command_line])


def _merge_java_tool_options(existing: str | None, addition: str) -> str:
    if existing is None or not existing.strip():
        return addition
    existing_value = existing.strip()
    if addition in existing_value:
        return existing_value
    return existing_value + " " + addition


def _extract_debug_vm(args: Sequence[str]) -> bool:
    for token in args:
        if token == "--debug-vm":
            return True
        if token.startswith("--debug-vm="):
            value = token.split("=", 1)[1].strip().lower()
            if value in {"1", "true", "yes", "on"}:
                return True
            if value in {"0", "false", "no", "off"}:
                return False
            raise ValueError("Invalid value for --debug-vm. Use true/false")
    return False


def _remove_debug_vm(args: list[str]) -> list[str]:
    normalized: list[str] = []
    for token in args:
        if token == "--debug-vm" or token.startswith("--debug-vm="):
            continue
        normalized.append(token)
    return normalized


def _check_port_available(port: int) -> tuple[bool, str | None]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
        return True, None
    except OSError as e:
        return False, str(e)
    finally:
        try:
            sock.close()
        except Exception:
            pass


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


def _normalize_no_cache_flag(args: list[str]) -> list[str]:
    normalized: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--no-cache":
            normalized.append("--no-cache")
            index += 1
            continue
        if token.startswith("--no-cache="):
            normalized.append("--no-cache=" + token.split("=", 1)[1])
            index += 1
            continue
        normalized.append(token)
        index += 1
    return normalized


def _extract_no_cache(args: Sequence[str]) -> bool:
    for token in args:
        if token == "--no-cache":
            return True
        if token.startswith("--no-cache="):
            value = token.split("=", 1)[1].strip().lower()
            if value in {"1", "true", "yes", "on"}:
                return True
            if value in {"0", "false", "no", "off"}:
                return False
            raise ValueError("Invalid value for --no-cache. Use true/false")
    return False


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


def _process_required(project_dir: Path, command: str) -> bool:
    classes_ready = (project_dir / "__pyronaut__" / "classes").is_dir()
    if command == "test":
        test_classes_ready = (project_dir / "__pyronaut__" / "test-classes").is_dir()
        return not (classes_ready and test_classes_ready)
    return not classes_ready


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
