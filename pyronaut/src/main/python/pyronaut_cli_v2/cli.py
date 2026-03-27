from __future__ import annotations

import atexit
import shlex
import shutil
import subprocess
import sys
import socket
import os
import platform
import tarfile
import tempfile
import threading
import time
import urllib.request
from pathlib import Path
from typing import Callable, Protocol, Sequence

SUCCESS = 0
USAGE_ERROR = 2
PRECONDITION_FAILED = 8
PLATFORM_UNSUPPORTED = 9
INTERNAL_ERROR = 10

SUPPORTED_COMMANDS = {"install", "process", "run", "test", "build", "validate-config", "test-resources-server"}
COMMAND_TO_EXECUTABLE = {
    "install": "pyronaut-install",
    "process": "pyronaut-processor",
    "run": "pyronaut-run",
    "test": "pyronaut-test",
    "validate-config": "pyronaut-validate-config",
    "test-resources-server": "pyronaut-test-resources-server",
}
NATIVE_BUILD_EXECUTABLE = "pyronaut-native-build"

_JDWP_FLAGS = "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"

Runner = Callable[[list[str]], int]
RunnerWithEnv = Callable[[list[str], dict[str, str] | None], int]


class ManagedProcess(Protocol):
    def poll(self) -> int | None:
        ...

    def terminate(self) -> None:
        ...

    def wait(self, timeout: float | None = None) -> int:
        ...

    def kill(self) -> None:
        ...


ProcessRunner = Callable[[list[str], dict[str, str] | None], ManagedProcess]
JavaHomeProvider = Callable[[], str | None]

_provisioned_graalvm_home: str | None = None
_GRAALVM_MIN_JDK_MAJOR = 25
_GRAALVM_SDKMAN_CANDIDATE = "25-graal"


def main() -> None:
    code = run(sys.argv[1:])
    raise SystemExit(code)


def run(
    argv: Sequence[str],
    runner: Runner | None = None,
    runner_with_env: RunnerWithEnv | None = None,
    process_runner: ProcessRunner | None = None,
    resolver: Callable[[str], str | None] | None = None,
    platform_name: str | None = None,
    watch_poll_interval: float = 0.25,
    watch_debounce_seconds: float = 0.5,
    snapshotter: Callable[[Path], tuple[tuple[str, int, int], ...]] | None = None,
    monotonic: Callable[[], float] | None = None,
    sleep: Callable[[float], None] | None = None,
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    if monotonic is None:
        monotonic = time.monotonic
    if sleep is None:
        sleep = time.sleep

    if runner_with_env is not None:
        execute = runner_with_env
    elif runner is not None:
        execute = lambda command_line, env=None: runner(command_line)
    else:
        execute = _run_subprocess
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

    if "--tui" in argv or argv[0] == "--tui":
        return _run_tui(argv=list(argv), runner_with_env=execute, resolver=locate)

    command = argv[0]
    forwarded_args = _normalize_project_flag(list(argv[1:]))
    forwarded_args = _normalize_no_cache_flag(forwarded_args)
    forwarded_args = _normalize_tests_selection_flag(forwarded_args)
    no_validate = _extract_no_validate(forwarded_args)
    forwarded_args = _remove_no_validate(forwarded_args)
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
    delegated_args = _strip_no_cache_flag(forwarded_args) if command in {"run", "test"} else forwarded_args
    auto_restart_mode = command == "run" and (process_runner is not None or (runner is None and runner_with_env is None))

    effective_java_home_provider = java_home_provider or _default_java_home_provider(
        runner=runner,
        runner_with_env=runner_with_env,
        process_runner=process_runner,
    )

    if command == "build":
        return _run_build(
            args=forwarded_args,
            runner=execute,
            resolver=locate,
            no_cache=no_cache,
            no_validate=no_validate,
            java_home_provider=effective_java_home_provider,
        )

    if command == "test-resources-server" and _is_test_resources_start(forwarded_args):
        install_args = ["--project-dir", project_dir]
        if no_cache:
            install_args.append("--refresh")
        install_code = _delegate("install", install_args, execute, locate)
        if install_code != SUCCESS:
            return install_code

    if debug_vm:
        port_ok, error = _check_port_available(5005)
        if not port_ok:
            print(
                "Cannot enable --debug-vm because port 5005 is already in use." + (f" ({error})" if error else ""),
                file=sys.stderr,
            )
            return PRECONDITION_FAILED

    tr_session: _OwnedTestResourcesSession | None = None
    test_resources_env_overrides: dict[str, str] | None = None
    try:
        test_resources_disabled = _test_resources_disabled()
        if command in {"run", "test"} and not test_resources_disabled:
            tr_session = _OwnedTestResourcesSession(
                project_dir=Path(project_dir).resolve(),
                owner_command=shlex.join(["pyronaut", command, *forwarded_args]),
            )
        elif command in {"run", "test"} and test_resources_disabled:
            sys.stderr.write("[test-resources] skipped (disabled via PYRONAUT_TEST_RESOURCES_DISABLED)\n")

        if auto_restart_mode:
            preflight_code = _run_preflight(project_dir, no_cache, execute, locate)
            if preflight_code != SUCCESS:
                return preflight_code
            if tr_session is not None:
                tr_session.ensure_started(runner=execute, resolver=locate)
                test_resources_env_overrides = tr_session.client_env_overrides()
            return _run_with_auto_restart(
                project_dir=Path(project_dir),
                run_args=delegated_args,
                no_cache=no_cache,
                execute=execute,
                process_runner=process_runner or _spawn_subprocess,
                resolver=locate,
                debug_vm=debug_vm,
                env_overrides=test_resources_env_overrides,
                initial_preflight_done=True,
                poll_interval=watch_poll_interval,
                debounce_seconds=watch_debounce_seconds,
                snapshotter=snapshotter or _snapshot_watched_files,
                monotonic=monotonic,
                sleep=sleep,
                java_home_provider=effective_java_home_provider,
            )

        if command in {"run", "test"}:
            if no_validate:
                sys.stderr.write("[validation] skipped (--no-validate)\n")
            else:
                validation_code = _run_lifecycle_validation(
                    project_dir=project_dir,
                    scenario=command,
                    runner=execute,
                    resolver=locate,
                    no_cache=no_cache,
                )
                if validation_code != SUCCESS:
                    return validation_code

            preflight_code = _run_preflight(project_dir, no_cache, execute, locate)
            if preflight_code != SUCCESS:
                return preflight_code

            if tr_session is not None:
                tr_session.ensure_started(runner=execute, resolver=locate)
                test_resources_env_overrides = tr_session.client_env_overrides()

        return _delegate(
            command,
            delegated_args,
            execute,
            locate,
            debug_vm=debug_vm,
            env_overrides=test_resources_env_overrides if command in {"run", "test"} else None,
            java_home_provider=effective_java_home_provider,
        )
    finally:
        if tr_session is not None:
            tr_session.stop_if_owned(runner=execute, resolver=locate)


def _delegate(
    command: str,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
    env_overrides: dict[str, str] | None = None,
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    executable_name = COMMAND_TO_EXECUTABLE[command]
    executable_path = resolver(executable_name)
    if executable_path is None:
        print(f"Missing delegated executable: {executable_name}", file=sys.stderr)
        return PRECONDITION_FAILED
    command_line = [executable_path, *args]
    if debug_vm and command in {"run", "test"}:
        command_line = [*command_line, "--debug-vm"]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    try:
        env = _build_java_home_env(command, java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    env = _merge_env_overrides(env, env_overrides)
    if debug_vm:
        env = _build_debug_vm_env(env)
    return runner(command_line, env)


def _run_preflight(
    project_dir: str,
    no_cache: bool,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
) -> int:
    install_args = ["--project-dir", project_dir]
    if no_cache:
        install_args.append("--refresh")
    install_code = _delegate("install", install_args, runner, resolver)
    if install_code != SUCCESS:
        return install_code

    process_args = ["--project-dir", project_dir]
    if no_cache:
        process_args.append("--no-cache")
    return _delegate("process", process_args, runner, resolver)


def _run_build(
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    no_cache: bool,
    no_validate: bool,
    java_home_provider: JavaHomeProvider | None,
) -> int:
    if _extract_flag(args, "--help") or _extract_flag(args, "-h"):
        _print_build_usage()
        return SUCCESS

    project_dir = Path(_extract_project_dir(args)).resolve()
    verbose = _extract_build_verbose(args)
    try:
        mode = _resolve_build_mode(project_dir, args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR

    if no_validate:
        sys.stderr.write("[validation] skipped (--no-validate)\n")
    else:
        validation_code = _run_lifecycle_validation(
            project_dir=str(project_dir),
            scenario="production",
            runner=runner,
            resolver=resolver,
            no_cache=no_cache,
        )
        if validation_code != SUCCESS:
            return validation_code

    if mode == "native":
        preflight = _run_preflight(str(project_dir), no_cache, runner, resolver)
        if preflight != SUCCESS:
            return preflight

        try:
            env = _build_java_home_env("build", java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED

        try:
            _build_native_classpath(project_dir)
            main_class = _extract_main_class(args)
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return USAGE_ERROR
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        output_dir = project_dir / "__pyronaut__" / "native"
        output_dir.mkdir(parents=True, exist_ok=True)
        output_binary = output_dir / "application"
        delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
        if delegate_executable is None:
            print(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}", file=sys.stderr)
            return PRECONDITION_FAILED
        native_command = [
            delegate_executable,
            "--project-dir",
            str(project_dir),
            "--main-class",
            main_class,
            "--output",
            str(output_binary),
        ]
        if verbose:
            native_command.append("--verbose")
        native_command.extend(_extract_native_build_passthrough_args(args))
        if _delegation_trace_enabled():
            print(shlex.join(native_command), file=sys.stderr)
        exit_code = runner(native_command, env)
        if exit_code == SUCCESS:
            print(f"Native build complete: {output_binary}")
            print(f"Run it with: {output_binary}")
        return exit_code

    dist_dir = project_dir / "dist"
    dist_dir.mkdir(parents=True, exist_ok=True)
    python_exec = _read_env("PYRONAUT_PYTHON_EXECUTABLE") or sys.executable or "python3"
    wheel_command = [
        python_exec,
        "-m",
        "pip",
        "wheel",
        "--no-deps",
        "--wheel-dir",
        str(dist_dir),
        str(project_dir),
    ]
    if _delegation_trace_enabled():
        print(shlex.join(wheel_command), file=sys.stderr)
    exit_code = runner(wheel_command, None)
    if exit_code == SUCCESS:
        print(f"Wheel build complete. Artifacts are in: {dist_dir}")
        print(f"Install with: {python_exec} -m pip install {dist_dir}/*.whl")
        print("Run the project with: pyronaut run --project-dir " + str(project_dir))
    return exit_code


def _run_lifecycle_validation(
    *,
    project_dir: str,
    scenario: str,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    no_cache: bool = False,
) -> int:
    args = ["--project-dir", project_dir, "--scenario", scenario]
    if no_cache:
        args.append("--no-cache")
    return _delegate(
        "validate-config",
        args,
        runner,
        resolver,
    )


def _resolve_build_mode(project_dir: Path, args: Sequence[str]) -> str:
    explicit = _extract_build_mode_flag(args)
    if explicit is not None:
        return explicit

    configured = _read_pyproject_build_mode(project_dir)
    if configured is not None:
        return configured
    return "jvm"


def _extract_build_mode_flag(args: Sequence[str]) -> str | None:
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--native":
            return "native"
        if token == "--jvm":
            return "jvm"
        if token == "--mode":
            if index + 1 >= len(args):
                raise ValueError("Missing value for --mode. Use native|jvm")
            value = args[index + 1].strip().lower()
            if value in {"native", "jvm"}:
                return value
            raise ValueError("Invalid value for --mode. Use native|jvm")
        if token.startswith("--mode="):
            value = token.split("=", 1)[1].strip().lower()
            if value in {"native", "jvm"}:
                return value
            raise ValueError("Invalid value for --mode. Use native|jvm")
        index += 1
    return None


def _read_pyproject_build_mode(project_dir: Path) -> str | None:
    pyproject = project_dir / "pyproject.toml"
    if not pyproject.exists():
        return None
    try:
        import tomllib
    except Exception:
        return None
    try:
        with pyproject.open("rb") as fp:
            data = tomllib.load(fp)
    except Exception:
        return None

    tool = data.get("tool")
    if not isinstance(tool, dict):
        return None
    pyronaut = tool.get("pyronaut")
    if not isinstance(pyronaut, dict):
        return None
    build = pyronaut.get("build")
    if not isinstance(build, dict):
        return None
    mode = build.get("mode")
    if not isinstance(mode, str):
        return None
    normalized = mode.strip().lower()
    if normalized in {"native", "jvm"}:
        return normalized
    raise ValueError("Invalid build mode in pyproject.toml. Use tool.pyronaut.build.mode = 'native' or 'jvm'")


def _read_pyproject_test_resources_shared(project_dir: Path) -> bool:
    pyproject = project_dir / "pyproject.toml"
    if not pyproject.exists():
        return False
    try:
        import tomllib
    except Exception:
        return False
    try:
        with pyproject.open("rb") as fp:
            data = tomllib.load(fp)
    except Exception:
        return False

    tool = data.get("tool")
    if not isinstance(tool, dict):
        return False
    pyronaut = tool.get("pyronaut")
    if not isinstance(pyronaut, dict):
        return False
    test_resources = pyronaut.get("testResources")
    if not isinstance(test_resources, dict):
        return False
    shared_server = test_resources.get("sharedServer")
    return isinstance(shared_server, bool) and shared_server


def _parse_properties_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    parsed: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed[key.strip()] = _unescape_properties_value(value.strip())
    return parsed


def _unescape_properties_value(value: str) -> str:
    return (
        value.replace("\\:", ":")
        .replace("\\=", "=")
        .replace("\\ ", " ")
        .replace("\\\\", "\\")
    )


def _test_resources_client_env_from_settings(path: Path) -> dict[str, str] | None:
    settings = _parse_properties_file(path)
    if not settings:
        return None

    properties: dict[str, str] = {}
    for key, value in settings.items():
        if not key.startswith("server."):
            continue
        properties[f"micronaut.test.resources.{key}"] = value

    if not properties:
        return None

    java_tool_options = " ".join(f"-D{k}={_escape_java_tool_option_value(v)}" for k, v in sorted(properties.items()))
    existing = os.environ.get("JAVA_TOOL_OPTIONS", "").strip()
    merged = f"{existing} {java_tool_options}".strip() if existing else java_tool_options
    return {"JAVA_TOOL_OPTIONS": merged}


def _merge_env_overrides(base_env: dict[str, str] | None, env_overrides: dict[str, str] | None) -> dict[str, str] | None:
    if not env_overrides:
        return base_env
    merged = dict(base_env or {})
    merged.update(env_overrides)
    return merged


def _escape_java_tool_option_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace(" ", "\\ ")


def _extract_main_class(args: Sequence[str]) -> str:
    default_main = "pyronaut_application.PyronautMain"
    for index, token in enumerate(args):
        if token == "--main-class" and index + 1 < len(args):
            value = args[index + 1].strip()
            if not value:
                raise ValueError("Invalid value for --main-class. Value cannot be empty")
            return value
        if token.startswith("--main-class="):
            value = token.split("=", 1)[1].strip()
            if not value:
                raise ValueError("Invalid value for --main-class. Value cannot be empty")
            return value
    return default_main


def _extract_build_verbose(args: Sequence[str]) -> bool:
    return any(token == "--verbose" for token in args)


def _extract_native_build_passthrough_args(args: Sequence[str]) -> list[str]:
    passthrough: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--":
            passthrough.extend(args[index + 1:])
            break
        if token in {"--native", "--jvm", "--verbose"}:
            index += 1
            continue
        if token in {"--mode", "--main-class", "--project-dir"}:
            index += 1
            if index < len(args):
                index += 1
            continue
        if (
            token.startswith("--mode=")
            or token.startswith("--main-class=")
            or token.startswith("--project-dir=")
        ):
            index += 1
            continue
        passthrough.append(token)
        index += 1
    return passthrough


def _build_native_classpath(project_dir: Path) -> str:
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")

    entries = []
    if runtime_manifest.exists():
        for line in runtime_manifest.read_text(encoding="utf-8").splitlines():
            value = line.strip()
            if value:
                entries.append(value)
    entries.append(str(classes_dir))
    return os.pathsep.join(entries)


def _run_with_auto_restart(
    project_dir: Path,
    run_args: Sequence[str],
    no_cache: bool,
    execute: RunnerWithEnv,
    process_runner: ProcessRunner,
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool,
    env_overrides: dict[str, str] | None,
    initial_preflight_done: bool,
    poll_interval: float,
    debounce_seconds: float,
    snapshotter: Callable[[Path], tuple[tuple[str, int, int], ...]],
    monotonic: Callable[[], float],
    sleep: Callable[[float], None],
    java_home_provider: JavaHomeProvider | None,
) -> int:
    if poll_interval <= 0:
        poll_interval = 0.25
    if debounce_seconds < 0:
        debounce_seconds = 0.0

    project_root = project_dir.resolve()
    snapshot = snapshotter(project_root)

    while True:
        if initial_preflight_done:
            initial_preflight_done = False
        else:
            preflight_code = _run_preflight(str(project_root), no_cache, execute, resolver)
            if preflight_code != SUCCESS:
                return preflight_code

        executable_path = resolver(COMMAND_TO_EXECUTABLE["run"])
        if executable_path is None:
            print("Missing delegated executable: pyronaut-run", file=sys.stderr)
            return PRECONDITION_FAILED

        command_line = [executable_path, *run_args]
        try:
            env = _build_java_home_env("run", java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        env = _merge_env_overrides(env, env_overrides)
        if debug_vm:
            command_line = [*command_line, "--debug-vm"]
            env = _build_debug_vm_env(env)

        if _delegation_trace_enabled():
            print(shlex.join(command_line), file=sys.stderr)

        try:
            process = process_runner(command_line, env)
        except OSError as exception:
            print(f"Failed executing delegated command: {exception}", file=sys.stderr)
            return INTERNAL_ERROR

        restart_requested_at: float | None = None

        try:
            while True:
                code = process.poll()
                if code is not None:
                    return int(code)

                sleep(poll_interval)
                next_snapshot = snapshotter(project_root)
                if next_snapshot == snapshot:
                    restart_requested_at = None
                    continue

                now = monotonic()
                if restart_requested_at is None:
                    restart_requested_at = now
                    continue

                if now - restart_requested_at < debounce_seconds:
                    continue

                stop_ok: bool | None = None
                refresh_code: int | None = None
                refresh_exception: BaseException | None = None

                def _stop_worker() -> None:
                    nonlocal stop_ok
                    stop_ok = _stop_managed_process(process)

                def _refresh_worker() -> None:
                    nonlocal refresh_code, refresh_exception
                    try:
                        refresh_code = _run_preflight(str(project_root), no_cache, execute, resolver)
                    except BaseException as exc:
                        refresh_exception = exc
                        refresh_code = INTERNAL_ERROR

                stop_thread = threading.Thread(target=_stop_worker, name="pyronaut-stop-for-restart", daemon=True)
                refresh_thread = threading.Thread(target=_refresh_worker, name="pyronaut-refresh-for-restart", daemon=True)
                stop_thread.start()
                refresh_thread.start()
                stop_thread.join()
                refresh_thread.join()

                if stop_ok is not True:
                    print("Failed to stop running process for restart.", file=sys.stderr)
                    return INTERNAL_ERROR
                if refresh_exception is not None:
                    print("Failed to refresh artifacts for restart.", file=sys.stderr)
                    return INTERNAL_ERROR
                if refresh_code is None:
                    print("Failed to refresh artifacts for restart.", file=sys.stderr)
                    return INTERNAL_ERROR
                if refresh_code != SUCCESS:
                    print(f"Failed to refresh artifacts for restart (exit code {refresh_code}).", file=sys.stderr)
                    return int(refresh_code)
                snapshot = next_snapshot
                break
        except KeyboardInterrupt:
            _stop_managed_process(process)
            return 130


def _stop_managed_process(process: ManagedProcess) -> bool:
    try:
        process.terminate()
        process.wait(timeout=3)
        return True
    except Exception:
        try:
            process.kill()
            process.wait(timeout=5)
            return True
        except Exception:
            return False


def _snapshot_watched_files(project_dir: Path) -> tuple[tuple[str, int, int], ...]:
    watched_roots = ("src", "tests", "config")
    ignored_dirs = {"__pyronaut__", ".pytest_cache", "build", ".gradle", "__pycache__", ".git"}
    entries: list[tuple[str, int, int]] = []

    for root_name in watched_roots:
        root = project_dir / root_name
        if not root.is_dir():
            continue

        for current_root, dirs, files in __import__("os").walk(root):
            dirs[:] = [name for name in dirs if name not in ignored_dirs and not name.startswith(".")]
            base = Path(current_root)
            for file_name in sorted(files):
                if file_name.startswith("."):
                    continue
                file_path = base / file_name
                try:
                    stat = file_path.stat()
                except OSError:
                    continue
                relative = file_path.relative_to(project_dir).as_posix()
                entries.append((relative, int(stat.st_mtime_ns), int(stat.st_size)))

    entries.sort(key=lambda item: item[0])
    return tuple(entries)


def _spawn_subprocess(command_line: list[str], env: dict[str, str] | None = None) -> ManagedProcess:
    return subprocess.Popen(command_line, env=env)


def _default_java_home_provider(
    *,
    runner: Runner | None,
    runner_with_env: RunnerWithEnv | None,
    process_runner: ProcessRunner | None,
) -> JavaHomeProvider | None:
    if runner is None and runner_with_env is None and process_runner is None:
        return _ensure_graalvm_java_home
    return None


def _build_java_home_env(command: str, java_home_provider: JavaHomeProvider | None) -> dict[str, str] | None:
    if command not in {"run", "test", "build", "tui"}:
        return None
    env = dict(os.environ)
    if java_home_provider is None:
        return env

    java_home = java_home_provider()
    if java_home is None or not java_home.strip():
        raise RuntimeError("Unable to locate or provision compatible GraalVM JDK (requires JDK 25+)")

    env["JAVA_HOME"] = java_home
    java_bin = str(Path(java_home) / "bin")
    path_value = env.get("PATH", "")
    if path_value:
        if not path_value.startswith(java_bin + os.pathsep):
            env["PATH"] = java_bin + os.pathsep + path_value
    else:
        env["PATH"] = java_bin
    return env


def _build_debug_vm_env(base_env: dict[str, str] | None = None) -> dict[str, str]:
    env = dict(base_env) if base_env is not None else dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = _merge_java_tool_options(env.get("JAVA_TOOL_OPTIONS"), _JDWP_FLAGS)
    return env


def _ensure_graalvm_java_home() -> str | None:
    global _provisioned_graalvm_home
    if _provisioned_graalvm_home is not None:
        return _provisioned_graalvm_home

    env_java_home = _read_env("JAVA_HOME")
    if env_java_home and _is_compatible_graalvm_home(Path(env_java_home)):
        _provisioned_graalvm_home = env_java_home
        return env_java_home

    pyronaut_jdks = _graalvm_jdks_root()
    pyronaut_jdks.mkdir(parents=True, exist_ok=True)

    cached = _find_compatible_cached_jdk(pyronaut_jdks)
    if cached is not None:
        _provisioned_graalvm_home = str(cached)
        return _provisioned_graalvm_home

    sdkman_home = _install_with_sdkman(pyronaut_jdks)
    if sdkman_home is not None:
        _provisioned_graalvm_home = str(sdkman_home)
        return _provisioned_graalvm_home

    downloaded = _download_and_install_graalvm(pyronaut_jdks)
    if downloaded is not None:
        _provisioned_graalvm_home = str(downloaded)
        return _provisioned_graalvm_home
    return None


def _graalvm_jdks_root() -> Path:
    return Path.home() / ".pyronaut" / "jdks"


def _find_compatible_cached_jdk(jdks_root: Path) -> Path | None:
    for child in sorted(jdks_root.iterdir(), key=lambda path: path.name):
        if not child.is_dir():
            continue
        home = _normalize_extracted_home(child)
        if home is not None and _is_compatible_graalvm_home(home):
            return home
    return None


def _normalize_extracted_home(path: Path) -> Path | None:
    if (path / "bin" / "java").exists():
        return path

    contents_home = path / "Contents" / "Home"
    if (contents_home / "bin" / "java").exists():
        return contents_home

    candidates = [p for p in path.iterdir() if p.is_dir()] if path.exists() else []
    for candidate in candidates:
        if (candidate / "bin" / "java").exists():
            return candidate
        nested_contents = candidate / "Contents" / "Home"
        if (nested_contents / "bin" / "java").exists():
            return nested_contents
    return None


def _is_compatible_graalvm_home(java_home: Path) -> bool:
    java_bin = java_home / "bin" / "java"
    if not java_bin.exists():
        return False
    try:
        completed = subprocess.run(
            [str(java_bin), "-version"],
            check=False,
            capture_output=True,
            text=True,
        )
    except Exception:
        return False

    if completed.returncode != 0:
        return False
    output = (completed.stdout or "") + "\n" + (completed.stderr or "")
    lower = output.lower()
    if "graalvm" not in lower:
        return False
    major = _parse_java_major_version(output)
    return major is not None and major >= _GRAALVM_MIN_JDK_MAJOR


def _parse_java_major_version(version_output: str) -> int | None:
    for line in version_output.splitlines():
        if "version" not in line:
            continue
        quote_index = line.find('"')
        if quote_index < 0:
            continue
        end_quote = line.find('"', quote_index + 1)
        if end_quote < 0:
            continue
        raw_version = line[quote_index + 1:end_quote]
        if raw_version.startswith("1."):
            try:
                return int(raw_version.split(".")[1])
            except Exception:
                return None
        first = raw_version.split(".")[0]
        try:
            return int(first)
        except Exception:
            return None
    return None


def _install_with_sdkman(jdks_root: Path) -> Path | None:
    sdkman_dir = _read_env("SDKMAN_DIR") or str(Path.home() / ".sdkman")
    init_script = Path(sdkman_dir) / "bin" / "sdkman-init.sh"
    if not init_script.exists():
        return None

    install_command = (
        f"source {shlex.quote(str(init_script))} && "
        f"sdk install java {_GRAALVM_SDKMAN_CANDIDATE}"
    )
    install_result = subprocess.run(["bash", "-lc", install_command], check=False, capture_output=True, text=True)
    if install_result.returncode != 0:
        return None

    home_command = (
        f"source {shlex.quote(str(init_script))} && "
        f"sdk home java {_GRAALVM_SDKMAN_CANDIDATE}"
    )
    home_result = subprocess.run(["bash", "-lc", home_command], check=False, capture_output=True, text=True)
    if home_result.returncode != 0:
        return None

    installed_home = Path((home_result.stdout or "").strip())
    if not installed_home.exists() or not _is_compatible_graalvm_home(installed_home):
        return None

    link_target = jdks_root / installed_home.name
    if not link_target.exists():
        try:
            link_target.symlink_to(installed_home)
        except Exception:
            shutil.copytree(installed_home, link_target, dirs_exist_ok=True)

    normalized = _normalize_extracted_home(link_target) or link_target
    return normalized if _is_compatible_graalvm_home(normalized) else None


def _download_and_install_graalvm(jdks_root: Path) -> Path | None:
    archive_url = _resolve_graalvm_archive_url()
    if archive_url is None:
        return None

    with tempfile.TemporaryDirectory(prefix="pyronaut-graalvm-") as temp_dir:
        temp_path = Path(temp_dir)
        archive_name = archive_url.rsplit("/", 1)[-1]
        archive_file = temp_path / archive_name
        try:
            urllib.request.urlretrieve(archive_url, archive_file)
        except Exception:
            return None

        extract_dir = temp_path / "extract"
        extract_dir.mkdir(parents=True, exist_ok=True)
        try:
            if archive_name.endswith(".zip"):
                shutil.unpack_archive(str(archive_file), str(extract_dir))
            else:
                with tarfile.open(archive_file, "r:*") as tf:
                    tf.extractall(extract_dir)
        except Exception:
            return None

        extracted_homes = []
        for child in extract_dir.iterdir():
            normalized = _normalize_extracted_home(child)
            if normalized is not None:
                extracted_homes.append(normalized)

        for home in extracted_homes:
            if not _is_compatible_graalvm_home(home):
                continue
            destination = jdks_root / home.parent.name if (home.parent / "bin" / "java").exists() and home.name == "Home" else jdks_root / home.name
            if destination.exists():
                normalized_existing = _normalize_extracted_home(destination) or destination
                if _is_compatible_graalvm_home(normalized_existing):
                    return normalized_existing
                shutil.rmtree(destination, ignore_errors=True)

            source_root = home.parent if home.name == "Home" and home.parent.name == "Contents" else home
            shutil.copytree(source_root, destination, dirs_exist_ok=True)
            normalized_destination = _normalize_extracted_home(destination) or destination
            if _is_compatible_graalvm_home(normalized_destination):
                return normalized_destination
    return None


def _resolve_graalvm_archive_url() -> str | None:
    system = platform.system().lower()
    machine = platform.machine().lower()

    if system.startswith("linux"):
        os_segment = "linux"
        ext = "tar.gz"
    elif system == "darwin":
        os_segment = "macos"
        ext = "tar.gz"
    elif system.startswith("win"):
        os_segment = "windows"
        ext = "zip"
    else:
        return None

    if machine in {"x86_64", "amd64"}:
        arch = "x64"
    elif machine in {"aarch64", "arm64"}:
        arch = "aarch64"
    else:
        return None

    if os_segment == "macos" and arch == "x64":
        return None

    base = f"graalvm-community-jdk-25.0.2_{os_segment}-{arch}_bin.{ext}"
    return f"https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/{base}"


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


def _normalize_tests_selection_flag(args: list[str]) -> list[str]:
    normalized: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--tests":
            normalized.append("--tests")
            index += 1
            if index < len(args):
                normalized.append(args[index])
                index += 1
            continue
        if token.startswith("--tests="):
            normalized.append("--tests=" + token.split("=", 1)[1])
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


def _is_test_resources_start(args: Sequence[str]) -> bool:
    return len(args) > 0 and args[0].strip().lower() == "start"


def _strip_no_cache_flag(args: Sequence[str]) -> list[str]:
    filtered: list[str] = []
    for token in args:
        if token == "--no-cache" or token.startswith("--no-cache="):
            continue
        filtered.append(token)
    return filtered


def _extract_no_validate(args: Sequence[str]) -> bool:
    return any(token == "--no-validate" for token in args)


def _remove_no_validate(args: Sequence[str]) -> list[str]:
    return [token for token in args if token != "--no-validate"]


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


def _run_subprocess(command_line: list[str], env: dict[str, str] | None = None) -> int:
    try:
        completed = subprocess.run(command_line, check=False, env=env)
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


def _test_resources_disabled() -> bool:
    value = _read_env("PYRONAUT_TEST_RESOURCES_DISABLED")
    if value is None:
        return False
    return value.lower() in {"1", "true", "yes", "on"}


def _is_supported_platform(platform_name: str) -> bool:
    return platform_name.startswith("linux") or platform_name == "darwin"


def _print_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut [--version] [--tui [--smoke|--non-interactive]] <install|process|run|test|build|validate-config|test-resources-server> [args...]\n")


def _print_build_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut build [--project-dir <dir>] [--native|--jvm|--mode=<native|jvm>] [--main-class <fqcn>] [--verbose] [--no-cache] [--no-validate]\n"
    )


def _run_tui(*, argv: list[str], runner_with_env: RunnerWithEnv, resolver: Callable[[str], str | None]) -> int:
    from .tui.app import TuiApp, TuiOptions
    from .tui.reports import render_summary_line

    args = [a for a in argv if a != "--tui"]

    if _extract_flag(args, "--help") or _extract_flag(args, "-h"):
        return _delegate_to_tui_binary(["--help"], runner_with_env, resolver)
    if _extract_flag(args, "--version") or _extract_flag(args, "-V"):
        return _delegate_to_tui_binary(["--version"], runner_with_env, resolver)

    project_dir = Path(_extract_project_dir(args)).resolve()
    smoke = _extract_flag(args, "--smoke") or _extract_flag(args, "--non-interactive")
    non_interactive = _extract_flag(args, "--non-interactive")
    initial_mode = "test" if _extract_flag(args, "--test") else "run"
    report_dir = (_extract_path_flag(args, "--report-dir") or (project_dir / "__pyronaut__" / "reports" / "tests")).resolve()
    trace_delegation = _delegation_trace_enabled() or _extract_flag(args, "--trace-delegation")

    if not smoke and not non_interactive:
        return _run_tamboui_tui(
            project_dir=project_dir,
            initial_mode=initial_mode,
            report_dir=report_dir,
            trace_delegation=trace_delegation,
            runner=runner_with_env,
            resolver=resolver,
        )

    def _delegate(command: str, forwarded: Sequence[str]) -> int:
        previous = os.environ.get("PYRONAUT_TRACE_DELEGATION")
        if trace_delegation:
            os.environ["PYRONAUT_TRACE_DELEGATION"] = "true"
        try:
            return run([command, *forwarded], runner_with_env=runner_with_env, resolver=resolver)
        finally:
            if trace_delegation:
                if previous is None:
                    os.environ.pop("PYRONAUT_TRACE_DELEGATION", None)
                else:
                    os.environ["PYRONAUT_TRACE_DELEGATION"] = previous

    options = TuiOptions(
        project_dir=project_dir,
        report_dir=report_dir,
        initial_mode=initial_mode,
        smoke=smoke,
        non_interactive=non_interactive,
        trace_delegation=trace_delegation,
    )
    app = TuiApp(options=options, delegate=_delegate, report_summary=render_summary_line)
    return app.run()


class _OwnedTestResourcesSession:
    def __init__(self, *, project_dir: Path, owner_command: str) -> None:
        self._project_dir = project_dir
        self._session_file = project_dir / "__pyronaut__" / "test-resources-session.json"
        self._settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
        self._owner_pid = os.getpid()
        self._owner_command = owner_command
        self._owner_token = self._new_owner_token()
        self._shared_server = _read_pyproject_test_resources_shared(project_dir)
        self._started = False
        self._shutdown_registered = False
        self._client_env_overrides: dict[str, str] | None = None

    @staticmethod
    def _new_owner_token() -> str:
        return f"{os.getpid()}-{int(time.time() * 1000)}"

    def ensure_started(self, *, runner: RunnerWithEnv, resolver: Callable[[str], str | None]) -> None:
        cache_dir = self._project_dir / "__pyronaut__"
        cache_dir.mkdir(parents=True, exist_ok=True)
        self._remove_session_file()

        sys.stderr.write("[test-resources] start owned server\n")
        self._delegate_test_resources_server(
            [
                "start",
                "--project-dir",
                str(self._project_dir),
                "--owner-token",
                self._owner_token,
            ],
            runner=runner,
            resolver=resolver,
        )

        if self._shared_server:
            sys.stderr.write("[test-resources] shared-server mode: will not stop server on session exit\n")
        else:
            self._persist_session(started_at=time.time())
        self._started = True
        self._client_env_overrides = _test_resources_client_env_from_settings(self._settings_file)
        self._register_shutdown(runner=runner, resolver=resolver)

    def stop_if_owned(self, *, runner: RunnerWithEnv, resolver: Callable[[str], str | None]) -> None:
        if not self._started:
            return

        if self._shared_server:
            sys.stderr.write("[test-resources] stop skipped (shared server mode)\n")
            self._remove_session_file()
            return

        if not self._session_matches_owner():
            sys.stderr.write("[test-resources] stop skipped (ownership mismatch)\n")
            return

        sys.stderr.write("[test-resources] stop owned server\n")
        self._delegate_test_resources_server(
            [
                "stop",
                "--project-dir",
                str(self._project_dir),
                "--owner-token",
                self._owner_token,
            ],
            runner=runner,
            resolver=resolver,
        )
        self._remove_session_file()

    def client_env_overrides(self) -> dict[str, str] | None:
        if self._client_env_overrides is None:
            return None
        return dict(self._client_env_overrides)

    def _register_shutdown(self, *, runner: RunnerWithEnv, resolver: Callable[[str], str | None]) -> None:
        if self._shutdown_registered:
            return

        def _shutdown() -> None:
            try:
                self.stop_if_owned(runner=runner, resolver=resolver)
            except Exception:
                return

        atexit.register(_shutdown)
        self._shutdown_registered = True

    def _persist_session(self, *, started_at: float) -> None:
        data = {
            "ownerToken": self._owner_token,
            "ownerPid": self._owner_pid,
            "ownerCommand": self._owner_command,
            "startedAt": started_at,
        }
        self._session_file.write_text(_json_dumps(data) + "\n", encoding="utf-8")

    def _session_matches_owner(self) -> bool:
        try:
            data = _json_loads(self._session_file.read_text(encoding="utf-8"))
        except Exception:
            return False
        if not isinstance(data, dict):
            return False
        return (
            data.get("ownerToken") == self._owner_token
            and data.get("ownerPid") == self._owner_pid
            and data.get("ownerCommand") == self._owner_command
        )

    def _remove_session_file(self) -> None:
        try:
            self._session_file.unlink(missing_ok=True)
        except Exception:
            return

    def _delegate_test_resources_server(
        self,
        args: list[str],
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
    ) -> int:
        executable_path = resolver(COMMAND_TO_EXECUTABLE["test-resources-server"])
        if executable_path is None:
            raise RuntimeError("Missing delegated executable: pyronaut-test-resources-server")
        command_line = [executable_path, *args]
        if _delegation_trace_enabled():
            print(shlex.join(command_line), file=sys.stderr)
        removed_server_port = os.environ.pop("MICRONAUT_SERVER_PORT", None)
        removed_server_host = os.environ.pop("MICRONAUT_SERVER_HOST", None)
        try:
            return int(runner(command_line, None))
        finally:
            if removed_server_port is not None:
                os.environ["MICRONAUT_SERVER_PORT"] = removed_server_port
            if removed_server_host is not None:
                os.environ["MICRONAUT_SERVER_HOST"] = removed_server_host


def _json_dumps(data: object) -> str:
    import json

    return json.dumps(data, indent=2, sort_keys=True)


def _json_loads(data: str):
    import json

    return json.loads(data)


def _run_tamboui_tui(
    *,
    project_dir: Path,
    initial_mode: str,
    report_dir: Path,
    trace_delegation: bool,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
) -> int:
    tui_executable = _resolve_required_tui_executable(resolver)
    if tui_executable is None:
        return PRECONDITION_FAILED

    try:
        base_env = _build_java_home_env("tui", _ensure_graalvm_java_home)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED

    tr_session: _OwnedTestResourcesSession | None = None
    test_resources_env_overrides: dict[str, str] | None = None
    if initial_mode in {"run", "test"} and not _test_resources_disabled():
        tr_session = _OwnedTestResourcesSession(
            project_dir=project_dir.resolve(),
            owner_command=shlex.join(["pyronaut", "--tui", f"--{initial_mode}", "--project-dir", str(project_dir)]),
        )
        tr_session.ensure_started(runner=runner, resolver=resolver)
        test_resources_env_overrides = tr_session.client_env_overrides()
    elif initial_mode in {"run", "test"} and _test_resources_disabled():
        sys.stderr.write("[test-resources] skipped (disabled via PYRONAUT_TEST_RESOURCES_DISABLED)\n")

    delegated = {
        "install": resolver("pyronaut-install"),
        "process": resolver("pyronaut-processor"),
        "run": resolver("pyronaut-run"),
        "test": resolver("pyronaut-test"),
    }
    missing = [name for (name, path) in delegated.items() if path is None]
    if missing:
        print("Missing delegated executable(s): " + ", ".join(f"pyronaut-{name}" for name in missing), file=sys.stderr)
        return PRECONDITION_FAILED

    command_line = [
        tui_executable,
        "delegating-tui",
        "--project-dir",
        str(project_dir),
        "--mode",
        initial_mode,
        "--report-dir",
        str(report_dir),
        "--install-executable",
        str(delegated["install"]),
        "--process-executable",
        str(delegated["process"]),
        "--run-executable",
        str(delegated["run"]),
        "--test-executable",
        str(delegated["test"]),
    ]
    if trace_delegation:
        command_line.append("--trace-delegation")
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    try:
        return runner(command_line, _merge_env_overrides(base_env, test_resources_env_overrides))
    finally:
        if tr_session is not None:
            tr_session.stop_if_owned(runner=runner, resolver=resolver)


def _delegate_to_tui_binary(
    args: Sequence[str], runner: RunnerWithEnv, resolver: Callable[[str], str | None]
) -> int:
    tui_executable = _resolve_required_tui_executable(resolver)
    if tui_executable is None:
        return PRECONDITION_FAILED
    command_line = [tui_executable, *args]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    return runner(command_line, None)


def _resolve_required_tui_executable(resolver: Callable[[str], str | None]) -> str | None:
    tui_executable = resolver("pyronaut-tui")
    if tui_executable is None:
        print("Missing delegated executable: pyronaut-tui", file=sys.stderr)
        return None
    return tui_executable


def _extract_flag(argv: Sequence[str], name: str) -> bool:
    return any(token == name or token.startswith(name + "=") for token in argv)


def _extract_path_flag(argv: Sequence[str], name: str) -> Path | None:
    for i, token in enumerate(argv):
        if token == name and i + 1 < len(argv):
            value = argv[i + 1].strip()
            return Path(value) if value else None
        if token.startswith(name + "="):
            value = token.split("=", 1)[1].strip()
            return Path(value) if value else None
    return None
