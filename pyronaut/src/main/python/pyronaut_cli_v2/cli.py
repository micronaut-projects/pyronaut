from __future__ import annotations

import atexit
import contextlib
import hashlib
import json
import shlex
import shutil
import subprocess
import sys
import sysconfig
import socket
import os
import platform
import re
import tarfile
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, NamedTuple, Protocol, Sequence

SUCCESS = 0
USAGE_ERROR = 2
PRECONDITION_FAILED = 8
PLATFORM_UNSUPPORTED = 9
INTERNAL_ERROR = 10

SUPPORTED_COMMANDS = {"install", "process", "run", "test", "build", "create", "validate-config", "test-resources-server"}
LOCAL_REPOSITORY_ENV = "PYRONAUT_LOCAL_REPOSITORY"
COMMAND_TO_EXECUTABLE = {
    "install": "pyronaut-install",
    "process": "pyronaut-processor",
    "run": "pyronaut-run",
    "test": "pyronaut-test",
    "create": "pyronaut-create",
    "validate-config": "pyronaut-validate-config",
    "test-resources-server": "pyronaut-test-resources-server",
}

JAVA_MAIN_BY_COMMAND = {
    "run": "io.micronaut.pyronaut.run.PyronautRunMain",
    "test": "io.micronaut.pyronaut.test.PyronautTestMain",
}
JAVA_DELEGATE_JAR_ENV = {
    "run": "PYRONAUT_RUN_JAR",
    "test": "PYRONAUT_TEST_JAR",
}
NATIVE_BUILD_EXECUTABLE = "pyronaut-native-build"
_DEFAULT_DOCKER_JVM_BASE_IMAGE = "container-registry.oracle.com/graalvm/jdk:25"
_DEFAULT_DOCKER_NATIVE_BUILDER_IMAGE = "container-registry.oracle.com/graalvm/native-image:25"
_DEFAULT_DOCKER_NATIVE_BASE_IMAGE = "gcr.io/distroless/base"
_DEFAULT_DOCKER_STATIC_NATIVE_BUILDER_IMAGE = "container-registry.oracle.com/graalvm/native-image:25-muslib"
_DEFAULT_DOCKER_STATIC_NATIVE_BASE_IMAGE = "scratch"

_DELEGATE_JVM_FLAGS = [
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
]
_JDWP_FLAGS = "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
_TEST_RESOURCES_ENV_TO_PROPERTY = {
    "MICRONAUT_TEST_RESOURCES_SERVER_URI": "micronaut.test.resources.server.uri",
    "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN": "micronaut.test.resources.server.access.token",
    "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT": "micronaut.test.resources.server.client.read.timeout",
}
_TEST_RESOURCES_LOG_FILE = "test-resources.log"
_TEST_RESOURCES_STDIO_LOG_FILE = "launcher-stdio.log"
_TEST_RESOURCES_IMAGE_PULL_MARKER = "Pulling docker image:"
_TEST_RESOURCES_CONTAINER_CREATE_MARKER = "Creating container for image:"
_TEST_RESOURCES_CONTAINER_STARTED_MARKER = " started in PT"
_DEFAULT_PYTHON_SOURCE_DIR = "src"
_DEFAULT_PYTHON_TEST_DIR = "tests"
_DEFAULT_JAVA_SOURCE_DIR = "src-java"
_DEFAULT_JAVA_TEST_DIR = "test-java"
_DEFAULT_RESOURCES_DIR = "config"
_DEFAULT_TEST_RESOURCES_DIR = "tests-config"

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
_DEFAULT_GRAALVM_DOWNLOAD_VERSION = "25.0.2"
_DEFAULT_GRAALVM_DOWNLOAD_DISTRIBUTION = "ce"
_GRAALVM_CE_DEV_BUILDS_REPOSITORY = "graalvm/graalvm-ce-dev-builds"
_ORACLE_GRAALVM_EA_BUILDS_REPOSITORY = "graalvm/oracle-graalvm-ea-builds"


class _ToolchainSpec(NamedTuple):
    distribution: str | None
    version: str | None
    java_version: int
    release_tag: str | None = None
    download_url: str | None = None
    explicit: bool = False


class _GraalVmMetadata(NamedTuple):
    version: str | None
    java_version: int | None
    distribution: str | None


class _ProjectLayout(NamedTuple):
    python_source_dir: str = _DEFAULT_PYTHON_SOURCE_DIR
    python_test_dir: str = _DEFAULT_PYTHON_TEST_DIR
    java_source_dir: str = _DEFAULT_JAVA_SOURCE_DIR
    java_test_dir: str = _DEFAULT_JAVA_TEST_DIR
    resources_dir: str = _DEFAULT_RESOURCES_DIR
    test_resources_dir: str = _DEFAULT_TEST_RESOURCES_DIR
    additional_resources_dirs: tuple[str, ...] = ()
    additional_test_resources_dirs: tuple[str, ...] = ()


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
    local_repository = _extract_local_repository(forwarded_args)
    delegated_args = _strip_no_cache_flag(forwarded_args) if command in {"run", "test"} else forwarded_args
    delegated_args = _strip_local_repository_args(delegated_args) if command in {"run", "test"} else delegated_args
    auto_restart_mode = command == "run" and (process_runner is not None or (runner is None and runner_with_env is None))

    effective_java_home_provider = java_home_provider or _default_java_home_provider(
        runner=runner,
        runner_with_env=runner_with_env,
        process_runner=process_runner,
        project_dir=Path(project_dir),
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
        install_args = ["--project-dir", project_dir, *_local_repository_install_args()]
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
        test_resources_enabled = _test_resources_enabled(Path(project_dir))
        if command in {"run", "test"} and test_resources_enabled:
            tr_session = _OwnedTestResourcesSession(
                project_dir=Path(project_dir).resolve(),
                owner_command=shlex.join(["pyronaut", command, *forwarded_args]),
            )

        if auto_restart_mode:
            if no_validate:
                sys.stderr.write("[validation] skipped (--no-validate)\n")
            else:
                validation_code = _run_lifecycle_validation(
                    project_dir=project_dir,
                    scenario=command,
                    runner=execute,
                    resolver=locate,
                    no_cache=no_cache,
                    env_overrides=test_resources_env_overrides,
                )
                if validation_code != SUCCESS:
                    return validation_code
            preflight_code = _run_preflight(project_dir, no_cache, local_repository, execute, locate)
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
                local_repository=local_repository,
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
                    env_overrides=test_resources_env_overrides,
                )
                if validation_code != SUCCESS:
                    return validation_code

            preflight_code = _run_preflight(project_dir, no_cache, local_repository, execute, locate)
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
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
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
    if command == "test" and not debug_vm:
        try:
            executable_path = _resolve_delegate_executable_path(command, args, resolver)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        default_executable = resolver(COMMAND_TO_EXECUTABLE[command])
        if executable_path != default_executable:
            command_line = [executable_path, *args]
            if _delegation_trace_enabled():
                print(shlex.join(command_line), file=sys.stderr)
            try:
                env = _build_non_test_resources_env(command, java_home_provider)
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
            env = _merge_env_overrides(env, env_overrides)
            env = _apply_project_virtualenv(env, Path(_extract_project_dir(args)).resolve())
            return runner(command_line, env)

    if command in {"run", "test"}:
        return _delegate_via_java(
            command,
            args,
            runner,
            resolver,
            debug_vm=debug_vm,
            env_overrides=env_overrides,
            java_home_provider=java_home_provider,
        )

    try:
        executable_path = _resolve_delegate_executable_path(command, args, resolver)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    command_line = [executable_path, *args]
    if debug_vm and command in {"run", "test"}:
        command_line = [*command_line, "--debug-vm"]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    try:
        if command in {"install", "process", "validate-config"}:
            env = _build_non_test_resources_env(command, java_home_provider)
        else:
            env = _build_java_home_env(command, java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    env = _merge_env_overrides(env, env_overrides)
    return runner(command_line, env)


def _delegate_via_java(
    command: str,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
    env_overrides: dict[str, str] | None = None,
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    try:
        command_line, env = _build_java_delegate_invocation(
            command,
            args,
            resolver,
            debug_vm=debug_vm,
            env_overrides=env_overrides,
            java_home_provider=java_home_provider,
        )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    return runner(command_line, env)


def _build_java_delegate_invocation(
    command: str,
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
    env_overrides: dict[str, str] | None = None,
    java_home_provider: JavaHomeProvider | None = None,
) -> tuple[list[str], dict[str, str] | None]:
    project_dir = Path(_extract_project_dir(args)).resolve()
    env = _build_non_test_resources_env(command, java_home_provider)
    env = _merge_env_overrides(env, env_overrides)
    env = _apply_project_virtualenv(env, project_dir)
    java_exec = _resolve_java_executable(env)
    classpath = _build_delegate_classpath(command, project_dir, resolver)
    jvm_args = _build_delegate_jvm_args(debug_vm)
    jvm_args.extend(_build_test_resources_jvm_args(env_overrides))

    command_line = [java_exec, *jvm_args, "-cp", classpath, JAVA_MAIN_BY_COMMAND[command], *args]
    if debug_vm:
        command_line = [*command_line, "--debug-vm"]
    return command_line, env


def _resolve_java_executable(env: dict[str, str] | None) -> str:
    if env is not None:
        java_home = env.get("JAVA_HOME")
        if java_home:
            java_bin = Path(java_home) / "bin" / "java"
            if java_bin.exists():
                return str(java_bin)
    resolved = shutil.which("java", path=(env or os.environ).get("PATH"))
    if resolved is None:
        raise RuntimeError("Unable to locate java executable for delegated run/test launch")
    return resolved


def _read_manifest_entries(file: Path) -> list[str]:
    if not file.exists():
        raise RuntimeError(f"Missing classpath manifest: {file}. Run pyronaut install first.")
    entries: list[str] = []
    for line in file.read_text(encoding="utf-8").splitlines():
        value = line.strip()
        if value:
            entries.append(value)
    return entries


def _resolve_run_manifest(cache_dir: Path) -> Path:
    development_manifest = cache_dir / "resolved-development-runtime-dependencies"
    if development_manifest.exists():
        return development_manifest
    return cache_dir / "resolved-runtime-dependencies"


def _delegate_lib_entries(executable_path: str) -> list[str]:
    path = Path(executable_path).resolve()
    if path.suffix == ".jar":
        return [str(path)]
    lib_dir = path.parent.parent / "lib"
    jars = sorted(lib_dir.glob("*.jar"))
    if jars:
        return [str(jar) for jar in jars]
    command_name = path.name
    raise RuntimeError(f"Unable to resolve delegate jars for {command_name} from {lib_dir}")


def _build_delegate_classpath(command: str, project_dir: Path, resolver: Callable[[str], str | None]) -> str:
    cache_dir = project_dir / "__pyronaut__"
    if command == "run":
        classes_dir = cache_dir / "classes"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        entries = _read_manifest_entries(_resolve_run_manifest(cache_dir))
    elif command == "test":
        entries = _read_test_delegate_dependency_entries(cache_dir)
    else:
        entries = _read_test_delegate_dependency_entries(cache_dir)

    override_jar = _read_env(JAVA_DELEGATE_JAR_ENV[command])
    if override_jar:
        entries.extend([value for value in override_jar.split(os.pathsep) if value])
    else:
        delegate_executable = resolver(COMMAND_TO_EXECUTABLE[command])
        if delegate_executable is None:
            raise RuntimeError(f"Missing delegated executable: {COMMAND_TO_EXECUTABLE[command]}")
        entries.extend(_delegate_lib_entries(delegate_executable))

    deduped: list[str] = []
    seen_paths: set[str] = set()
    seen_file_names: set[str] = set()
    for entry in entries:
        normalized = str(Path(entry).expanduser().resolve())
        file_name = Path(entry).name
        if normalized in seen_paths:
            continue
        if file_name and file_name in seen_file_names:
            continue
        deduped.append(entry)
        seen_paths.add(normalized)
        if file_name:
            seen_file_names.add(file_name)
    return os.pathsep.join(deduped)


def _read_test_delegate_dependency_entries(cache_dir: Path) -> list[str]:
    return [
        entry
        for manifest in (
            cache_dir / "resolved-test-dependencies",
            cache_dir / "resolved-runtime-dependencies",
            cache_dir / "resolved-build-dependencies",
        )
        if manifest.exists()
        for entry in _read_manifest_entries(manifest)
        if not _is_test_launcher_provided_artifact(entry)
    ]


def _is_test_launcher_provided_artifact(entry: str) -> bool:
    file_name = Path(entry).name
    return (
        file_name.startswith("micronaut-context-python-")
        or file_name.startswith("micronaut-pyronaut-logback-")
        or file_name.startswith("micronaut-pyronaut-pytest-")
    )


def _run_preflight(
    project_dir: str,
    no_cache: bool,
    local_repository: str | None,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
) -> int:
    install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
    if no_cache:
        install_args.append("--no-cache")
    install_code = _delegate("install", install_args, runner, resolver)
    if install_code != SUCCESS:
        return install_code

    process_args = ["--project-dir", project_dir]
    if no_cache:
        process_args.append("--no-cache")
    return _delegate("process", process_args, runner, resolver)


def _local_repository_install_args(local_repository: str | None = None) -> list[str]:
    repository = local_repository or _read_env(LOCAL_REPOSITORY_ENV)
    if repository is None:
        return []
    return ["--local-repository", repository]


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
    docker_build = _extract_build_docker(args)
    static_native = _extract_build_static(args)
    try:
        project_name, project_version = _read_pyproject_project_metadata(project_dir)
        main_class = _extract_main_class(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR

    if static_native and (mode != "native" or not docker_build):
        print("--static is only supported with pyronaut build --native --docker", file=sys.stderr)
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

    preflight = _run_preflight(str(project_dir), no_cache, None, runner, resolver)
    if preflight != SUCCESS:
        return preflight

    if docker_build:
        return _run_docker_build(
            args=args,
            runner=runner,
            resolver=resolver,
            project_dir=project_dir,
            project_name=project_name,
            project_version=project_version,
            mode=mode,
            main_class=main_class,
            verbose=verbose,
            static_native=static_native,
        )

    dist_dir = project_dir / "dist"
    dist_dir.mkdir(parents=True, exist_ok=True)
    _remove_existing_built_wheels(dist_dir, project_name)
    python_exec = _read_env("PYRONAUT_PYTHON_EXECUTABLE") or sys.executable or "python3"

    if mode == "native":
        try:
            env = _build_non_test_resources_env("build", java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED

        try:
            _build_native_classpath(project_dir)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        output_dir = project_dir / "__pyronaut__" / "native"
        output_dir.mkdir(parents=True, exist_ok=True)
        output_binary = output_dir / project_name
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
        if exit_code != SUCCESS:
            return exit_code
        if not output_binary.exists():
            print(f"Native build reported success but no binary was produced at: {output_binary}", file=sys.stderr)
            return PRECONDITION_FAILED
        with tempfile.TemporaryDirectory(prefix="pyronaut-build-native-") as staging_root:
            staging_dir = Path(staging_root) / "stage"
            _prepare_build_wheel_staging(
                project_dir=project_dir,
                staging_dir=staging_dir,
                project_name=project_name,
                project_version=project_version,
                mode="native",
                main_class=main_class,
            )
            wheel_command = [
                python_exec,
                "-m",
                "pip",
                "wheel",
                "--no-deps",
                "--wheel-dir",
                str(dist_dir),
                str(staging_dir),
            ]
            if _delegation_trace_enabled():
                print(shlex.join(wheel_command), file=sys.stderr)
            wheel_exit = runner(wheel_command, None)
        if wheel_exit == SUCCESS:
            print(f"Native wheel build complete. Artifacts are in: {dist_dir}")
            print(f"Install with: {python_exec} -m pip install {dist_dir}/*.whl")
            print(f"Native binary staged from: {output_binary}")
        return wheel_exit

    with tempfile.TemporaryDirectory(prefix="pyronaut-build-jvm-") as staging_root:
        staging_dir = Path(staging_root) / "stage"
        _prepare_build_wheel_staging(
            project_dir=project_dir,
            staging_dir=staging_dir,
            project_name=project_name,
            project_version=project_version,
            mode="jvm",
            main_class=main_class,
        )
        wheel_command = [
            python_exec,
            "-m",
            "pip",
            "wheel",
            "--no-deps",
            "--wheel-dir",
            str(dist_dir),
            str(staging_dir),
        ]
        if _delegation_trace_enabled():
            print(shlex.join(wheel_command), file=sys.stderr)
        exit_code = runner(wheel_command, None)
    if exit_code == SUCCESS:
        print(f"Wheel build complete. Artifacts are in: {dist_dir}")
        print(f"Install with: {python_exec} -m pip install {dist_dir}/*.whl")
        print(f"Run the project with: {project_name}")
    return exit_code


def _read_pyproject_project_metadata(project_dir: Path) -> tuple[str, str]:
    data = _read_pyproject_data(project_dir)
    if not isinstance(data, dict):
        return project_dir.name, "0.1.0"
    project = data.get("project")
    if not isinstance(project, dict):
        return project_dir.name, "0.1.0"
    name = project.get("name")
    version = project.get("version")
    resolved_name = name.strip() if isinstance(name, str) and name.strip() else project_dir.name
    resolved_version = version.strip() if isinstance(version, str) and version.strip() else "0.1.0"
    return resolved_name, resolved_version


def _read_pyproject_data(project_dir: Path) -> dict[str, object] | None:
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
    return data if isinstance(data, dict) else None


def _prepare_build_wheel_staging(
    *,
    project_dir: Path,
    staging_dir: Path,
    project_name: str,
    project_version: str,
    mode: str,
    main_class: str,
) -> None:
    if mode not in {"jvm", "native"}:
        raise ValueError(f"Unsupported build wheel staging mode: {mode}")

    layout = _read_pyproject_sources(project_dir)
    launcher_package = _launcher_package_name(project_name)
    launcher_dir = staging_dir / launcher_package
    app_dir = launcher_dir / "app"
    pyronaut_dir = app_dir / "__pyronaut__"

    launcher_dir.mkdir(parents=True, exist_ok=True)
    pyronaut_dir.mkdir(parents=True, exist_ok=True)

    (launcher_dir / "__init__.py").write_text("", encoding="utf-8")
    _write_build_setup_files(
        staging_dir=staging_dir,
        launcher_package=launcher_package,
        project_name=project_name,
        project_version=project_version,
        native=(mode == "native"),
    )
    _copy_layout_dir_if_exists(project_dir, layout.resources_dir, app_dir)
    for resource_dir in layout.additional_resources_dirs:
        _copy_layout_dir_if_exists(project_dir, resource_dir, app_dir)

    if mode == "native":
        binary_name = project_name
        binary_path = project_dir / "__pyronaut__" / "native" / binary_name
        if not binary_path.exists():
            raise RuntimeError(f"Missing native binary for packaging: {binary_path}")
        (pyronaut_dir / "native").mkdir(parents=True, exist_ok=True)
        shutil.copy2(binary_path, pyronaut_dir / "native" / binary_name)
        (pyronaut_dir / "native" / binary_name).chmod(0o755)
        launcher_code = _native_build_launcher_code(binary_name)
    else:
        classes_dir = project_dir / "__pyronaut__" / "classes"
        runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        if not runtime_manifest.exists():
            raise RuntimeError(f"Missing classpath manifest: {runtime_manifest}. Run pyronaut install first.")
        _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
        _stage_manifest_artifacts(
            source=runtime_manifest,
            target=pyronaut_dir / "resolved-runtime-dependencies",
            project_dir=project_dir,
            pyronaut_dir=pyronaut_dir,
        )
        launcher_code = _jvm_build_launcher_code(main_class)

    (launcher_dir / "launcher.py").write_text(launcher_code, encoding="utf-8")


def _write_build_setup_files(
    *,
    staging_dir: Path,
    launcher_package: str,
    project_name: str,
    project_version: str,
    native: bool,
) -> None:
    pyproject = """\
[build-system]
requires = ["setuptools>=61", "wheel"]
build-backend = "setuptools.build_meta"
"""
    setup_lines = [
        "from setuptools import setup",
    ]
    if native:
        setup_lines.extend(
            [
                "from wheel.bdist_wheel import bdist_wheel as _bdist_wheel",
                "",
                "class bdist_wheel(_bdist_wheel):",
                "    def finalize_options(self):",
                "        super().finalize_options()",
                "        self.root_is_pure = False",
                "",
            ]
        )
    setup_lines.extend(
        [
            "setup(",
            f"    name={project_name!r},",
            f"    version={project_version!r},",
            f"    packages=[{launcher_package!r}],",
            "    include_package_data=True,",
            "    zip_safe=False,",
            f"    entry_points={{'console_scripts': [{project_name!r} + '=' + {launcher_package!r} + '.launcher:main']}},",
            *(["    cmdclass={'bdist_wheel': bdist_wheel},"] if native else []),
            ")",
            "",
        ]
    )
    manifest = f"recursive-include {launcher_package}/app *\n"

    (staging_dir / "pyproject.toml").write_text(pyproject, encoding="utf-8")
    (staging_dir / "setup.py").write_text("\n".join(setup_lines), encoding="utf-8")
    (staging_dir / "MANIFEST.in").write_text(manifest, encoding="utf-8")


def _copytree_if_exists(source: Path, target: Path) -> None:
    if source.exists():
        shutil.copytree(source, target, dirs_exist_ok=True)


def _stage_manifest_artifacts(*, source: Path, target: Path, project_dir: Path, pyronaut_dir: Path) -> None:
    project_root = project_dir.resolve()
    artifact_root = pyronaut_dir / "m2-repository"
    rewritten: list[str] = []
    for entry in _read_manifest_entries(source):
        path = Path(entry)
        resolved = path.resolve() if path.is_absolute() else (project_root / path).resolve()
        if resolved.is_file():
            artifact_relative = _staged_artifact_relative_path(resolved, project_root)
            staged = artifact_root / artifact_relative
            staged.parent.mkdir(parents=True, exist_ok=True)
            if resolved != staged.resolve():
                shutil.copy2(resolved, staged)
            rewritten.append(str(Path("__pyronaut__") / "m2-repository" / artifact_relative))
            continue
        with contextlib.suppress(ValueError):
            rewritten.append(str(resolved.relative_to(project_root)))
            continue
        rewritten.append(entry)
    target.write_text("".join(f"{entry}\n" for entry in rewritten), encoding="utf-8")


def _staged_artifact_relative_path(resolved: Path, project_root: Path) -> Path:
    project_local_repository = project_root / "__pyronaut__" / "m2-repository"
    with contextlib.suppress(ValueError):
        return resolved.relative_to(project_local_repository)
    for parent in resolved.parents:
        if parent.name in {"repository", "m2-repository"}:
            return resolved.relative_to(parent)
    with contextlib.suppress(ValueError):
        return resolved.relative_to(project_root)
    digest = hashlib.sha256(str(resolved).encode("utf-8")).hexdigest()[:16]
    return Path("external-artifacts") / digest / resolved.name


def _extract_build_docker(args: Sequence[str]) -> bool:
    return any(token == "--docker" for token in args)


def _extract_build_static(args: Sequence[str]) -> bool:
    return any(token == "--static" for token in args)


def _read_pyproject_build_docker_config(project_dir: Path) -> dict[str, str]:
    data = _read_pyproject_data(project_dir)
    if not isinstance(data, dict):
        return {}

    tool = data.get("tool")
    if not isinstance(tool, dict):
        return {}
    pyronaut = tool.get("pyronaut")
    if not isinstance(pyronaut, dict):
        return {}
    build = pyronaut.get("build")
    if not isinstance(build, dict):
        return {}
    docker = build.get("docker")
    if not isinstance(docker, dict):
        return {}

    resolved: dict[str, str] = {}
    aliases = {
        "image_name": ("image-name", "imageName"),
        "dockerfile": ("dockerfile",),
        "dockerfile_native": ("dockerfile-native", "dockerfileNative"),
        "jvm_base_image": ("jvm-base-image", "jvmBaseImage"),
        "native_builder_image": ("native-builder-image", "nativeBuilderImage"),
        "native_base_image": ("native-base-image", "nativeBaseImage"),
        "static_native_builder_image": ("static-native-builder-image", "staticNativeBuilderImage"),
        "static_native_base_image": ("static-native-base-image", "staticNativeBaseImage"),
    }
    for key, candidates in aliases.items():
        for candidate in candidates:
            value = docker.get(candidate)
            if isinstance(value, str) and value.strip():
                resolved[key] = value.strip()
                break
    return resolved


def _default_docker_image_name(project_name: str) -> str:
    normalized = project_name.strip().lower()
    normalized = re.sub(r"[^a-z0-9._/-]+", "-", normalized)
    normalized = re.sub(r"-+", "-", normalized).strip("-/")
    if not normalized:
        return "pyronaut-app"
    return normalized


def _resolve_build_dockerfile(
    *,
    project_dir: Path,
    configured_path: str | None,
    default_name: str,
) -> Path | None:
    candidates: list[Path] = []
    if configured_path is not None and configured_path.strip():
        configured = Path(configured_path.strip())
        candidates.append(configured if configured.is_absolute() else (project_dir / configured))
    candidates.append(project_dir / default_name)
    for candidate in candidates:
        if candidate.exists() and candidate.is_file():
            return candidate.resolve()
    if configured_path is not None and configured_path.strip():
        raise RuntimeError(f"Configured Dockerfile does not exist: {configured_path}")
    return None


def _stage_delegate_distribution(executable_path: str, target_dir: Path) -> Path:
    resolved = Path(executable_path).resolve()
    if resolved.suffix == ".jar":
        raise RuntimeError(f"Docker builds require an installDist launcher, not a jar override: {resolved}")
    install_root = resolved.parent.parent
    if not install_root.exists():
        raise RuntimeError(f"Missing delegate distribution for Docker build: {resolved}")
    shutil.copytree(install_root, target_dir, dirs_exist_ok=True)
    return target_dir / "bin" / resolved.name


def _prepare_common_docker_app_context(project_dir: Path, context_dir: Path) -> Path:
    app_dir = context_dir / "app"
    pyronaut_dir = app_dir / "__pyronaut__"
    pyronaut_dir.mkdir(parents=True, exist_ok=True)
    layout = _read_pyproject_sources(project_dir)
    _copy_layout_dir_if_exists(project_dir, layout.resources_dir, app_dir)
    for resource_dir in layout.additional_resources_dirs:
        _copy_layout_dir_if_exists(project_dir, resource_dir, app_dir)
    if (project_dir / "pyproject.toml").exists():
        shutil.copy2(project_dir / "pyproject.toml", app_dir / "pyproject.toml")
    return app_dir


def _prepare_jvm_docker_context(
    *,
    project_dir: Path,
    context_dir: Path,
    resolver: Callable[[str], str | None],
) -> None:
    app_dir = _prepare_common_docker_app_context(project_dir, context_dir)
    pyronaut_dir = app_dir / "__pyronaut__"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
    if not runtime_manifest.exists():
        raise RuntimeError(f"Missing classpath manifest: {runtime_manifest}. Run pyronaut install first.")
    _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
    _stage_manifest_artifacts(
        source=runtime_manifest,
        target=pyronaut_dir / "resolved-runtime-dependencies",
        project_dir=project_dir,
        pyronaut_dir=pyronaut_dir,
    )
    delegate_executable = resolver(COMMAND_TO_EXECUTABLE["run"])
    if delegate_executable is None:
        raise RuntimeError(f"Missing delegated executable: {COMMAND_TO_EXECUTABLE['run']}")
    _stage_delegate_distribution(delegate_executable, pyronaut_dir / "tools" / "pyronaut-run")


def _prepare_native_docker_context(
    *,
    project_dir: Path,
    context_dir: Path,
    resolver: Callable[[str], str | None],
) -> None:
    app_dir = _prepare_common_docker_app_context(project_dir, context_dir)
    pyronaut_dir = app_dir / "__pyronaut__"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
    if not runtime_manifest.exists():
        raise RuntimeError(f"Missing runtime classpath manifest: {runtime_manifest}. Run pyronaut install first.")
    _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
    _stage_manifest_artifacts(
        source=runtime_manifest,
        target=pyronaut_dir / "resolved-runtime-dependencies",
        project_dir=project_dir,
        pyronaut_dir=pyronaut_dir,
    )
    delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
    if delegate_executable is None:
        raise RuntimeError(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}")
    _stage_delegate_distribution(delegate_executable, pyronaut_dir / "tools" / "pyronaut-native-build")


def _write_jvm_dockerfile(*, target: Path, base_image: str) -> None:
    dockerfile = f"""\
FROM {base_image}
WORKDIR /app
COPY app/ /app/
ENTRYPOINT ["/app/__pyronaut__/tools/pyronaut-run/bin/pyronaut-run", "--project-dir", "/app"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _write_native_dockerfile(
    *,
    target: Path,
    builder_image: str,
    runtime_image: str,
    project_name: str,
    main_class: str,
    verbose: bool,
    static_native: bool,
    passthrough_args: Sequence[str],
) -> None:
    output_binary = f"/workspace/app/__pyronaut__/native/{project_name}"
    build_command = [
        "/workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build",
        "--project-dir",
        "/workspace/app",
        "--main-class",
        main_class,
        "--output",
        output_binary,
    ]
    if verbose:
        build_command.append("--verbose")
    build_command.extend(passthrough_args)
    if static_native:
        build_command.extend(["--static", "--libc=musl"])
    dockerfile = f"""\
FROM {builder_image} AS builder
WORKDIR /workspace
COPY app/ /workspace/app/
RUN chmod +x /workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build
RUN {shlex.join(build_command)}

FROM {runtime_image}
WORKDIR /app
COPY --from=builder {output_binary} /app/{project_name}
ENTRYPOINT ["/app/{project_name}"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _copy_dockerfile_into_context(source: Path, context_dir: Path) -> Path:
    target = context_dir / source.name
    shutil.copy2(source, target)
    return target


def _build_docker_command(
    *,
    dockerfile: Path,
    image_tag: str,
    context_dir: Path,
    verbose: bool,
    build_args: dict[str, str],
) -> list[str]:
    docker_executable = shutil.which("docker") or "docker"
    command = [docker_executable, "build"]
    if verbose:
        command.append("--progress=plain")
    for key, value in build_args.items():
        command.extend(["--build-arg", f"{key}={value}"])
    command.extend(["-t", image_tag, "-f", str(dockerfile), str(context_dir)])
    return command


def _run_docker_build(
    *,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    project_dir: Path,
    project_name: str,
    project_version: str,
    mode: str,
    main_class: str,
    verbose: bool,
    static_native: bool,
) -> int:
    docker_config = _read_pyproject_build_docker_config(project_dir)
    image_name = docker_config.get("image_name") or _default_docker_image_name(project_name)
    image_tag = f"{image_name}:{project_version}"
    if mode == "native":
        image_tag = f"{image_name}:{project_version}-native"

    with tempfile.TemporaryDirectory(prefix=f"pyronaut-build-{mode}-docker-") as context_root:
        context_dir = Path(context_root)
        build_args = {
            "PYRONAUT_PROJECT_NAME": project_name,
            "PYRONAUT_PROJECT_VERSION": project_version,
            "PYRONAUT_BUILD_MODE": mode,
            "PYRONAUT_NATIVE_STATIC": "true" if static_native else "false",
        }
        if mode == "native":
            _prepare_native_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
            builder_image = docker_config.get(
                "static_native_builder_image" if static_native else "native_builder_image"
            ) or (
                _DEFAULT_DOCKER_STATIC_NATIVE_BUILDER_IMAGE if static_native else _DEFAULT_DOCKER_NATIVE_BUILDER_IMAGE
            )
            runtime_image = docker_config.get(
                "static_native_base_image" if static_native else "native_base_image"
            ) or (
                _DEFAULT_DOCKER_STATIC_NATIVE_BASE_IMAGE if static_native else _DEFAULT_DOCKER_NATIVE_BASE_IMAGE
            )
            build_args["PYRONAUT_NATIVE_BUILDER_IMAGE"] = builder_image
            build_args["PYRONAUT_NATIVE_BASE_IMAGE"] = runtime_image
            custom = _resolve_build_dockerfile(
                project_dir=project_dir,
                configured_path=docker_config.get("dockerfile_native"),
                default_name="DockerfileNative",
            )
            if custom is not None:
                dockerfile = _copy_dockerfile_into_context(custom, context_dir)
            else:
                dockerfile = context_dir / "DockerfileNative"
                _write_native_dockerfile(
                    target=dockerfile,
                    builder_image=builder_image,
                    runtime_image=runtime_image,
                    project_name=project_name,
                    main_class=main_class,
                    verbose=verbose,
                    static_native=static_native,
                    passthrough_args=_extract_native_build_passthrough_args(args),
                )
        else:
            _prepare_jvm_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
            build_args["PYRONAUT_JVM_BASE_IMAGE"] = docker_config.get("jvm_base_image") or _DEFAULT_DOCKER_JVM_BASE_IMAGE
            custom = _resolve_build_dockerfile(
                project_dir=project_dir,
                configured_path=docker_config.get("dockerfile"),
                default_name="Dockerfile",
            )
            if custom is not None:
                dockerfile = _copy_dockerfile_into_context(custom, context_dir)
            else:
                dockerfile = context_dir / "Dockerfile"
                _write_jvm_dockerfile(
                    target=dockerfile,
                    base_image=build_args["PYRONAUT_JVM_BASE_IMAGE"],
                )

        command = _build_docker_command(
            dockerfile=dockerfile,
            image_tag=image_tag,
            context_dir=context_dir,
            verbose=verbose,
            build_args=build_args,
        )
        if _delegation_trace_enabled():
            print(shlex.join(command), file=sys.stderr)
        exit_code = runner(command, None)
    if exit_code == SUCCESS:
        print(f"Docker image build complete: {image_tag}")
    return exit_code


def _launcher_package_name(project_name: str) -> str:
    normalized = re.sub(r"[^0-9A-Za-z_]", "_", project_name.strip().replace("-", "_"))
    normalized = re.sub(r"_+", "_", normalized).strip("_")
    if not normalized:
        normalized = "pyronaut_app"
    if normalized[0].isdigit():
        normalized = f"pyronaut_{normalized}"
    return f"{normalized}_launcher"


def _remove_existing_built_wheels(dist_dir: Path, project_name: str) -> None:
    prefix = re.sub(r"[-_.]+", "_", project_name.strip()).strip("_")
    if not prefix:
        return
    for wheel in dist_dir.glob(f"{prefix}-*.whl"):
        with contextlib.suppress(OSError):
            wheel.unlink()


def _jvm_build_launcher_code(main_class: str) -> str:
    return f"""\
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent / "app"
MAIN_CLASS = {main_class!r}


def main() -> None:
    try:
        from pyronaut_cli_v2 import cli as pyronaut_cli
    except Exception as exc:
        raise SystemExit("Pyronaut runtime is required to launch this JVM build: " + str(exc))

    os.chdir(PROJECT_DIR)
    try:
        command_line, env = pyronaut_cli._build_java_delegate_invocation(
            "run",
            ["--project-dir", str(PROJECT_DIR), "--main-class", MAIN_CLASS, *sys.argv[1:]],
            pyronaut_cli._resolve_executable,
            java_home_provider=lambda: pyronaut_cli._ensure_graalvm_java_home(PROJECT_DIR),
        )
    except RuntimeError as exc:
        raise SystemExit(str(exc))

    merged_env = pyronaut_cli._strip_test_resources_java_tool_options(dict(os.environ)) or dict(os.environ)
    if env:
        merged_env.update(env)
    raise SystemExit(subprocess.run(command_line, env=merged_env).returncode)
"""


def _native_build_launcher_code(binary_name: str) -> str:
    return f"""\
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent / "app"
BINARY = PROJECT_DIR / "__pyronaut__" / "native" / {binary_name!r}


def main() -> None:
    if not BINARY.exists():
        raise SystemExit(f"Error: binary '{{BINARY.name}}' not found")
    raise SystemExit(subprocess.run([str(BINARY), *sys.argv[1:]]).returncode)
"""


def _run_lifecycle_validation(
    *,
    project_dir: str,
    scenario: str,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    no_cache: bool = False,
    env_overrides: dict[str, str] | None = None,
) -> int:
    args = ["--project-dir", project_dir, "--scenario", scenario]
    if no_cache:
        args.append("--no-cache")
    effective_env_overrides = env_overrides
    if env_overrides is not None:
        effective_env_overrides = _merge_env_overrides(
            _build_non_test_resources_env("validate-config", None),
            env_overrides,
        )
    return _delegate(
        "validate-config",
        args,
        runner,
        resolver,
        env_overrides=effective_env_overrides,
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


def _read_pyproject_pyronaut_table(project_dir: Path) -> dict[str, object] | None:
    data = _read_pyproject_data(project_dir)
    if not isinstance(data, dict):
        return None

    tool = data.get("tool")
    if not isinstance(tool, dict):
        return None
    pyronaut = tool.get("pyronaut")
    if not isinstance(pyronaut, dict):
        return None
    return pyronaut


def _read_pyproject_string(table: dict[str, object], *keys: str) -> str | None:
    for key in keys:
        value = table.get(key)
        if value is None:
            continue
        if not isinstance(value, str):
            raise ValueError(f"Invalid value for [tool.pyronaut]: expected string for {key}")
        stripped = value.strip()
        if stripped:
            return stripped
    return None


def _read_pyproject_string_list(table: dict[str, object], *keys: str) -> tuple[str, ...]:
    for key in keys:
        value = table.get(key)
        if value is None:
            continue
        if not isinstance(value, list):
            raise ValueError(f"Invalid value for [tool.pyronaut]: expected string array for {key}")
        values: list[str] = []
        for index, item in enumerate(value):
            if not isinstance(item, str):
                raise ValueError(f"Invalid value for [tool.pyronaut]: expected string for {key}[{index}]")
            stripped = item.strip()
            if stripped:
                values.append(stripped)
        return tuple(values)
    return ()


def _read_pyproject_sources(project_dir: Path) -> _ProjectLayout:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return _ProjectLayout()
    sources = pyronaut.get("sources")
    if not isinstance(sources, dict):
        return _ProjectLayout()
    return _ProjectLayout(
        python_source_dir=_read_pyproject_string(sources, "python") or _DEFAULT_PYTHON_SOURCE_DIR,
        python_test_dir=_read_pyproject_string(sources, "python-test", "pythonTest") or _DEFAULT_PYTHON_TEST_DIR,
        java_source_dir=_read_pyproject_string(sources, "java") or _DEFAULT_JAVA_SOURCE_DIR,
        java_test_dir=_read_pyproject_string(sources, "java-test", "javaTest") or _DEFAULT_JAVA_TEST_DIR,
        resources_dir=_read_pyproject_string(sources, "resources") or _DEFAULT_RESOURCES_DIR,
        test_resources_dir=_read_pyproject_string(sources, "test-resources", "testResources") or _DEFAULT_TEST_RESOURCES_DIR,
        additional_resources_dirs=_read_pyproject_string_list(sources, "additional-resources", "additionalResources"),
        additional_test_resources_dirs=_read_pyproject_string_list(sources, "additional-test-resources", "additionalTestResources"),
    )


def _resolve_layout_dir(project_dir: Path, configured_dir: str) -> Path:
    path = Path(configured_dir)
    return path.resolve() if path.is_absolute() else (project_dir / path).resolve()


def _copy_layout_dir_if_exists(project_dir: Path, configured_dir: str, app_dir: Path) -> None:
    source_dir = _resolve_layout_dir(project_dir, configured_dir)
    target_dir = app_dir / Path(configured_dir)
    _copytree_if_exists(source_dir, target_dir)


def _normalize_toolchain_distribution(value: str) -> str:
    normalized = value.strip().lower()
    aliases = {
        "ce": "ce",
        "community": "ce",
        "graalce": "ce",
        "ee": "ee",
        "oracle": "ee",
        "graal": "ee",
        "dev": "dev",
    }
    resolved = aliases.get(normalized)
    if resolved is None:
        raise ValueError("Invalid toolchain distribution in pyproject.toml. Use tool.pyronaut.toolchain.distribution = 'ce', 'ee', or 'dev'")
    return resolved


def _read_pyproject_toolchain_spec(project_dir: Path | None) -> _ToolchainSpec:
    if project_dir is None:
        return _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)
    toolchain = pyronaut.get("toolchain")
    if not isinstance(toolchain, dict):
        return _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)

    distribution_raw = toolchain.get("distribution")
    distribution = None
    if distribution_raw is not None:
        if not isinstance(distribution_raw, str):
            raise ValueError("Invalid toolchain distribution in pyproject.toml. Use tool.pyronaut.toolchain.distribution = 'ce', 'ee', or 'dev'")
        distribution = _normalize_toolchain_distribution(distribution_raw)

    version = _read_pyproject_string(toolchain, "version")
    release_tag = _read_pyproject_string(toolchain, "release-tag", "releaseTag")
    download_url = _read_pyproject_string(toolchain, "download-url", "downloadUrl")

    java_version_raw = toolchain.get("java-version", toolchain.get("javaVersion"))
    java_version = _GRAALVM_MIN_JDK_MAJOR
    if java_version_raw is not None:
        if isinstance(java_version_raw, bool) or not isinstance(java_version_raw, int):
            raise ValueError("Invalid toolchain java version in pyproject.toml. Use tool.pyronaut.toolchain.java-version = 25")
        java_version = int(java_version_raw)

    explicit = any(
        key in toolchain
        for key in ("distribution", "version", "java-version", "javaVersion", "release-tag", "releaseTag", "download-url", "downloadUrl")
    )
    if explicit and distribution is None:
        distribution = _DEFAULT_GRAALVM_DOWNLOAD_DISTRIBUTION
    return _ToolchainSpec(distribution, version, java_version, release_tag, download_url, explicit)


def _read_pyproject_build_mode(project_dir: Path) -> str | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
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


def _read_pyproject_processor_mode(project_dir: Path) -> str:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return "jit"
    processor = pyronaut.get("processor")
    if not isinstance(processor, dict):
        return "jit"
    mode = processor.get("mode")
    if not isinstance(mode, str):
        return "jit"
    normalized = mode.strip().lower()
    if normalized in {"jit", "native"}:
        return normalized
    raise ValueError("Invalid processor mode in pyproject.toml. Use tool.pyronaut.processor.mode = 'jit' or 'native'")


def _read_pyproject_test_mode(project_dir: Path) -> str:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return "jit"
    test = pyronaut.get("test")
    if not isinstance(test, dict):
        return "jit"
    mode = test.get("mode")
    if not isinstance(mode, str):
        return "jit"
    normalized = mode.strip().lower()
    if normalized in {"jit", "native"}:
        return normalized
    raise ValueError("Invalid test mode in pyproject.toml. Use tool.pyronaut.test.mode = 'jit' or 'native'")


def _read_pyproject_test_resources_table(project_dir: Path) -> dict[str, object] | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return None
    test_resources = pyronaut.get("test-resources")
    if not isinstance(test_resources, dict):
        test_resources = pyronaut.get("testResources")
    if not isinstance(test_resources, dict):
        return None
    return test_resources


def _read_pyproject_test_resources_enabled(project_dir: Path) -> bool:
    test_resources = _read_pyproject_test_resources_table(project_dir)
    if not isinstance(test_resources, dict):
        return False
    enabled = test_resources.get("enabled")
    return isinstance(enabled, bool) and enabled


def _read_pyproject_test_resources_shared(project_dir: Path) -> bool:
    test_resources = _read_pyproject_test_resources_table(project_dir)
    if not isinstance(test_resources, dict):
        return False
    shared_server = test_resources.get("sharedServer")
    return isinstance(shared_server, bool) and shared_server


def _resolve_test_resources_logs_dir(project_dir: Path, settings_file: Path) -> Path:
    test_resources = _read_pyproject_test_resources_table(project_dir)
    if isinstance(test_resources, dict):
        configured = test_resources.get("logsDir")
        if isinstance(configured, str):
            stripped = configured.strip()
            if stripped:
                configured_path = Path(stripped)
                return (configured_path if configured_path.is_absolute() else project_dir / configured_path).resolve()
    return (settings_file.parent / "logs").resolve()


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

    env: dict[str, str] = {}
    for env_name, property_name in _TEST_RESOURCES_ENV_TO_PROPERTY.items():
        settings_key = property_name.removeprefix("micronaut.test.resources.")
        value = settings.get(settings_key)
        if value:
            env[env_name] = value

    if not env:
        return None
    return env


def _merge_env_overrides(base_env: dict[str, str] | None, env_overrides: dict[str, str] | None) -> dict[str, str] | None:
    if not env_overrides:
        return base_env
    merged = dict(base_env or {})
    merged.update(env_overrides)
    return merged


def _apply_project_virtualenv(env: dict[str, str] | None, project_dir: Path) -> dict[str, str] | None:
    venv_dir = project_dir / ".venv"
    if not venv_dir.is_dir():
        return env

    activated = dict(os.environ if env is None else env)
    venv_bin = venv_dir / "bin"
    activated["VIRTUAL_ENV"] = str(venv_dir)
    activated.pop("PYTHONHOME", None)
    if venv_bin.is_dir():
        activated["PATH"] = _prepend_path_entry(activated.get("PATH", ""), str(venv_bin))
        venv_python = _resolve_virtualenv_python(venv_bin)
        if venv_python is not None:
            activated["PYRONAUT_PYTHON_EXECUTABLE"] = str(venv_python)
    return activated


def _prepend_path_entry(path_value: str, entry: str) -> str:
    entries = [value for value in path_value.split(os.pathsep) if value]
    entries = [value for value in entries if value != entry]
    return os.pathsep.join([entry, *entries])


def _resolve_virtualenv_python(venv_bin: Path) -> Path | None:
    for name in ("python", "python3"):
        candidate = venv_bin / name
        if candidate.exists():
            return candidate
    return None


def _strip_test_resources_java_tool_options(env: dict[str, str] | None) -> dict[str, str] | None:
    if env is None:
        return None

    sanitized = dict(env)
    java_tool_options = sanitized.get("JAVA_TOOL_OPTIONS")
    if java_tool_options is None:
        return sanitized

    filtered_tokens = [
        token
        for token in shlex.split(java_tool_options)
        if not token.startswith("-Dmicronaut.test.resources.")
    ]
    if filtered_tokens:
        sanitized["JAVA_TOOL_OPTIONS"] = " ".join(filtered_tokens)
    else:
        sanitized.pop("JAVA_TOOL_OPTIONS", None)
    for env_name in _TEST_RESOURCES_ENV_TO_PROPERTY:
        sanitized.pop(env_name, None)
    return sanitized


def _build_non_test_resources_env(command: str, java_home_provider: JavaHomeProvider | None) -> dict[str, str] | None:
    return _strip_test_resources_java_tool_options(_build_java_home_env(command, java_home_provider))


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
        if token in {"--native", "--jvm", "--verbose", "--no-cache", "--no-validate", "--docker", "--static"}:
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
            or token.startswith("--no-cache=")
            or token.startswith("--docker=")
            or token.startswith("--static=")
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
    local_repository: str | None,
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
            preflight_code = _run_preflight(str(project_root), no_cache, local_repository, execute, resolver)
            if preflight_code != SUCCESS:
                return preflight_code

        try:
            command_line, env = _build_java_delegate_invocation(
                "run",
                run_args,
                resolver,
                debug_vm=debug_vm,
                env_overrides=env_overrides,
                java_home_provider=java_home_provider,
            )
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED

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
                        refresh_code = _run_preflight(str(project_root), no_cache, local_repository, execute, resolver)
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
    layout = _read_pyproject_sources(project_dir)
    watched_roots = (
        layout.python_source_dir,
        layout.python_test_dir,
        layout.java_source_dir,
        layout.java_test_dir,
        layout.resources_dir,
        layout.test_resources_dir,
        *layout.additional_resources_dirs,
        *layout.additional_test_resources_dirs,
    )
    ignored_dirs = {"__pyronaut__", ".pytest_cache", "build", ".gradle", "__pycache__", ".git"}
    entries: list[tuple[str, int, int]] = []
    seen_roots: set[Path] = set()

    for root_name in watched_roots:
        root = _resolve_layout_dir(project_dir, root_name)
        if root in seen_roots:
            continue
        seen_roots.add(root)
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
                try:
                    relative = file_path.relative_to(project_dir).as_posix()
                except ValueError:
                    relative = file_path.as_posix()
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
    project_dir: Path,
) -> JavaHomeProvider | None:
    return lambda: _ensure_graalvm_java_home(project_dir)


def _build_java_home_env(command: str, java_home_provider: JavaHomeProvider | None) -> dict[str, str] | None:
    if command not in {"run", "test", "build", "tui", "validate-config", "install", "process"}:
        return None
    env = dict(os.environ)
    if java_home_provider is None:
        return env

    java_home = java_home_provider()
    if java_home is None or not java_home.strip():
        raise RuntimeError("Unable to locate or provision compatible GraalVM JDK (requires JDK 25+)")

    env["JAVA_HOME"] = java_home
    env.setdefault("PYRONAUT_PYTHON_EXECUTABLE", sys.executable)
    site_packages = sysconfig.get_paths().get("purelib")
    if site_packages:
        env.setdefault("PYRONAUT_PYTHON_SITE_PACKAGES", site_packages)
    java_bin = str(Path(java_home) / "bin")
    path_value = env.get("PATH", "")
    if path_value:
        if not path_value.startswith(java_bin + os.pathsep):
            env["PATH"] = java_bin + os.pathsep + path_value
    else:
        env["PATH"] = java_bin
    return env


def _build_delegate_jvm_args(debug_vm: bool) -> list[str]:
    jvm_args = list(_DELEGATE_JVM_FLAGS)
    if debug_vm:
        jvm_args.append(_JDWP_FLAGS)
    return jvm_args


def _build_test_resources_jvm_args(env_overrides: dict[str, str] | None) -> list[str]:
    if not env_overrides:
        return []
    jvm_args: list[str] = []
    for env_name, property_name in _TEST_RESOURCES_ENV_TO_PROPERTY.items():
        value = env_overrides.get(env_name)
        if value is None:
            continue
        stripped = value.strip()
        if not stripped:
            continue
        jvm_args.append(f"-D{property_name}={stripped}")
    return jvm_args


def _ensure_graalvm_java_home(project_dir: Path | None = None) -> str | None:
    global _provisioned_graalvm_home
    toolchain = _read_pyproject_toolchain_spec(project_dir)

    if _provisioned_graalvm_home is not None and _matches_requested_graalvm_home(Path(_provisioned_graalvm_home), toolchain):
        return _provisioned_graalvm_home

    env_java_home = _read_env("JAVA_HOME")
    if env_java_home and _matches_requested_graalvm_home(Path(env_java_home), toolchain):
        _provisioned_graalvm_home = env_java_home
        return env_java_home

    pyronaut_jdks = _graalvm_jdks_root()
    pyronaut_jdks.mkdir(parents=True, exist_ok=True)

    cached = _find_compatible_cached_jdk(pyronaut_jdks, toolchain)
    if cached is not None:
        _provisioned_graalvm_home = str(cached)
        return _provisioned_graalvm_home

    legacy_jdks = _legacy_graalvm_jdks_root()
    if legacy_jdks.exists():
        legacy = _find_compatible_cached_jdk(legacy_jdks, toolchain)
        if legacy is not None:
            _provisioned_graalvm_home = str(legacy)
            return _provisioned_graalvm_home

    sdkman_home = _install_with_sdkman(pyronaut_jdks, toolchain)
    if sdkman_home is not None:
        _provisioned_graalvm_home = str(sdkman_home)
        return _provisioned_graalvm_home

    jenv_home = _find_with_jenv(toolchain)
    if jenv_home is not None:
        _provisioned_graalvm_home = str(jenv_home)
        return _provisioned_graalvm_home

    gradle_home = _find_with_gradle_jdks(toolchain)
    if gradle_home is not None:
        _provisioned_graalvm_home = str(gradle_home)
        return _provisioned_graalvm_home

    downloaded = _download_and_install_graalvm(pyronaut_jdks, toolchain)
    if downloaded is not None:
        _provisioned_graalvm_home = str(downloaded)
        return _provisioned_graalvm_home
    return None


def _graalvm_jdks_root() -> Path:
    return Path.home() / ".pyronaut" / "sdks"


def _legacy_graalvm_jdks_root() -> Path:
    return Path.home() / ".pyronaut" / "jdks"


def _find_compatible_cached_jdk(jdks_root: Path, toolchain: _ToolchainSpec | None = None) -> Path | None:
    spec = toolchain or _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)
    return _find_matching_home(jdks_root, spec)


def _find_matching_home(root: Path, toolchain: _ToolchainSpec) -> Path | None:
    if not root.exists():
        return None
    for child in sorted(root.iterdir(), key=lambda path: path.name):
        if not child.is_dir():
            continue
        home = _normalize_extracted_home(child)
        if home is not None and _matches_requested_graalvm_home(home, toolchain):
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
    metadata = _read_graalvm_metadata(java_home)
    return metadata is not None and metadata.java_version is not None and metadata.java_version >= _GRAALVM_MIN_JDK_MAJOR


def _matches_requested_graalvm_home(java_home: Path, toolchain: _ToolchainSpec) -> bool:
    metadata = _read_graalvm_metadata(java_home)
    if metadata is None or metadata.java_version is None or metadata.java_version < toolchain.java_version:
        return False
    if not toolchain.explicit:
        return True
    if toolchain.distribution is not None and metadata.distribution != toolchain.distribution:
        return False
    if toolchain.version is not None and metadata.version != toolchain.version:
        return False
    return True


def _read_graalvm_metadata(java_home: Path) -> _GraalVmMetadata | None:
    java_bin = java_home / "bin" / "java"
    if not java_bin.exists():
        return None
    try:
        completed = subprocess.run(
            [str(java_bin), "-version"],
            check=False,
            capture_output=True,
            text=True,
        )
    except Exception:
        _warn_if_quarantined_graalvm(java_home)
        return None

    if completed.returncode != 0:
        _warn_if_quarantined_graalvm(java_home)
        return None
    output = (completed.stdout or "") + "\n" + (completed.stderr or "")
    lower = output.lower()
    if "graalvm" not in lower:
        return None
    version = _extract_java_version_string(output)
    major = _parse_java_major_version(output)
    distribution = _detect_graalvm_distribution(output, java_home, version)
    return _GraalVmMetadata(version, major, distribution)


def _warn_if_quarantined_graalvm(java_home: Path) -> None:
    if platform.system().lower() != "darwin":
        return
    xattr = shutil.which("xattr")
    if xattr is None:
        return
    targets = [java_home, java_home.parent.parent if java_home.name == "Home" and java_home.parent.name == "Contents" else java_home]
    for target in targets:
        try:
            result = subprocess.run(
                [xattr, "-p", "com.apple.quarantine", str(target)],
                check=False,
                capture_output=True,
                text=True,
            )
        except Exception:
            return
        if result.returncode == 0:
            sys.stderr.write(
                "Detected a macOS-quarantined GraalVM toolchain. "
                f"Run `sudo xattr -r -d com.apple.quarantine {shlex.quote(str(target))}` and try again.\n"
            )
            return


def _extract_java_version_string(version_output: str) -> str | None:
    for line in version_output.splitlines():
        if "version" not in line:
            continue
        quote_index = line.find('"')
        if quote_index < 0:
            continue
        end_quote = line.find('"', quote_index + 1)
        if end_quote < 0:
            continue
        return line[quote_index + 1:end_quote]
    return None


def _detect_graalvm_distribution(version_output: str, java_home: Path, version: str | None) -> str | None:
    lowered = (version_output + "\n" + str(java_home)).lower()
    if version is not None and "dev" in version.lower():
        return "dev"
    if "oracle graalvm" in lowered or "graalvm-jdk" in lowered:
        return "ee"
    if "graalvm community" in lowered or "graalvm ce" in lowered or "graalce" in lowered or "graalvm-community" in lowered:
        return "ce"
    if "dev" in lowered:
        return "dev"
    if "graalvm" in lowered:
        return "ce"
    return None


def _parse_java_major_version(version_output: str) -> int | None:
    raw_version = _extract_java_version_string(version_output)
    if raw_version is None:
        return None
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


def _install_with_sdkman(jdks_root: Path, toolchain: _ToolchainSpec | None = None) -> Path | None:
    sdkman_dir = _read_env("SDKMAN_DIR") or str(Path.home() / ".sdkman")
    candidates_root = Path(sdkman_dir) / "candidates" / "java"
    if not candidates_root.exists():
        return None
    spec = toolchain or _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)
    return _find_matching_home(candidates_root, spec)


def _find_with_jenv(toolchain: _ToolchainSpec) -> Path | None:
    return _find_matching_home(Path.home() / ".jenv" / "versions", toolchain)


def _find_with_gradle_jdks(toolchain: _ToolchainSpec) -> Path | None:
    return _find_matching_home(Path.home() / ".gradle" / "jdks", toolchain)


def _download_and_install_graalvm(jdks_root: Path, toolchain: _ToolchainSpec | None = None) -> Path | None:
    spec = toolchain or _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)
    archive_url = _resolve_graalvm_archive_url(spec)
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
            if not _matches_requested_graalvm_home(home, spec):
                continue
            destination = jdks_root / home.parent.name if (home.parent / "bin" / "java").exists() and home.name == "Home" else jdks_root / home.name
            if destination.exists():
                normalized_existing = _normalize_extracted_home(destination) or destination
                if _matches_requested_graalvm_home(normalized_existing, spec):
                    return normalized_existing
                shutil.rmtree(destination, ignore_errors=True)

            source_root = home.parent if home.name == "Home" and home.parent.name == "Contents" else home
            shutil.copytree(source_root, destination, dirs_exist_ok=True)
            normalized_destination = _normalize_extracted_home(destination) or destination
            if _matches_requested_graalvm_home(normalized_destination, spec):
                _warn_if_quarantined_graalvm(normalized_destination)
                return normalized_destination
    return None


def _resolve_graalvm_archive_url(toolchain: _ToolchainSpec) -> str | None:
    if toolchain.download_url is not None:
        return toolchain.download_url

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

    distribution = toolchain.distribution or _DEFAULT_GRAALVM_DOWNLOAD_DISTRIBUTION
    version = toolchain.version or (_DEFAULT_GRAALVM_DOWNLOAD_VERSION if not toolchain.explicit else None)
    if distribution == "dev":
        return _resolve_dev_build_archive_url(os_segment, arch, ext, toolchain)
    if version is None:
        return None
    if distribution == "ce":
        base = f"graalvm-community-jdk-{version}_{os_segment}-{arch}_bin.{ext}"
        return f"https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-{version}/{base}"
    if distribution == "ee":
        base = f"graalvm-jdk-{version}_{os_segment}-{arch}_bin.{ext}"
        return f"https://download.oracle.com/graalvm/{version}/latest/{base}"
    return None


def _resolve_dev_build_archive_url(os_segment: str, arch: str, ext: str, toolchain: _ToolchainSpec) -> str | None:
    release_tag = toolchain.release_tag
    if release_tag is None:
        return None
    repository = _dev_build_repository(release_tag)
    asset_name = _dev_build_asset_name(release_tag, os_segment, arch, ext)
    if asset_name is None:
        return None
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/releases/tags/{urllib.parse.quote(_dev_build_release_tag(release_tag))}",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "pyronaut-cli-v2",
        },
    )
    try:
        with urllib.request.urlopen(request) as response:
            payload = json.load(response)
    except Exception:
        return None
    assets = payload.get("assets")
    if not isinstance(assets, list):
        return None
    for asset in assets:
        if not isinstance(asset, dict):
            continue
        name = asset.get("name")
        download_url = asset.get("browser_download_url")
        if name == asset_name and isinstance(download_url, str):
            return download_url
    return None


def _is_oracle_graalvm_ea_tag(release_tag: str) -> bool:
    return re.fullmatch(r"(?:jdk-)?\d+e\d+-.+", release_tag.strip()) is not None


def _dev_build_repository(release_tag: str) -> str:
    if _is_oracle_graalvm_ea_tag(release_tag):
        return _ORACLE_GRAALVM_EA_BUILDS_REPOSITORY
    return _GRAALVM_CE_DEV_BUILDS_REPOSITORY


def _dev_build_release_tag(release_tag: str) -> str:
    tag = release_tag.strip()
    if _is_oracle_graalvm_ea_tag(tag) and not tag.startswith("jdk-"):
        return f"jdk-{tag}"
    if not _is_oracle_graalvm_ea_tag(tag) and tag.startswith("jdk-"):
        return tag[4:]
    return tag


def _dev_build_asset_name(release_tag: str, os_segment: str, arch: str, ext: str) -> str | None:
    tag = _dev_build_release_tag(release_tag)
    if _is_oracle_graalvm_ea_tag(tag):
        release_version = tag[4:] if tag.startswith("jdk-") else tag
        return f"graalvm-jdk-{release_version}_{os_segment}-{arch}_bin.{ext}"

    ce_os_segment = "darwin" if os_segment == "macos" else os_segment
    ce_arch = "amd64" if arch == "x64" else arch
    if os_segment == "macos" and ce_arch == "amd64":
        return None
    return f"graalvm-community-dev-{ce_os_segment}-{ce_arch}.{ext}"


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


def _extract_local_repository(args: Sequence[str]) -> str | None:
    for index, token in enumerate(args):
        if token in {"--local-repository", "--local-repo"}:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {token}")
            return args[index + 1]
        for prefix in ("--local-repository=", "--local-repo="):
            if token.startswith(prefix):
                return token.split("=", 1)[1]
    return None


def _strip_local_repository_args(args: Sequence[str]) -> list[str]:
    filtered: list[str] = []
    skip_next = False
    for token in args:
        if skip_next:
            skip_next = False
            continue
        if token in {"--local-repository", "--local-repo"}:
            skip_next = True
            continue
        if token.startswith("--local-repository=") or token.startswith("--local-repo="):
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


def _run_subprocess_quiet(command_line: list[str], env: dict[str, str] | None = None) -> int:
    try:
        completed = subprocess.run(
            command_line,
            check=False,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
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


def _bundled_native_executable(command_name: str) -> Path | None:
    if sys.platform.startswith("linux") or sys.platform == "darwin":
        package_root = Path(__file__).resolve().parent
        return package_root / "tools" / command_name / "native" / command_name
    return None


def _resolve_native_preferred_executable(
    command_name: str,
    resolver: Callable[[str], str | None],
    *,
    fallback_to_resolver: bool = True,
) -> str | None:
    env_key = command_name.upper().replace("-", "_") + "_NATIVE_EXECUTABLE"
    override = _read_env(env_key)
    if override:
        return override

    bundled = _bundled_native_executable(command_name)
    if bundled is not None and bundled.exists():
        return str(bundled)

    if fallback_to_resolver:
        return resolver(command_name)
    return None


def _resolve_delegate_executable_path(
    command: str,
    args: Sequence[str],
    resolver: Callable[[str], str | None],
) -> str:
    executable_name = COMMAND_TO_EXECUTABLE[command]
    project_dir = Path(_extract_project_dir(args)).resolve()

    if command in {"install", "validate-config"}:
        executable_path = _resolve_native_preferred_executable(executable_name, resolver)
        if executable_path is None:
            raise RuntimeError(f"Missing delegated executable: {executable_name}")
        return executable_path

    if command == "process":
        try:
            processor_mode = _read_pyproject_processor_mode(project_dir)
        except ValueError as exc:
            raise RuntimeError(str(exc)) from exc
        if processor_mode == "native":
            executable_path = _resolve_native_preferred_executable(
                executable_name,
                resolver,
                fallback_to_resolver=False,
            )
            if executable_path is None:
                raise RuntimeError(
                    "Missing native delegated executable for pyronaut-processor. "
                    "Build or install a native pyronaut-processor, or set tool.pyronaut.processor.mode = 'jit'."
                )
            return executable_path
        executable_path = resolver(executable_name)
        if executable_path is None:
            raise RuntimeError(f"Missing delegated executable: {executable_name}")
        return executable_path

    if command == "test":
        try:
            test_mode = _read_pyproject_test_mode(project_dir)
        except ValueError as exc:
            raise RuntimeError(str(exc)) from exc
        if _extract_debug_vm(args):
            test_mode = "jit"
        if test_mode == "native":
            executable_path = _resolve_native_preferred_executable(
                executable_name,
                resolver,
                fallback_to_resolver=False,
            )
            if executable_path is None:
                raise RuntimeError(
                    "Missing native delegated executable for pyronaut-test. "
                    "Build or install a native pyronaut-test, or set tool.pyronaut.test.mode = 'jit'."
                )
            return executable_path

    executable_path = resolver(executable_name)
    if executable_path is None:
        raise RuntimeError(f"Missing delegated executable: {executable_name}")
    return executable_path


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


def _test_resources_enabled(project_dir: Path) -> bool:
    return not _test_resources_disabled() and _read_pyproject_test_resources_enabled(project_dir)


def _is_supported_platform(platform_name: str) -> bool:
    return platform_name.startswith("linux") or platform_name == "darwin"


def _print_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut [--version] [--tui [--smoke|--non-interactive]] <install|process|run|test|build|create|validate-config|test-resources-server> [args...]\n")


def _print_build_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut build [--project-dir <dir>] [--native|--jvm|--mode=<native|jvm>] [--docker] [--static] [--main-class <fqcn>] [--verbose] [--no-cache] [--no-validate]\n"
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
    def __init__(self, *, project_dir: Path, owner_command: str, quiet: bool = False) -> None:
        self._project_dir = project_dir
        self._session_file = project_dir / "__pyronaut__" / "test-resources-session.json"
        self._settings_file = project_dir / ".micronaut" / "test-resources" / "test-resources.properties"
        self._owner_pid = os.getpid()
        self._owner_command = owner_command
        self._owner_token = self._new_owner_token()
        self._shared_server = _read_pyproject_test_resources_shared(project_dir)
        self._quiet = quiet
        self._started = False
        self._shutdown_registered = False
        self._client_env_overrides: dict[str, str] | None = None
        self._log_mirror_stop: threading.Event | None = None
        self._log_mirror_thread: threading.Thread | None = None

    @staticmethod
    def _new_owner_token() -> str:
        return f"{os.getpid()}-{int(time.time() * 1000)}"

    def ensure_started(self, *, runner: RunnerWithEnv, resolver: Callable[[str], str | None]) -> None:
        cache_dir = self._project_dir / "__pyronaut__"
        cache_dir.mkdir(parents=True, exist_ok=True)

        if self._should_attach_to_external_server():
            self._emit_status("[test-resources] attach external server")
            self._started = True
            self._client_env_overrides = _test_resources_client_env_from_settings(self._settings_file)
            return

        self._remove_session_file()

        self._emit_status("[test-resources] start owned server")
        exit_code = self._delegate_test_resources_server(
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
        if exit_code != SUCCESS:
            raise RuntimeError(f"Test resources server failed to start (exit code {exit_code})")
        self._report_started_server()
        self._start_log_mirror()

        if self._shared_server:
            self._emit_status("[test-resources] shared-server mode: will not stop server on session exit")
        else:
            self._persist_session(started_at=time.time())
        self._started = True
        self._client_env_overrides = _test_resources_client_env_from_settings(self._settings_file)
        self._register_shutdown(runner=runner, resolver=resolver)

    def stop_if_owned(self, *, runner: RunnerWithEnv, resolver: Callable[[str], str | None]) -> None:
        self._stop_log_mirror()
        if not self._started:
            return

        if self._shared_server:
            self._emit_status("[test-resources] stop skipped (shared server mode)")
            self._remove_session_file()
            return

        if not self._session_matches_owner():
            self._emit_status("[test-resources] stop skipped (ownership mismatch)")
            return

        self._emit_status("[test-resources] stop owned server")
        exit_code = self._delegate_test_resources_server(
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
        if exit_code == SUCCESS:
            self._remove_session_file()
        else:
            self._emit_status(f"[test-resources] stop failed (exit code {exit_code}); preserving session state for retry")

    def client_env_overrides(self) -> dict[str, str] | None:
        if self._client_env_overrides is None:
            return None
        return dict(self._client_env_overrides)

    def _report_started_server(self) -> None:
        settings = _parse_properties_file(self._settings_file)
        server_uri = settings.get("server.uri", "").strip()
        logs_dir = _resolve_test_resources_logs_dir(self._project_dir, self._settings_file)
        logs_hint = f"; logs: {logs_dir}"
        if not server_uri:
            return
        try:
            parsed = urllib.parse.urlparse(server_uri)
        except Exception:
            self._emit_status(f"[test-resources] server running: {server_uri}{logs_hint}")
            return
        port = parsed.port
        if port is None:
            port = 443 if parsed.scheme == "https" else 80
        self._emit_status(f"[test-resources] server running on port {port} ({server_uri}){logs_hint}")

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

    def _start_log_mirror(self) -> None:
        logs_dir = _resolve_test_resources_logs_dir(self._project_dir, self._settings_file)
        log_files = [
            logs_dir / _TEST_RESOURCES_LOG_FILE,
            logs_dir / _TEST_RESOURCES_STDIO_LOG_FILE,
        ]
        initial_positions: dict[Path, int] = {}
        for candidate in log_files:
            try:
                initial_positions[candidate] = candidate.stat().st_size
            except OSError:
                initial_positions[candidate] = 0
        if self._log_mirror_thread is not None and self._log_mirror_thread.is_alive():
            return
        stop_event = threading.Event()
        self._log_mirror_stop = stop_event

        def _tail() -> None:
            position = 0
            active_log_file: Path | None = None

            def _resolve_active_log_file() -> Path | None:
                for candidate in log_files:
                    if candidate.exists():
                        return candidate
                return None

            while not stop_event.is_set() and active_log_file is None:
                active_log_file = _resolve_active_log_file()
                stop_event.wait(0.1)
            if active_log_file is None:
                return
            position = initial_positions.get(active_log_file, 0)
            while not stop_event.is_set():
                try:
                    candidate = _resolve_active_log_file()
                    if candidate is None:
                        stop_event.wait(0.1)
                        continue
                    if active_log_file != candidate:
                        active_log_file = candidate
                        position = initial_positions.get(active_log_file, 0)
                    current_size = active_log_file.stat().st_size
                    if current_size < position:
                        position = 0
                    if current_size > position:
                        with active_log_file.open("r", encoding="utf-8") as handle:
                            handle.seek(position)
                            chunk = handle.read()
                            position = handle.tell()
                        for line in chunk.splitlines():
                            stripped = line.strip()
                            if stripped and _should_mirror_test_resources_log_line(stripped):
                                self._emit_status(stripped)
                    stop_event.wait(0.1)
                except OSError:
                    stop_event.wait(0.1)

        self._log_mirror_thread = threading.Thread(
            target=_tail,
            name="pyronaut-test-resources-log-mirror",
            daemon=True,
        )
        self._log_mirror_thread.start()

    def _stop_log_mirror(self) -> None:
        stop_event = self._log_mirror_stop
        thread = self._log_mirror_thread
        self._log_mirror_stop = None
        self._log_mirror_thread = None
        if stop_event is None:
            return
        stop_event.set()
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)

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

    def _emit_status(self, line: str) -> None:
        if self._quiet:
            return
        sys.stderr.write(line + "\n")
        sys.stderr.flush()

    def _should_attach_to_external_server(self) -> bool:
        if self._shared_server:
            return False
        if self._session_file.exists():
            return False
        if not self._settings_file.exists():
            return False
        env = _test_resources_client_env_from_settings(self._settings_file)
        if env is None:
            return False
        if _test_resources_server_available(self._settings_file):
            return True
        self._emit_status("[test-resources] stale external settings detected; starting owned server instead")
        return False

    def _delegate_test_resources_server(
        self,
        args: list[str],
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
    ) -> int:
        executable_path = _resolve_native_preferred_executable(
            COMMAND_TO_EXECUTABLE["test-resources-server"],
            resolver,
        )
        if executable_path is None:
            raise RuntimeError("Missing delegated executable: pyronaut-test-resources-server")
        command_line = [executable_path, *args]
        if _delegation_trace_enabled():
            print(shlex.join(command_line), file=sys.stderr)
        removed_server_port = os.environ.pop("MICRONAUT_SERVER_PORT", None)
        removed_server_host = os.environ.pop("MICRONAUT_SERVER_HOST", None)
        try:
            effective_runner = _run_subprocess_quiet if self._quiet and runner is _run_subprocess else runner
            return int(effective_runner(command_line, None))
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


def _test_resources_server_available(settings_path: Path) -> bool:
    settings = _parse_properties_file(settings_path)
    server_uri = settings.get("server.uri")
    if not server_uri:
        return False
    try:
        parsed = urllib.parse.urlparse(server_uri)
    except Exception:
        return False
    host = parsed.hostname
    port = parsed.port
    if host is None:
        return False
    if port is None:
        port = 443 if parsed.scheme == "https" else 80
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def _should_mirror_test_resources_log_line(line: str) -> bool:
    return (
        " ERROR " in line
        or _TEST_RESOURCES_IMAGE_PULL_MARKER in line
        or _TEST_RESOURCES_CONTAINER_CREATE_MARKER in line
        or _TEST_RESOURCES_CONTAINER_STARTED_MARKER in line
    )


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
    base_env = _apply_project_virtualenv(base_env, project_dir)

    tr_session: _OwnedTestResourcesSession | None = None
    test_resources_env_overrides: dict[str, str] | None = None
    try:
        if initial_mode in {"run", "test"} and _test_resources_enabled(project_dir):
            tr_session = _OwnedTestResourcesSession(
                project_dir=project_dir.resolve(),
                owner_command=shlex.join(["pyronaut", "--tui", f"--{initial_mode}", "--project-dir", str(project_dir)]),
                quiet=True,
            )
            tr_session.ensure_started(runner=runner, resolver=resolver)
            test_resources_env_overrides = tr_session.client_env_overrides()
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED

    delegated = {
        "validate-config": _resolve_delegate_executable_path("validate-config", ["--project-dir", str(project_dir)], resolver),
        "install": _resolve_delegate_executable_path("install", ["--project-dir", str(project_dir)], resolver),
        "process": _resolve_delegate_executable_path("process", ["--project-dir", str(project_dir)], resolver),
        "run": resolver("pyronaut-run"),
        "test": _resolve_delegate_executable_path("test", ["--project-dir", str(project_dir)], resolver),
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
        "--validate-executable",
        str(delegated["validate-config"]),
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
