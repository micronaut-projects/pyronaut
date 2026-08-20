from __future__ import annotations

import atexit
import contextlib
import fnmatch
from functools import lru_cache
import hashlib
import importlib.metadata
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
import zipfile
import xml.etree.ElementTree as ET
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, Iterable, NamedTuple, Protocol, Sequence

SUCCESS = 0
USAGE_ERROR = 2
PRECONDITION_FAILED = 8
PLATFORM_UNSUPPORTED = 9
INTERNAL_ERROR = 10

SUPPORTED_COMMANDS = {"install", "process", "dev", "run", "test", "build", "validate-config", "test-resources-server"}
LOCAL_REPOSITORY_ENV = "PYRONAUT_LOCAL_REPOSITORY"
COMMAND_TO_EXECUTABLE = {
    "install": "pyronaut-install",
    "process": "pyronaut-processor",
    "dev": "pyronaut-dev",
    "run": "pyronaut-run",
    "test": "pyronaut-test",
    "validate-config": "pyronaut-validate-config",
    "test-resources-server": "pyronaut-test-resources-server",
}
DEV_NATIVE_EXECUTABLE = "pyronaut-dev"
# Configuration validation remains a JVM delegate. Unlike the development,
# processor, and runtime commands, it needs the complete launcher classpath
# (including Jackson's JsonMapper implementation) and is not part of the
# downloadable native-image set.
DEV_NATIVE_COMMANDS = {"install", "process", "run", "test"}
TOOLCHAIN_TYPE_JVM = "jvm"
TOOLCHAIN_TYPE_NATIVE = "native"

JAVA_MAIN_BY_COMMAND = {
    "dev": "io.micronaut.pyronaut.dev.PyronautDevMain",
    "run": "io.micronaut.pyronaut.run.PyronautRunMain",
    "test": "io.micronaut.pyronaut.test.PyronautTestMain",
}
JAVA_DELEGATE_JAR_ENV = {
    "dev": "PYRONAUT_DEV_JAR",
    "run": "PYRONAUT_RUN_JAR",
    "test": "PYRONAUT_TEST_JAR",
}
NATIVE_BUILD_EXECUTABLE = "pyronaut-native-build"
PYTHON_RUN_EXECUTABLE = "pyronaut-run-python"
_DEFAULT_JDK_VERSION = "25"
_DEFAULT_GRAALVM_DOWNLOAD_VERSION = f"{_DEFAULT_JDK_VERSION}i2"
_GDS_DOWNLOAD_URL = "https://gds.oracle.com/download/graal"
_NATIVE_IMAGE_BASE_URL = "https://gds.oracle.com/download/pyronaut/bundles/"
_NATIVE_IMAGE_COMMANDS = {"pyronaut-dev", "pyronaut-run", "pyronaut-run-python"}
_NATIVE_IMAGE_SETTINGS_TABLE = "native-images"
_DEFAULT_DOCKER_JVM_BASE_IMAGE = f"container-registry.oracle.com/graalvm/jdk:{_DEFAULT_GRAALVM_DOWNLOAD_VERSION}"
_DEFAULT_DOCKER_NATIVE_BUILDER_IMAGE = f"container-registry.oracle.com/graalvm/native-image:{_DEFAULT_GRAALVM_DOWNLOAD_VERSION}"
_DEFAULT_DOCKER_NATIVE_BASE_IMAGE = "gcr.io/distroless/base"
_DEFAULT_DOCKER_STATIC_NATIVE_BUILDER_IMAGE = f"container-registry.oracle.com/graalvm/native-image:{_DEFAULT_GRAALVM_DOWNLOAD_VERSION}-muslib-ol8"
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
    "MICRONAUT_TEST_RESOURCES_PROJECT_PATH_URI": "micronaut.test.resources.project-path-uri",
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
_ANSI_YELLOW = "\033[33m"
_ANSI_RESET = "\033[0m"


def _print_version() -> None:
    values: dict[str, str] = {}
    version_file = Path(__file__).with_name("version.properties")
    if version_file.is_file():
        for line in version_file.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
    print(f"Pyronaut: {values.get('pyronaut', 'unknown')}")
    print(f"Micronaut Core: {values.get('micronaut.core', 'unknown')}")
    print(f"Micronaut Platform: {values.get('micronaut.platform', 'unknown')}")
    print(f"GraalPy: {values.get('graalpy', 'unknown')}")
    print(f"Native Image JDK: {values.get('native-image.jdk', 'unknown')}")

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
_DEFAULT_GRAALVM_DOWNLOAD_DISTRIBUTION = "ee"
_PACKAGED_TOOLCHAIN_DEFAULTS = "toolchain-defaults.properties"
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
    input_reader: Callable[[float | None], str | None] | None = None,
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
        _print_version()
        return SUCCESS

    if "--tui" in argv or argv[0] == "--tui":
        tui_args = [value for value in argv if value != "--tui"]
        tui_project_dir = (
            Path.cwd()
            if _looks_like_direct_source_invocation(tui_args)
            else Path(_extract_project_dir(tui_args)).resolve()
        )
        tui_java_home_provider = java_home_provider or _default_java_home_provider(
            runner=runner,
            runner_with_env=runner_with_env,
            process_runner=process_runner,
            project_dir=tui_project_dir,
        )
        return _run_tui(
            argv=list(argv),
            runner_with_env=execute,
            resolver=locate,
            java_home_provider=tui_java_home_provider,
        )

    command = argv[0]
    forwarded_args = _normalize_project_flag(list(argv[1:]))
    forwarded_args = _normalize_no_cache_flag(forwarded_args)
    forwarded_args = _normalize_tests_selection_flag(forwarded_args)
    no_validate = _extract_no_validate(forwarded_args)
    forwarded_args = _remove_no_validate(forwarded_args)
    continuous = _extract_continuous(forwarded_args)
    forwarded_args = _remove_continuous(forwarded_args)
    debug_vm = _extract_debug_vm(forwarded_args)
    forwarded_args = _remove_debug_vm(forwarded_args)

    if command not in SUPPORTED_COMMANDS:
        if _looks_like_direct_source_invocation(argv):
            direct_source_java_home_provider = java_home_provider or _default_java_home_provider(
                runner=runner,
                runner_with_env=runner_with_env,
                process_runner=process_runner,
                project_dir=Path.cwd(),
            )
            return _delegate_direct_source(
                "dev",
                argv,
                execute,
                locate,
                java_home_provider=direct_source_java_home_provider,
            )
        print(f"Unknown command: {command}", file=sys.stderr)
        _print_usage(stream=sys.stderr)
        return USAGE_ERROR

    if command in {"dev", "run"} and (_extract_flag(forwarded_args, "--help") or _extract_flag(forwarded_args, "-h")):
        _print_run_usage(command=command)
        return SUCCESS

    if command == "test" and (_extract_flag(forwarded_args, "--help") or _extract_flag(forwarded_args, "-h")):
        _print_test_usage()
        return SUCCESS

    if command == "dev" and not _looks_like_direct_source_invocation(forwarded_args):
        default_source = _default_dev_source(forwarded_args)
        if default_source is not None:
            forwarded_args.append(default_source)

    if command in {"dev", "run"} and _looks_like_direct_source_invocation(forwarded_args):
        direct_source_java_home_provider = java_home_provider or _default_java_home_provider(
            runner=runner,
            runner_with_env=runner_with_env,
            process_runner=process_runner,
            project_dir=Path.cwd() if command == "run" else None,
        )
        if command == "dev":
            return _run_direct_source(
                command,
                forwarded_args,
                execute,
                process_runner or _spawn_subprocess,
                locate,
                java_home_provider=direct_source_java_home_provider,
                watch_poll_interval=watch_poll_interval,
                watch_debounce_seconds=watch_debounce_seconds,
                monotonic=monotonic,
                sleep=sleep,
            )
        return _delegate_direct_source(
            command,
            forwarded_args,
            execute,
            locate,
            java_home_provider=direct_source_java_home_provider,
        )

    if command == "test" and _looks_like_direct_source_invocation(forwarded_args):
        direct_source_java_home_provider = java_home_provider or _default_java_home_provider(
            runner=runner,
            runner_with_env=runner_with_env,
            process_runner=process_runner,
            project_dir=Path.cwd(),
        )
        direct_args = ["test", *forwarded_args]
        if continuous:
            return _run_direct_source(
                command,
                direct_args,
                execute,
                process_runner or _spawn_subprocess,
                locate,
                java_home_provider=direct_source_java_home_provider,
                watch_poll_interval=watch_poll_interval,
                watch_debounce_seconds=watch_debounce_seconds,
                monotonic=monotonic,
                sleep=sleep,
            )
        return _delegate_direct_source(
            command,
            direct_args,
            execute,
            locate,
            java_home_provider=direct_source_java_home_provider,
        )

    if not _is_supported_platform(current_platform):
        print("Pyronaut CLI v2 phase 1 supports macOS and Linux only.", file=sys.stderr)
        return PLATFORM_UNSUPPORTED

    project_dir = _extract_project_dir(forwarded_args)
    # Report locations are selected by the native test launcher from the
    # project layout. Do not forward the CLI's internal report path option;
    # pyronaut-test intentionally does not expose --report-dir.
    no_cache = _extract_no_cache(forwarded_args)
    local_repository = _extract_local_repository(forwarded_args)
    delegated_args = _strip_no_cache_flag(forwarded_args) if command in {"dev", "run", "test"} else forwarded_args
    delegated_args = _strip_local_repository_args(delegated_args) if command in {"dev", "run", "test"} else delegated_args
    # Keep the dev process under the Python auto-restart loop for both managed
    # and external builds. The native pyronaut-dev launcher runs one
    # application instance; it does not watch Gradle/Maven source trees.
    auto_restart_mode = (
        command == "dev"
        and (process_runner is not None or (runner is None and runner_with_env is None))
    )

    effective_java_home_provider = java_home_provider or _default_java_home_provider(
        runner=runner,
        runner_with_env=runner_with_env,
        process_runner=process_runner,
        project_dir=Path(project_dir),
    )

    if command == "build":
        if _looks_like_direct_build_invocation(forwarded_args):
            return _run_direct_source_build(
                args=forwarded_args,
                runner=execute,
                resolver=locate,
                no_cache=no_cache,
                no_validate=no_validate,
                java_home_provider=effective_java_home_provider,
            )
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
        install_code = _delegate("install", install_args, execute, locate, java_home_provider=effective_java_home_provider)
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
        # Test Resources enablement for external builds comes from the layout
        # produced by install. Refresh it before deciding whether to own a
        # server; otherwise a stale layout from an earlier invocation can
        # incorrectly start Test Resources.
        if command == "test" and _is_external_build_project(Path(project_dir)):
            install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
            if no_cache:
                install_args.append("--no-cache")
            install_code = _delegate("install", install_args, execute, locate)
            if install_code != SUCCESS:
                return install_code
        test_resources_enabled = _test_resources_enabled(Path(project_dir))
        if command in {"dev", "test"} and test_resources_enabled:
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
            if not _is_external_build_project(Path(project_dir)):
                preflight_code = _run_preflight(
                    project_dir,
                    no_cache,
                    local_repository,
                    execute,
                    locate,
                    install=False,
                    process_pass="main",
                )
                if preflight_code != SUCCESS:
                    return preflight_code
            if tr_session is not None:
                tr_session.ensure_started(runner=execute, resolver=locate, java_home_provider=effective_java_home_provider)
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
                process_pass="main",
            )

        if command in {"dev", "run"}:
            if _is_external_build_project(Path(project_dir)):
                preflight_code = _run_preflight(
                    project_dir,
                    no_cache,
                    local_repository,
                    execute,
                    locate,
                    install=command != "dev",
                    # External dev must use the normal process entry point;
                    # the explicit pass can skip Java-only output generation.
                    process_pass=None,
                )
                if preflight_code != SUCCESS:
                    return preflight_code
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

            if not _is_external_build_project(Path(project_dir)):
                preflight_code = _run_preflight(
                    project_dir,
                    no_cache,
                    local_repository,
                    execute,
                    locate,
                    install=False,
                    process_pass="main",
                )
                if preflight_code != SUCCESS:
                    return preflight_code

            if tr_session is not None:
                tr_session.ensure_started(runner=execute, resolver=locate, java_home_provider=effective_java_home_provider)
                test_resources_env_overrides = tr_session.client_env_overrides()

            return _delegate(
                command,
                delegated_args,
                execute,
                locate,
                debug_vm=debug_vm,
                env_overrides=test_resources_env_overrides if command == "dev" else None,
                java_home_provider=effective_java_home_provider,
            )

        if command == "test":
            if continuous:
                return _run_test_continuously(
                    project_dir=Path(project_dir),
                    delegated_args=delegated_args,
                    no_cache=no_cache,
                    execute=execute,
                    resolver=locate,
                    debug_vm=debug_vm,
                    tr_session=tr_session,
                    test_resources_env_overrides=test_resources_env_overrides,
                    no_validate=no_validate,
                    watch_poll_interval=watch_poll_interval,
                    watch_debounce_seconds=watch_debounce_seconds,
                    snapshotter=snapshotter or _snapshot_watched_files,
                    monotonic=monotonic,
                    sleep=sleep,
                    input_reader=input_reader,
                    java_home_provider=effective_java_home_provider,
                    local_repository=local_repository,
                )

            test_exit_code, _ = _run_test_cycle(
                project_dir=Path(project_dir),
                delegated_args=delegated_args,
                no_cache=no_cache,
                execute=execute,
                resolver=locate,
                debug_vm=debug_vm,
                tr_session=tr_session,
                test_resources_env_overrides=test_resources_env_overrides,
                no_validate=no_validate,
                java_home_provider=effective_java_home_provider,
                local_repository=local_repository,
            )
            return test_exit_code

        if command in {"install", "process", "validate-config"}:
            return _delegate(
                command,
                forwarded_args,
                execute,
                locate,
                java_home_provider=effective_java_home_provider,
            )
        if command == "test-resources-server":
            return _delegate(
                command,
                forwarded_args,
                execute,
                locate,
                java_home_provider=effective_java_home_provider,
            )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    finally:
        if tr_session is not None:
            tr_session.stop_if_owned(runner=execute, resolver=locate, java_home_provider=effective_java_home_provider)


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
    if command == "test-resources-server":
        try:
            env = _build_non_test_resources_env(command, java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        env = _merge_env_overrides(env, env_overrides)
        executable_path = _resolve_native_preferred_executable(
            COMMAND_TO_EXECUTABLE["test-resources-server"],
            resolver,
        )
        if executable_path is None:
            print("Missing delegated executable: pyronaut-test-resources-server", file=sys.stderr)
            return PRECONDITION_FAILED
        command_line = [executable_path, *args]
        if _delegation_trace_enabled():
            print(shlex.join(command_line), file=sys.stderr)
        return runner(command_line, env) or SUCCESS

    if command == "dev":
        project_dir = Path(_extract_project_dir(args)).resolve()
        if _is_external_build_project(project_dir):
            classes_dir = _pyronaut_output_dir(project_dir) / "classes"
            if not classes_dir.is_dir():
                # Invoke the public process entry point so it follows exactly
                # the same external-project setup as `pyronaut process`.
                process_executable = shutil.which("pyronaut") or sys.argv[0]
                process_code = subprocess.run(
                    [process_executable, "process", "--project-dir", str(project_dir), "--no-cache"],
                    check=False,
                ).returncode
                if process_code != SUCCESS:
                    return process_code
        try:
            dev_command_line, env = _build_dev_delegate_invocation(
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
            print(shlex.join(dev_command_line), file=sys.stderr)
        return runner(dev_command_line, env)

    # pyronaut-dev's native direct-source command treats unknown options as
    # source paths. Test selectors belong to pyronaut-test, whose launcher
    # understands repeatable --tests options (including pytest node IDs).
    if command == "test" and _has_tests_selection(args) and _bundled_native_executable("pyronaut-dev") is not None:
        return _delegate_via_java(
            command,
            args,
            runner,
            resolver,
            debug_vm=debug_vm,
            env_overrides=env_overrides,
            java_home_provider=java_home_provider,
        )

    dev_command_line = None
    if command != "run":
        try:
            dev_command_line = _pyronaut_dev_native_command_line(
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
    if dev_command_line is not None:
        if _delegation_trace_enabled():
            print(shlex.join(dev_command_line), file=sys.stderr)
        try:
            env = _build_non_test_resources_env(command, java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        env = _merge_env_overrides(env, env_overrides)
        env = _apply_project_virtualenv(env, Path(_extract_project_dir(args)).resolve())
        return runner(dev_command_line, env)

    try:
        run_command_line = _pyronaut_run_native_command_line(
            command,
            args,
            resolver,
            debug_vm=debug_vm,
        )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if run_command_line is not None:
        if _delegation_trace_enabled():
            print(shlex.join(run_command_line), file=sys.stderr)
        try:
            env = _build_non_test_resources_env(command, java_home_provider)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        env = _merge_env_overrides(env, env_overrides)
        env = _apply_project_virtualenv(env, Path(_extract_project_dir(args)).resolve())
        return runner(run_command_line, env)

    # JVM external delegates keep their own launcher/runtime classpath
    # isolated from resolved application dependencies. Native external
    # projects have already taken the pyronaut-dev path above.
    if command in {"run", "test"} and not debug_vm:
        project_dir = Path(_extract_project_dir(args)).resolve()
        if _read_external_layout(project_dir) is not None:
            executable_path = _resolve_delegate_executable_path(command, args, resolver)
            if executable_path is None:
                print(f"Missing delegated executable: {COMMAND_TO_EXECUTABLE[command]}", file=sys.stderr)
                return PRECONDITION_FAILED
            try:
                env = _build_non_test_resources_env(command, java_home_provider)
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
            env = _merge_env_overrides(env, env_overrides)
            env = _apply_project_virtualenv(env, project_dir)
            command_line = [executable_path, *[value for value in args if value not in {"--jvm", "--native"}]]
            if _delegation_trace_enabled():
                print(shlex.join(command_line), file=sys.stderr)
            return runner(command_line, env) or SUCCESS

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
            return runner(command_line, env) or SUCCESS

    if command in {"dev", "run", "test"}:
        launcher_args = [value for value in args if value not in {"--jvm", "--native"}]
        return _delegate_via_java(
            command,
            launcher_args,
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
    env = _apply_project_virtualenv(env, Path(_extract_project_dir(args)).resolve())
    if command == "install" and _has_direct_install_sources(args):
        try:
            direct_launcher = _resolve_pyronaut_dev_native_executable(resolver)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        direct_classpath = _native_launcher_compile_classpath_entries(direct_launcher)
        if direct_classpath:
            env = dict(env or os.environ)
            env["PYRONAUT_DIRECT_CLASSPATH"] = os.pathsep.join(direct_classpath)
    return runner(command_line, env)


def _delegate_direct_source(
    command: str,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    *,
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    try:
        executable_path = _resolve_direct_source_dev_executable(args, resolver)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if executable_path is None:
        print("Missing native delegated executable: pyronaut-dev", file=sys.stderr)
        return PRECONDITION_FAILED
    try:
        env = _build_java_home_env(command, java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    jvm_args = _build_direct_source_native_jvm_args(
        executable_path,
        env,
        command=command,
        environment=_default_environment(command, args),
        args=args,
    )
    forwarded_args = [
        "-Dmicronaut.control-panel.enabled=true" if value == "--control-panel" else value
        for value in args
        if value not in {"--jvm", "--native"}
    ]
    command_line = [executable_path, *jvm_args, *forwarded_args]
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    return runner(command_line, env)


def _run_direct_source(
    command: str,
    args: Sequence[str],
    runner: RunnerWithEnv,
    process_runner: ProcessRunner,
    resolver: Callable[[str], str | None],
    *,
    java_home_provider: JavaHomeProvider | None = None,
    watch_poll_interval: float,
    watch_debounce_seconds: float,
    monotonic: Callable[[], float],
    sleep: Callable[[float], None],
) -> int:
    if command not in {"dev", "test"}:
        return _delegate_direct_source(
            command,
            args,
            runner,
            resolver,
            java_home_provider=java_home_provider,
        )
    try:
        executable_path = _resolve_direct_source_dev_executable(args, resolver)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if executable_path is None:
        print("Missing native delegated executable: pyronaut-dev", file=sys.stderr)
        return PRECONDITION_FAILED
    try:
        env = _build_java_home_env(command, java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if command == "dev":
        env = _apply_project_virtualenv(env, Path.cwd())
    snapshot = _snapshot_direct_source_inputs(args)
    return _run_direct_source_with_auto_restart(
        executable_path,
        command,
        args,
        env,
        runner,
        resolver,
        process_runner,
        snapshot,
        monotonic=monotonic,
        sleep=sleep,
        watch_poll_interval=watch_poll_interval,
        watch_debounce_seconds=watch_debounce_seconds,
        # Direct development must also keep the watcher alive when the
        # compiler exits with an error. This allows a subsequent source
        # change to start a fresh compilation and resume the application.
        keep_watching_after_exit=command == "test",
        keep_watching_after_failure=command == "dev",
    )


def _resolve_direct_source_dev_executable(args: Sequence[str], resolver: Callable[[str], str | None]) -> str | None:
    """Select the direct-source launcher, honoring the command-line override."""
    mode = _extract_build_mode_flag(args)
    if mode is None and (Path.cwd() / "pyproject.toml").is_file():
        mode = _read_pyproject_toolchain_type(Path.cwd())
    if mode == TOOLCHAIN_TYPE_JVM:
        bundled = _bundled_executable(DEV_NATIVE_EXECUTABLE)
        return str(bundled) if bundled is not None and bundled.exists() else resolver(DEV_NATIVE_EXECUTABLE)
    return _resolve_pyronaut_dev_native_executable(resolver) or resolver(DEV_NATIVE_EXECUTABLE)

def _stop_direct_source_test_resources_server(
    *,
    project_dir: Path,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    env: dict[str, str] | None,
) -> None:
    """Stop a server retained while replacing a direct-source child process."""
    restart_marker = project_dir / "__pyronaut__" / "direct-test-resources-restart"
    if not restart_marker.exists():
        return
    session_file = project_dir / "__pyronaut__" / "test-resources-session.json"
    try:
        data = _json_loads(session_file.read_text(encoding="utf-8"))
    except Exception:
        restart_marker.unlink(missing_ok=True)
        return
    if not isinstance(data, dict) or not isinstance(data.get("ownerToken"), str):
        restart_marker.unlink(missing_ok=True)
        return
    executable_path = _resolve_native_preferred_executable(
        COMMAND_TO_EXECUTABLE["test-resources-server"], resolver
    )
    if executable_path is None:
        restart_marker.unlink(missing_ok=True)
        return
    command_line = [
        executable_path,
        "stop",
        "--project-dir",
        str(project_dir),
        "--owner-token",
        data["ownerToken"],
    ]
    try:
        runner(command_line, env)
    except Exception:
        return
    finally:
        restart_marker.unlink(missing_ok=True)


def _run_direct_source_with_auto_restart(
    executable_path: str,
    command: str,
    args: Sequence[str],
    env: dict[str, str] | None,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    process_runner: ProcessRunner,
    snapshot: tuple[tuple[str, int, int], ...],
    *,
    monotonic: Callable[[], float],
    sleep: Callable[[float], None],
    watch_poll_interval: float,
    watch_debounce_seconds: float,
    keep_watching_after_exit: bool = False,
    keep_watching_after_failure: bool = False,
) -> int:
    if watch_poll_interval <= 0:
        watch_poll_interval = 0.25
    if watch_debounce_seconds < 0:
        watch_debounce_seconds = 0.0

    forwarded_args = [
        "-Dmicronaut.control-panel.enabled=true" if value == "--control-panel" else value
        for value in args
        if value not in {"--jvm", "--native"}
    ]
    command_line = [
        executable_path,
        *_build_direct_source_native_jvm_args(
            executable_path,
            env,
            command=command,
            environment=_default_environment(command, args),
            args=forwarded_args,
        ),
        *forwarded_args,
    ]

    cleanup = lambda: _stop_direct_source_test_resources_server(
        project_dir=Path.cwd().resolve(),
        runner=runner,
        resolver=resolver,
        env=env,
    )
    marker = Path.cwd().resolve() / "__pyronaut__" / "direct-test-resources-restart"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.touch()
    atexit.register(cleanup)

    while True:
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
                    if keep_watching_after_exit or (keep_watching_after_failure and code != 0):
                        print("Continuous Testing Active. Waiting for source changes (Ctrl-C to exit).")
                        changed_at: float | None = None
                        while True:
                            sleep(watch_poll_interval)
                            next_snapshot = _snapshot_direct_source_inputs(args)
                            if next_snapshot == snapshot:
                                changed_at = None
                                continue
                            now = monotonic()
                            if changed_at is None:
                                changed_at = now
                                continue
                            if now - changed_at < watch_debounce_seconds:
                                continue
                            snapshot = next_snapshot
                            break
                        break
                    return int(code)
                sleep(watch_poll_interval)
                next_snapshot = _snapshot_direct_source_inputs(args)
                if next_snapshot == snapshot:
                    restart_requested_at = None
                    continue
                now = monotonic()
                if restart_requested_at is None:
                    restart_requested_at = now
                    continue
                if now - restart_requested_at < watch_debounce_seconds:
                    continue
                if not _stop_managed_process(process):
                    print("Failed to stop running process for restart.", file=sys.stderr)
                    return INTERNAL_ERROR
                snapshot = next_snapshot
                break
        except KeyboardInterrupt:
            _stop_managed_process(process)
            return 130


def _build_direct_source_native_jvm_args(
    executable_path: str,
    env: dict[str, str] | None,
    *,
    command: str | None = None,
    environment: str | None = None,
    args: Sequence[str] = (),
) -> list[str]:
    jvm_args: list[str] = []
    java_home = (env or os.environ).get("JAVA_HOME")
    if java_home:
        jvm_args.append(f"-Djava.home={java_home}")
    if command is not None:
        jvm_args.append(f"-Dpyronaut.dev.project.dir={Path.cwd().resolve()}")
    if environment is not None and not _has_micronaut_environments_property(args):
        jvm_args.append(f"-Dmicronaut.environments={environment}")
    if command == "dev":
        jvm_args.append("-Dpyronaut.dev.direct.command=dev")
        if not _control_panel_requested(Path.cwd().resolve(), args):
            jvm_args.append("-Dmicronaut.control-panel.enabled=false")
    elif command == "run":
        jvm_args.append("-Dmicronaut.control-panel.enabled=false")
    if command in {"dev", "run", "test"}:
        # Native GraalPy cannot dispatch optional ImageSingleton lookups.
        # Set this explicitly because the native image may provide a default
        # value before the Java launcher can apply its fallback.
        jvm_args.append("-Dmicronaut.graalvm.imagesingletons.enabled=false")
    if command in {"dev", "test"}:
        jvm_args.append("-Dpyronaut.dev.direct.restartable=true")
    if "--control-panel" in args or any(value == "-Dmicronaut.control-panel.enabled=true" for value in args):
        jvm_args.append("-Dmicronaut.control-panel.enabled=true")
        jvm_args.append("-Dmicronaut.control-panel.path=/control-panel")
        jvm_args.append("-Dmicronaut.control-panel.security.access=ANONYMOUS")
    compiler_classpath = os.pathsep.join(_native_launcher_compile_classpath_entries(executable_path))
    if not compiler_classpath and Path(executable_path).name == DEV_NATIVE_EXECUTABLE:
        with contextlib.suppress(RuntimeError):
            compiler_classpath = os.pathsep.join(_delegate_lib_entries(executable_path))
    if compiler_classpath:
        jvm_args.append(f"-Dpyronaut.dev.compiler.class.path={compiler_classpath}")
        if command == "process":
            # The process command has no application runtime classpath yet;
            # expose the compiler support jars as the native JVM classpath so
            # generated Python annotations are visible during compilation.
            jvm_args.append(f"-Djava.class.path={compiler_classpath}")
    # A direct source launch may be run from a project which has already been
    # installed. Preserve that project's command-specific class path, but keep
    # standalone source execution compatible with older wheels/projects.
    if command in {"dev", "run", "test"}:
        project_dir = Path.cwd().resolve()
        cache_dir = _pyronaut_output_dir(project_dir)
        classpath = ""
        if cache_dir.is_dir():
            try:
                classpath = _build_native_application_classpath(command, project_dir, executable_path)
            except RuntimeError:
                pass
            else:
                jvm_args.append(f"-Djava.class.path={classpath}")
                jvm_args.append(f"-Dpyronaut.dev.application.class.path={classpath}")
        if "--control-panel" in args or any(value == "-Dmicronaut.control-panel.enabled=true" for value in args):
            # Resolve Control Panel artifacts bundled with the launcher wheel;
            # do not infer dependencies from project manifests or Maven local.
            control_panel = _direct_control_panel_classpath_entries(executable_path)
            if control_panel:
                existing = classpath.split(os.pathsep) if classpath else []
                classpath = os.pathsep.join(dict.fromkeys([*existing, *control_panel]))
                jvm_args.append(f"-Dpyronaut.dev.application.class.path={classpath}")
                jvm_args.append(f"-Dpyronaut.dev.control.panel.class.path={os.pathsep.join(control_panel)}")
    return jvm_args


def _direct_control_panel_classpath_entries(executable_path: str) -> list[str]:
    entries: list[str] = []
    launcher_lib = Path(executable_path).parent.parent / "lib"
    if Path(executable_path).name in _NATIVE_IMAGE_COMMANDS:
        packaged_lib = _packaged_tool_dir(Path(executable_path).name) / "lib"
        if (packaged_lib / "control-panel").is_dir():
            launcher_lib = packaged_lib
    bundled_dir = launcher_lib / "control-panel"
    for entry in sorted(bundled_dir.glob("*.jar")):
        if not entry.name.endswith("-sources.jar"):
            entries.append(str(entry))
    return list(dict.fromkeys(entries))


def _direct_source_native_compiler_classpath_entries(executable_path: str) -> list[str]:
    return _native_launcher_compile_classpath_entries(executable_path)


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
    if (environment := _default_environment(command, args)) is not None:
        jvm_args.append(f"-Dmicronaut.environments={environment}")
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


def _launcher_shared_lib_dir(executable_path: str | Path) -> Path:
    """Resolve the wheel-level shared library directory for a launcher."""
    path = Path(executable_path).resolve()
    if path.name in _NATIVE_IMAGE_COMMANDS:
        packaged_shared = Path(__file__).resolve().parent / "tools" / "shared" / "lib"
        if packaged_shared.is_dir():
            return packaged_shared
    tool_dir = path.parent.parent
    return tool_dir.parent / "shared" / "lib"


def _packaged_tool_dir(command_name: str) -> Path:
    return Path(__file__).resolve().parent / "tools" / command_name


def _delegate_lib_entries(executable_path: str, *, include_control_panel: bool = True) -> list[str]:
    path = Path(executable_path).resolve()
    if path.suffix == ".jar":
        return [str(path)]
    command_name = path.name
    packaged_dir = _packaged_tool_dir(command_name) if command_name in _NATIVE_IMAGE_COMMANDS else None
    roots = [path.parent.parent]
    if packaged_dir is not None:
        roots.append(packaged_dir)
    shared_lib = _launcher_shared_lib_dir(path)
    for root in roots:
        manifest = root / "bin" / "pyronaut-classpath.txt"
        if manifest.is_file():
            entries = [line.strip() for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
            resolved = []
            for entry in entries:
                candidate = root / "lib" / entry
                if not candidate.is_file():
                    candidate = shared_lib / entry
                if candidate.is_file():
                    resolved.append(str(candidate))
            if not include_control_panel:
                resolved = [entry for entry in resolved if not _is_control_panel_artifact(Path(entry).name)]
            if resolved:
                return list(dict.fromkeys(resolved))
        lib_dir = root / "lib"
        jars = sorted(lib_dir.glob("*.jar"))
        if not include_control_panel:
            jars = [jar for jar in jars if not _is_control_panel_artifact(jar.name)]
        if jars:
            return [str(jar) for jar in jars]
    raise RuntimeError(f"Unable to resolve delegate jars for {command_name} from the packaged wheel")


def _build_delegate_classpath(command: str, project_dir: Path, resolver: Callable[[str], str | None]) -> str:
    cache_dir = _pyronaut_output_dir(project_dir)
    external = _read_external_layout(project_dir)
    if external is not None:
        key = "developmentRuntimeClasspath" if command == "dev" else "runtimeClasspath" if command == "run" else "testClasspath"
        entries = list(external.get(key, []))
        if command in {"dev", "test"}:
            entries.extend(
                entry for entry in external.get("testClasspath", [])
                if _is_native_test_resources_client_artifact(Path(entry).name)
            )
    elif command == "dev":
        entries = _read_manifest_entries(_resolve_run_manifest(cache_dir))
    elif command == "run":
        classes_dir = cache_dir / "classes"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        entries = _read_manifest_entries(cache_dir / "resolved-runtime-dependencies")
    elif command == "test":
        entries = _read_test_delegate_dependency_entries(cache_dir)
    else:
        entries = _read_test_delegate_dependency_entries(cache_dir)

    if command == "run" and not _control_panel_dependency_declared(project_dir):
        entries = [entry for entry in entries if not _is_control_panel_artifact(Path(entry).name)]
    application_entries = entries
    delegate_entries: list[str] = []
    delegate_executable_name = _delegate_executable_name(command, project_dir)
    override_jar = _read_env(_delegate_jar_env(command, project_dir))
    if override_jar:
        delegate_entries.extend([value for value in override_jar.split(os.pathsep) if value])
    else:
        delegate_executable = resolver(delegate_executable_name)
        if delegate_executable is None:
            raise RuntimeError(f"Missing delegated executable: {delegate_executable_name}")
        delegate_entries.extend(_delegate_lib_entries(
            delegate_executable,
            include_control_panel=_control_panel_dependency_declared(project_dir) if command == "run" else True,
        ))
    # The external build owns the application runtime.  Put it first so its
    # Micronaut/Test Resources services are resolved against the same versions
    # Gradle or Maven selected; the Pyronaut delegate only supplies its command
    # entry point and launcher support.
    # The application runtime is authoritative when it already contains a
    # module supplied by the delegate. This is particularly important for
    # Python VFS jars: loading two versions of context-python or pyronaut-
    # logback creates separate VFS roots and duplicate resource diagnostics.
    application_artifact_ids = {
        coordinate.rsplit(":", 1)[-1]
        for entry in application_entries
        if (coordinate := _artifact_coordinate(entry)) is not None
    }
    delegate_entries = [
        entry for entry in delegate_entries
        if _versioned_jar_artifact_id(Path(entry).name) not in application_artifact_ids
    ]
    entries = [*application_entries, *delegate_entries]

    deduped: list[str] = []
    seen_paths: set[str] = set()
    seen_file_names: set[str] = set()
    seen_coordinates: set[str] = set()
    for entry in entries:
        normalized = str(Path(entry).expanduser().resolve())
        file_name = Path(entry).name
        if normalized in seen_paths:
            continue
        if file_name and file_name in seen_file_names:
            continue
        coordinate = _artifact_coordinate(entry)
        if coordinate is not None and coordinate in seen_coordinates:
            continue
        deduped.append(entry)
        seen_paths.add(normalized)
        if file_name:
            seen_file_names.add(file_name)
        if coordinate is not None:
            seen_coordinates.add(coordinate)
    return os.pathsep.join(deduped)


def _delegate_executable_name(command: str, project_dir: Path) -> str:
    if command == "run" and _is_python_runtime_project(project_dir):
        return PYTHON_RUN_EXECUTABLE
    return COMMAND_TO_EXECUTABLE[command]


def _delegate_jar_env(command: str, project_dir: Path) -> str:
    if command == "run" and _is_python_runtime_project(project_dir):
        return "PYRONAUT_RUN_PYTHON_JAR"
    return JAVA_DELEGATE_JAR_ENV[command]


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


def _build_native_application_classpath(command: str, project_dir: Path, launcher_executable: str | None = None) -> str:
    entries = _build_native_application_classpath_entries(command, project_dir)
    if launcher_executable and "pyronaut-run-python" in Path(launcher_executable).name:
        entries = _replace_embedded_python_vfs_classes(entries, project_dir, launcher_executable)
    if ((command == "dev" and not _control_panel_enabled_for_project(project_dir))
            or (command == "run" and not _control_panel_dependency_declared(project_dir))):
        entries = [entry for entry in entries if not _is_control_panel_artifact(Path(entry).name)]
    filtered = _dedupe_classpath_entries(
            _filter_native_launcher_provided_entries(
                entries,
                launcher_executable,
                command,
            )
        )
    if ((command == "dev" and not _control_panel_enabled_for_project(project_dir))
            or (command == "run" and not _control_panel_dependency_declared(project_dir))):
        filtered = [entry for entry in filtered if not _is_control_panel_artifact(Path(entry).name)]
    return os.pathsep.join(filtered)


def _replace_embedded_python_vfs_classes(
    entries: Sequence[str], project_dir: Path, launcher_executable: str,
) -> list[str]:
    """Remove VFS resources from app classes when the native runner embeds them."""
    classes = project_dir / "__pyronaut__" / "classes"
    if not classes.is_dir():
        return list(entries)
    embedded: set[str] = set()
    for jar in _native_launcher_provided_jar_entries(launcher_executable):
        try:
            with zipfile.ZipFile(jar) as archive:
                prefix = "META-INF/GRAALPY-VFS/micronaut-application/"
                embedded.update(name[len(prefix):] for name in archive.namelist() if name.startswith(prefix))
        except (OSError, zipfile.BadZipFile):
            continue
    if not embedded:
        return list(entries)
    signature = hashlib.sha256(("fileslist-v4\n" + "\n".join(sorted(embedded))).encode()).hexdigest()
    filtered = project_dir / "__pyronaut__" / f"classes-native-runtime-{signature[:12]}"
    marker = filtered / ".native-vfs-filter"
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == signature and filtered.is_dir():
        return [str(filtered.resolve()) if Path(entry).resolve() == classes.resolve() else entry for entry in entries]
    if filtered.is_dir():
        return [str(filtered.resolve()) if Path(entry).resolve() == classes.resolve() else entry for entry in entries]
    shutil.copytree(classes, filtered)
    fileslist = filtered / "META-INF" / "GRAALPY-VFS" / "micronaut-application" / "fileslist.txt"
    removed = {f"/META-INF/GRAALPY-VFS/micronaut-application/{name}" for name in embedded}
    if fileslist.is_file():
        framework_roots = {"io", "jakarta", "micronaut", "logback"}
        framework_prefix = "/META-INF/GRAALPY-VFS/micronaut-application/src/"
        framework_files = set()
        for line in fileslist.read_text(encoding="utf-8").splitlines():
            relative = line.strip()
            if not relative.startswith(framework_prefix):
                continue
            source_path = relative[len(framework_prefix):]
            if source_path.split("/", 1)[0] in framework_roots or source_path.startswith("micronaut_asyncio"):
                framework_files.add(relative)
        removed.update(framework_files)
        fileslist.write_text(
            "".join(line for line in fileslist.read_text(encoding="utf-8").splitlines(keepends=True)
                    if line.strip() not in removed),
            encoding="utf-8",
        )
    for relative in removed:
        if relative == "fileslist.txt":
            continue
        if relative.startswith("/META-INF/GRAALPY-VFS/micronaut-application/"):
            relative = relative[len("/META-INF/GRAALPY-VFS/micronaut-application/"):]
        candidate = filtered / "META-INF" / "GRAALPY-VFS" / "micronaut-application" / relative
        if candidate.is_file():
            candidate.unlink()
    for relative in embedded:
        if relative == "fileslist.txt":
            continue
        candidate = filtered / "META-INF" / "GRAALPY-VFS" / "micronaut-application" / relative
        if candidate.is_file():
            candidate.unlink()
    marker.write_text(signature, encoding="utf-8")
    return [str(filtered.resolve()) if Path(entry).resolve() == classes.resolve() else entry for entry in entries]


def _build_native_application_classpath_entries(command: str, project_dir: Path) -> list[str]:
    cache_dir = _pyronaut_output_dir(project_dir)
    external = _read_external_layout(project_dir)
    layout = _read_pyproject_sources(project_dir)
    entries: list[str] = []
    if external is not None:
        if command in {"process", "process-test"}:
            # Processing happens before __pyronaut__/classes exists. Use the
            # resolver's compile/build classpath directly rather than asking
            # the application runtime classpath builder to add output dirs.
            key = "buildClasspath" if command == "process" else "testClasspath"
            entries.extend(external.get(key, []))
            output_dirs = {
                (cache_dir / "classes").resolve(),
                (cache_dir / "test-classes").resolve(),
            }
            return [
                entry for entry in entries
                if Path(entry).exists()
                and _is_native_application_classpath_entry(entry)
                and Path(entry).resolve() not in output_dirs
            ]
        key = "developmentRuntimeClasspath" if command == "dev" else "runtimeClasspath" if command == "run" else "testClasspath"
        entries.extend(external.get(key, []))
        if ((command == "dev" and not _control_panel_enabled_for_project(project_dir))
                or (command == "run" and not _control_panel_dependency_declared(project_dir))):
            entries = [entry for entry in entries if not _is_control_panel_artifact(Path(entry).name)]
        classes_dir = cache_dir / "classes"
        test_classes_dir = cache_dir / "test-classes"
        if command == "test":
            if not classes_dir.is_dir() and not test_classes_dir.is_dir():
                raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
            # Test classes contain generated test metadata, while production
            # classes contain the application beans and main entry point. Both
            # are required for MicronautTest discovery and startup.
            if classes_dir.is_dir():
                entries.append(str(classes_dir.resolve()))
            if test_classes_dir.is_dir():
                entries.append(str(test_classes_dir.resolve()))
        else:
            if not classes_dir.is_dir():
                # Pure Java external projects may have no Python sources, so
                # the processor legitimately produces no Pyronaut classes.
                # Use the build tool's ordinary application output instead.
                external_classes = [
                    project_dir / "build/classes/java/main",
                    project_dir / "target/classes",
                ]
                classes_dir = next((path for path in external_classes if path.is_dir()), classes_dir)
            if not classes_dir.is_dir():
                raise RuntimeError(f"Missing processed classes directory: {cache_dir / 'classes'}. Run pyronaut process first.")
            entries.append(str(classes_dir.resolve()))
        entries.extend(external.get("mainResources", []))
        if command in {"dev", "test"}:
            entries.extend(external.get("testResources", []))
            entries.extend(
                entry for entry in external.get("testClasspath", [])
                if _is_native_test_resources_client_artifact(Path(entry).name)
            )
        return [entry for entry in entries if _is_native_application_classpath_entry(entry)]
    if command == "process":
        # The project runtime dependencies provide application APIs (such as
        # jakarta.inject); native-compile-classpath is added by the delegate
        # command builder for the embedded compiler APIs.
        entries.extend(_read_manifest_entries(cache_dir / "resolved-runtime-dependencies"))
    elif command == "process-test":
        # Test processing needs all declared dependency scopes, but must not
        # include either generated output directory. Fingerprinting an output
        # as an input makes every successful test compilation invalidate the
        # next invocation.
        for manifest in (
            cache_dir / "resolved-test-dependencies",
            cache_dir / "resolved-runtime-dependencies",
            cache_dir / "resolved-build-dependencies",
        ):
            if manifest.exists():
                entries.extend(_read_manifest_entries(manifest))
    elif command == "dev":
        classes_dir = cache_dir / "classes"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        entries.extend(_read_manifest_entries(_resolve_run_manifest(cache_dir)))
        entries.append(str(classes_dir.resolve()))
        _add_classpath_dir(entries, _resolve_layout_dir(project_dir, layout.resources_dir))
        for resource_dir in layout.additional_resources_dirs:
            _add_classpath_dir(entries, _resolve_layout_dir(project_dir, resource_dir))
        _add_classpath_dir(entries, _resolve_layout_dir(project_dir, layout.test_resources_dir))
        for resource_dir in layout.additional_test_resources_dirs:
            _add_classpath_dir(entries, _resolve_layout_dir(project_dir, resource_dir))
    elif command == "run":
        classes_dir = cache_dir / "classes"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        entries.extend(_read_manifest_entries(cache_dir / "resolved-runtime-dependencies"))
        entries.append(str(classes_dir.resolve()))
        _add_classpath_dir(entries, _resolve_layout_dir(project_dir, layout.resources_dir))
        for resource_dir in layout.additional_resources_dirs:
            _add_classpath_dir(entries, _resolve_layout_dir(project_dir, resource_dir))
    elif command == "test":
        for manifest in (
            cache_dir / "resolved-test-dependencies",
            cache_dir / "resolved-runtime-dependencies",
            cache_dir / "resolved-build-dependencies",
        ):
            if manifest.exists():
                entries.extend(_read_manifest_entries(manifest))
        classes_dir = cache_dir / "classes"
        test_classes_dir = cache_dir / "test-classes"
        if classes_dir.is_dir():
            entries.append(str(classes_dir.resolve()))
        if test_classes_dir.is_dir():
            entries.append(str(test_classes_dir.resolve()))
        if not classes_dir.is_dir() and not test_classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        _add_classpath_dir(entries, _resolve_layout_dir(project_dir, layout.resources_dir))
        for resource_dir in layout.additional_resources_dirs:
            _add_classpath_dir(entries, _resolve_layout_dir(project_dir, resource_dir))
        _add_classpath_dir(entries, _resolve_layout_dir(project_dir, layout.test_resources_dir))
        for resource_dir in layout.additional_test_resources_dirs:
            _add_classpath_dir(entries, _resolve_layout_dir(project_dir, resource_dir))
    else:
        raise RuntimeError(f"Native application classpath is not supported for command: {command}")
    if not _is_python_runtime_project(project_dir):
        # Native development bundles contain GraalPy/compiler support jars.
        # They must not turn a Java application's runtime into a Python
        # runtime merely because those jars are present in its manifests.
        entries = [entry for entry in entries if not _is_python_runtime_artifact(Path(entry).name)]
    return [entry for entry in entries if _is_native_application_classpath_entry(entry)]


def _is_native_application_classpath_entry(entry: str) -> bool:
    path = Path(entry)
    # Resolver manifests can contain POM metadata beside jars; POMs are not
    # runtime classpath entries and can expose the embedded Polyglot metadata.
    return path.suffix.lower() != ".pom"


def _read_external_layout(project_dir: Path) -> dict[str, list[str]] | None:
    layout_file = _pyronaut_output_dir(project_dir) / "project-layout.properties"
    if not layout_file.exists():
        return None
    result: dict[str, list[str]] = {}
    for line in layout_file.read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key == "kind":
            continue
        result[key] = [str(Path(entry).expanduser().resolve()) for entry in value.split(os.pathsep) if entry]
    return result


def _pyronaut_output_dir(project_dir: Path) -> Path:
    """Return the generated-output directory for the project build tool."""
    if (project_dir / "pom.xml").is_file():
        return project_dir / "target" / "pyronaut"
    if any((project_dir / descriptor).is_file() for descriptor in (
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    )):
        return project_dir / "build" / "pyronaut"
    return project_dir / "__pyronaut__"


def _add_classpath_dir(entries: list[str], directory: Path) -> None:
    if directory.is_dir():
        entries.append(str(directory.resolve()))


def _dedupe_classpath_entries(entries: Sequence[str]) -> list[str]:
    deduped: list[str] = []
    seen: set[str] = set()
    for entry in entries:
        if entry not in seen:
            deduped.append(entry)
            seen.add(entry)
    return deduped


def _filter_native_launcher_provided_entries(
    entries: Sequence[str],
    launcher_executable: str | None,
    command: str,
) -> list[str]:
    launcher_provided_names = _native_launcher_provided_file_names(launcher_executable)
    launcher_provided_artifact_ids = _native_launcher_provided_artifact_ids(launcher_executable, launcher_provided_names)
    return [
        entry
        for entry in entries
        if not _is_native_launcher_provided_artifact(entry, launcher_provided_names, launcher_provided_artifact_ids, command)
    ]


def _native_launcher_provided_file_names(launcher_executable: str | None) -> set[str]:
    manifest_entries = _native_launcher_manifest_entries(launcher_executable, "native-provided-classpath.txt")
    manifest_names = {entry for entry in manifest_entries if ":" not in entry}
    if manifest_entries:
        # A coordinate-based manifest is authoritative, even though it has no
        # file names to return. Falling back to every JAR in lib would mark
        # unrelated compiler dependencies as native-provided.
        return manifest_names
    return {Path(entry).name for entry in _native_launcher_provided_jar_entries(launcher_executable)}


def _native_launcher_provided_artifact_ids(
    launcher_executable: str | None,
    file_names: set[str],
) -> set[tuple[str | None, str]]:
    coordinates = _native_launcher_provided_artifact_coordinates(launcher_executable)
    if coordinates:
        return {
            identity
            for coordinate in coordinates
            if (identity := _artifact_identity(coordinate)) is not None
        }
    return {(None, artifact_id) for artifact_id in _versioned_jar_artifact_ids(file_names)}


def _native_launcher_manifest_entries(launcher_executable: str | None, manifest_name: str) -> list[str]:
    if not launcher_executable:
        return []
    executable_path = Path(launcher_executable).resolve()
    executable_parent = executable_path.parent
    candidates = (
        executable_parent / "resources" / executable_path.name / manifest_name,
        executable_parent / manifest_name,
        executable_parent.parent / "bin" / manifest_name,
        executable_parent.parent / manifest_name,
    )
    if executable_path.name in _NATIVE_IMAGE_COMMANDS:
        packaged_tool = _packaged_tool_dir(executable_path.name)
        candidates += (
            packaged_tool / "bin" / manifest_name,
            packaged_tool / manifest_name,
        )
    # A locally built nativeCompile binary lives below build/native/nativeCompile,
    # while its manifests are generated under build/generated/native-classpaths.
    # Keep local native runs consistent with installed distributions so embedded
    # Polyglot/GraalPy jars are not re-added to java.class.path.
    candidates += tuple(
        parent / "generated" / "native-classpaths" / manifest_name
        for parent in executable_path.parents
    )
    manifest = next((candidate for candidate in candidates if candidate.is_file()), None)
    if manifest is None:
        return []
    return [
        line.strip()
        for line in manifest.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _native_launcher_provided_artifact_coordinates(launcher_executable: str | None) -> set[str]:
    return {
        entry
        for entry in _native_launcher_manifest_entries(launcher_executable, "native-provided-classpath.txt")
        if ":" in entry
    }


def _native_launcher_provided_manifest_file_names(launcher_executable: str | None) -> set[str]:
    # Compatibility helper for callers/tests written before the manifest was
    # changed from filenames to Maven coordinates.
    return _native_launcher_provided_artifact_coordinates(launcher_executable)


def _native_launcher_compile_classpath_entries(launcher_executable: str | None) -> list[str]:
    entries = _native_launcher_manifest_entries(launcher_executable, "native-compile-classpath.txt")
    if entries:
        launcher_parent = Path(launcher_executable).parent if launcher_executable else None
        lib_dirs = []
        if launcher_parent:
            lib_dirs.extend((launcher_parent.parent / "lib", _launcher_shared_lib_dir(launcher_executable)))
        resolved: list[str] = []
        for entry in entries:
            candidate = Path(entry)
            if candidate.is_file():
                resolved.append(str(candidate))
            elif lib_dirs:
                if ":" in entry and "/" not in entry:
                    artifact_id = entry.split(":", 1)[1]
                    matches = sorted({path for lib_dir in lib_dirs for path in lib_dir.glob(artifact_id + "-*.jar")})
                    resolved.extend(str(path) for path in matches)
                else:
                    for lib_dir in lib_dirs:
                        bundled = lib_dir / candidate.name
                        if bundled.is_file():
                            resolved.append(str(bundled))
                            break
        return resolved
    # Older distributions do not have a reduced compiler manifest. Their
    # native parent remains sufficient, so do not re-add the whole lib dir.
    return []


def _native_launcher_provided_jar_entries(launcher_executable: str | None) -> list[str]:
    if not launcher_executable:
        return []
    executable_path = Path(launcher_executable)
    candidate_lib_dirs = [
        executable_path.parent / "lib",
        executable_path.parent.parent / "lib",
        executable_path.parent.parent.parent / "lib",
        _launcher_shared_lib_dir(executable_path),
    ]
    if executable_path.name in _NATIVE_IMAGE_COMMANDS:
        candidate_lib_dirs.extend((
            _packaged_tool_dir(executable_path.name) / "lib",
            _packaged_tool_dir(executable_path.name).parent / "shared" / "lib",
        ))
    manifest_entries = _native_launcher_manifest_entries(
        launcher_executable, "native-provided-classpath.txt"
    )
    provided_ids = {
        entry.split(":", 1)[1] if ":" in entry else _versioned_jar_artifact_id(Path(entry).name)
        for entry in manifest_entries
    }
    provided_ids.discard(None)
    resolved: list[str] = []
    for lib_dir in candidate_lib_dirs:
        if not lib_dir.is_dir():
            continue
        for entry in sorted(lib_dir.iterdir()):
            if (entry.is_file() and entry.suffix == ".jar"
                    and not entry.name.endswith("-sources.jar")
                    and (not provided_ids or _versioned_jar_artifact_id(entry.name) in provided_ids)):
                resolved.append(str(entry.resolve()))
    return list(dict.fromkeys(resolved))


def _versioned_jar_artifact_ids(file_names: Iterable[str]) -> set[str]:
    return {
        artifact_id
        for file_name in file_names
        if (artifact_id := _versioned_jar_artifact_id(file_name)) is not None
    }


def _versioned_jar_artifact_id(file_name: str) -> str | None:
    if not file_name.endswith(".jar"):
        return None
    base_name = file_name.removesuffix(".jar")
    for index, character in enumerate(base_name):
        if character == "-" and index + 1 < len(base_name) and base_name[index + 1].isdigit():
            return base_name[:index]
    return None


def _artifact_identity(coordinate: str | None) -> tuple[str | None, str] | None:
    if coordinate is None:
        return None
    group, separator, artifact = coordinate.partition(":")
    if not separator or not group or not artifact:
        return None
    return group, artifact


def _is_native_launcher_provided_artifact(
    entry: str,
    launcher_provided_names: set[str],
    launcher_provided_artifact_ids: set[tuple[str | None, str]],
    command: str,
) -> bool:
    file_name = Path(entry).name
    if _is_control_panel_artifact(file_name):
        return command != "dev"
    if command == "dev" and _versioned_jar_artifact_id(file_name) == "micronaut-management":
        return False
    coordinate = _artifact_coordinate(entry)
    if coordinate is not None and coordinate.startswith("io.micronaut.serde:"):
        # Serde jars carry runtime bean definitions (JacksonObjectMapper and
        # SerdeRegistry); native embedding does not replace those resources.
        return False
    if coordinate == "io.micrometer:micrometer-core":
        # Native launcher metadata may list Micrometer transitively, but the
        # application still needs SimpleMeterRegistry at runtime.
        return False
    if _is_native_test_resources_client_artifact(file_name):
        return True
    if file_name in launcher_provided_names:
        return True
    artifact_id = _versioned_jar_artifact_id(file_name)
    identity = _artifact_identity(coordinate) if coordinate is not None else (None, artifact_id)
    return identity is not None and identity in launcher_provided_artifact_ids


@lru_cache(maxsize=4096)
def _artifact_coordinate(entry: str) -> str | None:
    if ":" in entry and "/" not in entry and "\\" not in entry:
        parts = entry.split(":")
        if len(parts) >= 2 and all(parts[:2]):
            return f"{parts[0]}:{parts[1]}"
    path = Path(entry)
    name = _versioned_jar_artifact_id(path.name)
    if name is None:
        return None
    parts = path.parts
    for pom in _classpath_pom_candidates(path, name):
        coordinate = _pom_coordinate(pom)
        if coordinate is not None:
            return coordinate
    try:
        artifact_index = len(parts) - 3
        if parts[artifact_index] != name:
            return None
        repository_index = parts.index("repository")
        group = ".".join(parts[repository_index + 1:artifact_index])
        return f"{group}:{name}" if group else None
    except (ValueError, IndexError):
        return None


def _classpath_pom_candidates(path: Path, artifact: str) -> tuple[Path, ...]:
    stem_pom = path.with_suffix(".pom")
    candidates = [stem_pom, path.parent.parent / f"{artifact}-{path.parent.parent.name}.pom"]
    # Gradle's files-2.1 layout stores the POM in a sibling hash directory.
    if path.parent.parent.parent.name == artifact:
        version_dir = path.parent.parent
        candidates.extend(version_dir.glob("*/" + artifact + "-*.pom"))
    return tuple(dict.fromkeys(candidates))


@lru_cache(maxsize=4096)
def _pom_coordinate(path: Path) -> str | None:
    if not path.is_file():
        return None
    try:
        root = ET.parse(path).getroot()
        ns = "{http://maven.apache.org/POM/4.0.0}"
        group = root.findtext(f"{ns}groupId") or root.findtext("groupId")
        artifact = root.findtext(f"{ns}artifactId") or root.findtext("artifactId")
        return f"{group}:{artifact}" if group and artifact else None
    except (ET.ParseError, OSError):
        return None


def _is_native_test_resources_client_artifact(file_name: str) -> bool:
    return (
        file_name.startswith("micronaut-test-resources-client-")
        or file_name.startswith("micronaut-test-resources-core-")
        or file_name.startswith("micronaut-test-resources-codec-")
    )


def _is_control_panel_artifact(file_name: str) -> bool:
    return file_name.startswith("micronaut-control-panel-")


def _is_test_launcher_provided_artifact(entry: str) -> bool:
    file_name = Path(entry).name
    return (
        file_name.startswith("micronaut-context-python-")
        or file_name.startswith("micronaut-pyronaut-pytest-")
    )


def _build_native_test_resources_client_classpath(project_dir: Path) -> str:
    cache_dir = project_dir / "__pyronaut__"
    external = _read_external_layout(project_dir)
    if external is not None:
        entries = [
            *(entry for entry in external.get("testClasspath", [])
              if _is_native_test_resources_client_artifact(Path(entry).name)),
        ]
    else:
        entries = [
            entry
            for manifest in (
                cache_dir / "resolved-development-runtime-dependencies",
                cache_dir / "resolved-test-dependencies",
                cache_dir / "resolved-runtime-dependencies",
            )
            if manifest.exists()
            for entry in _read_manifest_entries(manifest)
            if _is_native_test_resources_client_artifact(Path(entry).name)
        ]
    return os.pathsep.join(_dedupe_classpath_entries(entries))


def _run_preflight(
    project_dir: str,
    no_cache: bool,
    local_repository: str | None,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    *,
    install: bool = True,
    process_pass: str | None = None,
) -> int:
    if install:
        _seed_bundled_pyronaut_maven_repository(
            Path(project_dir),
            local_repository,
            resolver("pyronaut-install"),
        )
        install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
        if no_cache:
            install_args.append("--no-cache")
        install_code = _delegate("install", install_args, runner, resolver)
        if install_code != SUCCESS:
            return install_code

    process_args = ["--project-dir", project_dir]
    layout = _read_pyproject_sources(Path(project_dir))
    if layout.python_source_dir != _DEFAULT_PYTHON_SOURCE_DIR:
        process_args.extend(["--python-src", layout.python_source_dir])
    if layout.java_source_dir != _DEFAULT_JAVA_SOURCE_DIR:
        process_args.extend(["--java-src", layout.java_source_dir])
    if process_pass is not None:
        process_args.extend(["--pass", process_pass])
    # A prior cached processing run may have been interrupted or its output
    # removed. Force regeneration when the expected classes directory is
    # missing; otherwise `dev` can incorrectly reuse the external-build cache
    # and fail during classpath assembly.
    if no_cache:
        process_args.append("--no-cache")
    return _delegate("process", process_args, runner, resolver)


def _local_repository_install_args(local_repository: str | None = None) -> list[str]:
    repository = local_repository or _read_env(LOCAL_REPOSITORY_ENV)
    if repository is None:
        return []
    return ["--local-repository", repository]


def _seed_bundled_pyronaut_maven_repository(
    project_dir: Path,
    local_repository: str | None,
    install_executable: str | None,
) -> None:
    """Expose Pyronaut snapshot modules shipped in the SDK wheel to Maven.

    The delegated installer intentionally resolves through Maven rather than
    the CLI's Java classpath. Copying the wheel's Pyronaut module JARs into the
    selected local repository gives those modules normal Maven coordinates and
    avoids requiring a snapshot publication for every SDK wheel build.
    """
    if install_executable is None:
        return
    repository = local_repository
    if repository is None:
        return
    lib_dir = Path(install_executable).resolve().parent.parent / "lib"
    if not lib_dir.is_dir():
        return
    target_root = Path(repository).expanduser().resolve()
    for jar in sorted(lib_dir.glob("micronaut-pyronaut-*.jar")):
        match = re.match(r"^(micronaut-pyronaut-[^-].*)-(\d+[^/]*)\.jar$", jar.name)
        if match is None:
            continue
        artifact, version = match.groups()
        # The wheel is produced from this repository, whose published group
        # is stable across modules.
        artifact_dir = target_root / "io" / "micronaut" / "pyronaut" / artifact / version
        artifact_dir.mkdir(parents=True, exist_ok=True)
        staged_jar = artifact_dir / jar.name
        if not staged_jar.exists() or staged_jar.stat().st_size != jar.stat().st_size:
            shutil.copy2(jar, staged_jar)
        pom = artifact_dir / f"{artifact}-{version}.pom"
        if not pom.exists():
            pom.write_text(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                "<modelVersion>4.0.0</modelVersion>"
                f"<groupId>io.micronaut.pyronaut</groupId><artifactId>{artifact}</artifactId>"
                f"<version>{version}</version></project>",
                encoding="utf-8",
            )


def _has_direct_install_sources(args: Sequence[str]) -> bool:
    options_with_values = {
        "--project-dir",
        "--project",
        "--local-repository",
        "--local-repo",
        "--scope",
        "--progress",
        "--color",
    }
    skip_next = False
    for token in args:
        if skip_next:
            skip_next = False
            continue
        if token in options_with_values:
            skip_next = True
            continue
        if token.startswith("-"):
            continue
        return True
    return False


def _looks_like_direct_build_invocation(args: Sequence[str]) -> bool:
    return bool(_direct_build_source_selectors(args))


def _direct_build_source_selectors(args: Sequence[str]) -> list[str]:
    value_options = {
        "--project-dir", "--project", "--mode", "--main-class", "--base-image-output",
        "--name", "--version", "--setup", "--local-repository", "--local-repo",
    }
    selectors: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--":
            break
        if token in value_options:
            index += 2
            continue
        if any(token.startswith(option + "=") for option in value_options):
            index += 1
            continue
        if token.startswith("-"):
            index += 1
            continue
        path = Path(token)
        if token.endswith((".java", ".py")) or path.is_dir():
            selectors.append(token)
        index += 1
    return selectors


def _extract_build_value(args: Sequence[str], option: str) -> str | None:
    for index, token in enumerate(args):
        if token == option:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {option}")
            value = args[index + 1].strip()
            if not value:
                raise ValueError(f"Invalid empty value for {option}")
            return value
        if token.startswith(option + "="):
            value = token.split("=", 1)[1].strip()
            if not value:
                raise ValueError(f"Invalid empty value for {option}")
            return value
    return None


def _direct_build_source_files(root: Path, selectors: Sequence[str]) -> tuple[str, list[Path]]:
    files: list[Path] = []
    for selector in selectors:
        selected = Path(selector)
        path = selected if selected.is_absolute() else root / selected
        if not path.exists():
            raise ValueError(f"Direct source does not exist: {selector}")
        candidates = sorted(path.rglob("*.java")) + sorted(path.rglob("*.py")) if path.is_dir() else [path]
        files.extend(candidate.resolve() for candidate in candidates if candidate.suffix in {".java", ".py"})
    files = list(dict.fromkeys(files))
    if not files:
        raise ValueError("Direct source build contains no .java or .py files")
    languages = {"java" if file.suffix == ".java" else "python" for file in files}
    if len(languages) != 1:
        raise ValueError("Direct source build cannot mix Java and Python sources")
    return languages.pop(), files


def _direct_build_arguments(args: Sequence[str], staging_project: Path, root: Path, language: str) -> list[str]:
    selectors = set(_direct_build_source_selectors(args))
    value_options = {"--project-dir", "--project", "--name", "--version", "--setup"}
    result: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--base-image-output" and index + 1 < len(args):
            output = Path(args[index + 1])
            result.extend([token, str(output if output.is_absolute() else (root / output).resolve())])
            index += 2
            continue
        if token.startswith("--base-image-output="):
            output = Path(token.split("=", 1)[1])
            result.append("--base-image-output=" + str(output if output.is_absolute() else (root / output).resolve()))
            index += 1
            continue
        if token in value_options:
            index += 2
            continue
        if any(token.startswith(option + "=") for option in value_options):
            index += 1
            continue
        if token in selectors:
            index += 1
            continue
        result.append(token)
        index += 1
    result.extend(["--project-dir", str(staging_project)])
    if _extract_build_base_image(args) and _extract_build_base_image_output(args) is None:
        launcher = PYTHON_RUN_EXECUTABLE if language == "python" else COMMAND_TO_EXECUTABLE["run"]
        result.extend(["--base-image-output", str(root / "__pyronaut__" / "native" / "base" / launcher)])
    return result


def _write_direct_build_pyproject(target: Path, project_name: str, project_version: str, language: str) -> None:
    source_key = "python" if language == "python" else "java"
    source_dir = _DEFAULT_PYTHON_SOURCE_DIR if language == "python" else _DEFAULT_JAVA_SOURCE_DIR
    target.write_text(
        "[project]\n"
        f"name = {project_name!r}\n"
        f"version = {project_version!r}\n\n"
        "[tool.pyronaut.sources]\n"
        f"{source_key} = {source_dir!r}\n",
        encoding="utf-8",
    )


def _ensure_direct_java_annotation_dependency(path: Path) -> None:
    """Make inline Java @pyronaut.build declarations compilable."""
    text = path.read_text(encoding="utf-8")
    coordinate = '"io.micronaut.pyronaut:micronaut-pyronaut-build-annotations"'
    if coordinate in text and re.search(r"(?m)^runtime\s*=", text):
        return
    section = "[tool.pyronaut.dependencies]"
    if section not in text:
        text = text.rstrip() + f"\n\n{section}\nruntime = [{coordinate}]\nbuild = [{coordinate}]\n"
    else:
        match = re.search(r"(?ms)^build\s*=\s*\[(.*?)^\]", text)
        if match:
            body = match.group(1).rstrip()
            addition = (",\n  " if body else "\n  ") + coordinate + "\n"
            text = text[:match.start(1)] + body + addition + text[match.end(1):]
        else:
            text = text.rstrip() + f"\nruntime = [{coordinate}]\nbuild = [{coordinate}]\n"
        if not re.search(r"(?m)^runtime\s*=", text):
            text = text.replace(section, section + f"\nruntime = [{coordinate}]", 1)
    path.write_text(text, encoding="utf-8")


def _run_direct_source_build(
    *,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    no_cache: bool,
    no_validate: bool,
    java_home_provider: JavaHomeProvider | None,
) -> int:
    root = Path(_extract_project_dir(args)).resolve()
    selectors = _direct_build_source_selectors(args)
    try:
        language, source_files = _direct_build_source_files(root, selectors)
        configured_name = _extract_build_value(args, "--name")
        configured_version = _extract_build_value(args, "--version")
        setup = _extract_build_value(args, "--setup")
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR
    fingerprint_inputs = [
        *(f"{file}:{hashlib.sha256(file.read_bytes()).hexdigest()}" for file in source_files),
        f"name:{configured_name or ''}",
        f"version:{configured_version or ''}",
    ]
    if setup is not None:
        setup_fingerprint_path = Path(setup)
        if not setup_fingerprint_path.is_absolute():
            setup_fingerprint_path = root / setup_fingerprint_path
        if setup_fingerprint_path.is_file():
            fingerprint_inputs.append(
                f"setup:{setup_fingerprint_path.resolve()}:{hashlib.sha256(setup_fingerprint_path.read_bytes()).hexdigest()}"
            )
    source_fingerprint = hashlib.sha256("\n".join(fingerprint_inputs).encode("utf-8")).hexdigest()[:16]
    staging_project = root / "__pyronaut__" / "direct-source-build" / source_fingerprint
    if staging_project.exists():
        shutil.rmtree(staging_project)
    staging_project.mkdir(parents=True, exist_ok=True)
    try:
        if setup is not None:
            setup_path = Path(setup)
            setup_path = setup_path if setup_path.is_absolute() else root / setup_path
            if not setup_path.is_file():
                raise ValueError(f"Configured setup file does not exist: {setup}")
            shutil.copy2(setup_path, staging_project / "pyproject.toml")
        setup_name, setup_version = _read_pyproject_project_metadata(staging_project)
        project_name = configured_name or (setup_name if setup is not None else source_files[0].stem)
        project_version = configured_version or (setup_version if setup is not None else "0.1.0")
        if not (staging_project / "pyproject.toml").exists():
            _write_direct_build_pyproject(staging_project / "pyproject.toml", project_name, project_version, language)
        if language == "java":
            _ensure_direct_java_annotation_dependency(staging_project / "pyproject.toml")
        layout = _read_pyproject_sources(staging_project)
        if setup is not None:
            for resource_dir in (layout.resources_dir, *layout.additional_resources_dirs):
                source_resource_dir = setup_path.parent / resource_dir
                if source_resource_dir.is_dir():
                    shutil.copytree(source_resource_dir, staging_project / resource_dir, dirs_exist_ok=True)
        source_dir = layout.python_source_dir if language == "python" else layout.java_source_dir
        destination = staging_project / source_dir
        destination.mkdir(parents=True, exist_ok=True)
        # The processor accepts both language roots and expects each declared
        # root to exist. Direct-source staging only has one language, so create
        # the empty companion root to prevent a misleading missing-`src`
        # failure during Java-only builds (and vice versa).
        (staging_project / layout.python_source_dir).mkdir(parents=True, exist_ok=True)
        (staging_project / layout.java_source_dir).mkdir(parents=True, exist_ok=True)
        for source in source_files:
            shutil.copy2(source, destination / source.name)

        declaration_root = staging_project / "declarations"
        declaration_root.mkdir(parents=True, exist_ok=True)
        declaration_selectors: list[str] = []
        for source in source_files:
            staged_source = declaration_root / source.name
            shutil.copy2(source, staged_source)
            declaration_selectors.append(staged_source.name)
        install_args = [
            "--project-dir",
            str(declaration_root),
            *_local_repository_install_args(_extract_local_repository(args)),
            *declaration_selectors,
        ]
        if no_cache:
            install_args.append("--no-cache")
        install_code = _delegate("install", install_args, runner, resolver, java_home_provider=java_home_provider)
        if install_code != SUCCESS:
            return install_code
        source_cache = declaration_root / "__pyronaut__"
        staging_cache = staging_project / "__pyronaut__"
        staging_cache.mkdir(parents=True, exist_ok=True)
        for name in (
            "resolved-build-dependencies",
            "resolved-runtime-dependencies",
            "resolved-test-dependencies",
            "m2-repository",
        ):
            source = source_cache / name
            if source.is_dir():
                shutil.copytree(source, staging_cache / name, dirs_exist_ok=True)
            elif source.is_file():
                shutil.copy2(source, staging_cache / name)
        # Direct source installation has no test sources and therefore does
        # not materialize this otherwise-required shared pipeline manifest.
        (staging_cache / "resolved-test-dependencies").touch(exist_ok=True)

        if setup is not None:
            direct_manifests = {
                name: _read_manifest_entries(staging_cache / name)
                for name in (
                    "resolved-build-dependencies",
                    "resolved-runtime-dependencies",
                    "resolved-test-dependencies",
                )
            }
            setup_install_args = [
                "--project-dir",
                str(staging_project),
                *_local_repository_install_args(_extract_local_repository(args)),
            ]
            if no_cache:
                setup_install_args.append("--no-cache")
            setup_install_code = _delegate("install", setup_install_args, runner, resolver, java_home_provider=java_home_provider)
            if setup_install_code != SUCCESS:
                return setup_install_code
            for name, direct_entries in direct_manifests.items():
                manifest = staging_cache / name
                configured_entries = _read_manifest_entries(manifest)
                manifest.write_text(
                    "".join(f"{entry}\n" for entry in dict.fromkeys([*configured_entries, *direct_entries])),
                    encoding="utf-8",
                )

        exit_code = _run_build(
            args=_direct_build_arguments(args, staging_project, root, language),
            runner=runner,
            resolver=resolver,
            no_cache=no_cache,
            no_validate=no_validate,
            java_home_provider=java_home_provider,
            project_metadata=(project_name, project_version),
            preflight_install=False,
        )
        if exit_code == SUCCESS and not _extract_build_docker(args):
            dist = root / "dist"
            dist.mkdir(parents=True, exist_ok=True)
            for artifact in (staging_project / "dist").glob("*"):
                if artifact.is_file():
                    shutil.copy2(artifact, dist / artifact.name)
        return exit_code
    except (OSError, RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED


def _run_build(
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    no_cache: bool,
    no_validate: bool,
    java_home_provider: JavaHomeProvider | None,
    project_metadata: tuple[str, str] | None = None,
    preflight_install: bool = True,
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
    base_image_build = _extract_build_base_image(args)
    requested_default_base_image = _extract_build_default_base_image(args)
    configured_default_base_image = (_read_pyproject_build_base_image(project_dir) or "").strip().lower() == "default"
    default_base_image = requested_default_base_image or (configured_default_base_image and not base_image_build)
    if base_image_build and default_base_image:
        print("--base-image and --base-image=default cannot be combined", file=sys.stderr)
        return USAGE_ERROR
    if default_base_image and mode == "jvm":
        print("--base-image=default is only supported for native builds", file=sys.stderr)
        return USAGE_ERROR
    if base_image_build:
        if _extract_build_mode_flag(args) == "jvm":
            print("--base-image cannot be combined with JVM mode", file=sys.stderr)
            return USAGE_ERROR
        mode = "native"
    try:
        project_name, project_version = project_metadata or _read_pyproject_project_metadata(project_dir)
        main_class = _extract_main_class(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR

    if mode == "native" and _has_main_class_option(args):
        print("--main-class is not supported for native builds; PyronautRunMain is always used", file=sys.stderr)
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

    preflight = _run_preflight(str(project_dir), no_cache, None, runner, resolver, install=preflight_install)
    if preflight != SUCCESS:
        return preflight

    if base_image_build and not docker_build:
        return _run_native_base_image_build(
            args=args,
            runner=runner,
            resolver=resolver,
            project_dir=project_dir,
            verbose=verbose,
            java_home_provider=java_home_provider,
        )

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
            base_image_build=base_image_build,
            default_base_image=default_base_image,
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
        try:
            configured_base = _configured_local_base_image(project_dir)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        configured_default_base = (_read_pyproject_build_base_image(project_dir) or "").strip().lower() == "default"
        if default_base_image:
            try:
                configured_base = _bundled_default_base_image(project_dir)
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
        if (default_base_image or configured_default_base) and configured_base is None:
            print("The default native base image is not available for this platform.", file=sys.stderr)
            return PRECONDITION_FAILED
        if default_base_image:
            delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
            if delegate_executable is None:
                print(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}", file=sys.stderr)
                return PRECONDITION_FAILED
            native_command = [
                delegate_executable,
                "--project-dir",
                str(project_dir),
                "--output",
                str(output_binary),
                "--base-image",
                "--default-base-image",
                "--default-base-image-path",
                str(configured_base),
            ]
            if _extract_offline(args):
                native_command.append("--offline")
            if verbose:
                native_command.append("--verbose")
            native_command.extend(_extract_native_build_passthrough_args(args))
            if _delegation_trace_enabled():
                print(shlex.join(native_command), file=sys.stderr)
            exit_code = runner(native_command, env)
            if exit_code != SUCCESS:
                return exit_code
        elif configured_base is not None:
            if not configured_base.is_file():
                print(f"Configured base image does not exist: {configured_base}. Run pyronaut build --base-image first.", file=sys.stderr)
                return PRECONDITION_FAILED
            shutil.copy2(configured_base, output_binary)
            output_binary.chmod(0o755)
        else:
            delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
            if delegate_executable is None:
                print(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}", file=sys.stderr)
                return PRECONDITION_FAILED
            native_command = [
                delegate_executable,
                "--project-dir",
                str(project_dir),
                "--output",
                str(output_binary),
            ]
            if _extract_offline(args):
                native_command.append("--offline")
            if _is_python_runtime_project(project_dir):
                native_command.append("--include-python")
            native_command.extend(_native_user_package_args(project_dir))
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
                runner_executable=None,
            )
            wheel_command = [
                python_exec,
                "-m",
                "pip",
                "wheel",
                "--no-deps",
                *(["--no-build-isolation"] if _extract_offline(args) else []),
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
            runner_executable=resolver(PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]),
        )
        wheel_command = [
            python_exec,
            "-m",
            "pip",
            "wheel",
            "--no-deps",
            *(["--no-build-isolation"] if _extract_offline(args) else []),
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
    runner_executable: str | None = None,
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
        classes_dir = project_dir / "__pyronaut__" / "classes"
        runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
        if not classes_dir.is_dir():
            raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
        _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
        if runtime_manifest.exists():
            _stage_manifest_artifacts(
                source=runtime_manifest,
                target=pyronaut_dir / "resolved-runtime-dependencies",
                project_dir=project_dir,
                pyronaut_dir=pyronaut_dir,
                exclude_python=not _is_python_runtime_project(project_dir),
            )
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
            exclude_python=not _is_python_runtime_project(project_dir),
        )
        if runner_executable is not None:
            _stage_runner_runtime_dependencies(pyronaut_dir, runner_executable)
        launcher_code = _jvm_build_launcher_code("io.micronaut.pyronaut.run.PyronautRunMain")

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


def _stage_manifest_artifacts(
    *, source: Path, target: Path, project_dir: Path, pyronaut_dir: Path,
    exclude_python: bool = False,
    launcher_executable: str | None = None,
) -> None:
    project_root = project_dir.resolve()
    artifact_root = pyronaut_dir / "m2-repository"
    rewritten: list[str] = []
    entries = _read_manifest_entries(source)
    if launcher_executable:
        entries = _filter_native_launcher_provided_entries(
            entries, launcher_executable, "build"
        )
    for entry in entries:
        path = Path(entry)
        if exclude_python and _is_python_runtime_artifact(path.name):
            continue
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


def _is_python_runtime_artifact(file_name: str) -> bool:
    name = file_name.lower()
    return (
        "context-python" in name
        or "inject-python" in name
        or "graalpy" in name
        or "truffle" in name
        or "polyglot" in name
        or name.startswith("python-")
        or "-python-" in name
    )


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


def _extract_build_base_image(args: Sequence[str]) -> bool:
    return any(token == "--base-image" for token in args)


def _extract_build_default_base_image(args: Sequence[str]) -> bool:
    return any(token == "--base-image=default" for token in args)


def _extract_build_base_image_output(args: Sequence[str]) -> str | None:
    for index, token in enumerate(args):
        if token == "--base-image-output":
            if index + 1 >= len(args):
                raise ValueError("Missing value for --base-image-output")
            return args[index + 1]
        if token.startswith("--base-image-output="):
            return token.split("=", 1)[1]
    return None


def _read_pyproject_build_base_image(project_dir: Path) -> str | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return None
    build = pyronaut.get("build")
    if not isinstance(build, dict):
        return None
    return _read_pyproject_string(build, "base-image")


def _is_python_runtime_project(project_dir: Path) -> bool:
    if not (project_dir / "pyproject.toml").is_file() or _is_external_build_project(project_dir):
        return False
    # An explicitly configured Java source layout is authoritative. Direct
    # Java builds may still resolve Micronaut's Python runtime transitively,
    # which must not make the launcher/base-image selection Python-specific.
    sources = _read_pyproject_sources(project_dir)
    if sources.java_source_dir != _DEFAULT_JAVA_SOURCE_DIR and sources.python_source_dir == _DEFAULT_PYTHON_SOURCE_DIR:
        return False
    python_root = project_dir / sources.python_source_dir
    java_root = project_dir / sources.java_source_dir
    # A Java source tree is authoritative even when the runtime classpath also
    # contains Micronaut's optional Python support (which is bundled by the
    # native development launcher for compiler/editor services).
    if java_root.is_dir() and any(p.suffix == ".java" for p in java_root.rglob("*.java")):
        return False
    if any(project_dir.glob("*.py")) and not any(project_dir.glob("*.java")):
        return True
    if python_root.is_dir() and any(p.suffix == ".py" for p in python_root.rglob("*.py")):
        if not java_root.is_dir() or not any(p.suffix == ".java" for p in java_root.rglob("*.java")):
            return True
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    if runtime_manifest.is_file():
        with contextlib.suppress(OSError):
            if "micronaut-context-python" in runtime_manifest.read_text(encoding="utf-8"):
                return True
    return False


def _configured_local_base_image(project_dir: Path) -> Path | None:
    configured = _read_pyproject_build_base_image(project_dir)
    if configured is not None:
        if configured.strip().lower() == "default":
            return _bundled_default_base_image(project_dir)
        path = Path(configured)
        return path if path.is_absolute() else (project_dir / path).resolve()
    launcher = "pyronaut-run-python" if _is_python_runtime_project(project_dir) else "pyronaut-run"
    default_base = project_dir / "__pyronaut__" / "native" / "base" / launcher
    return default_base if default_base.is_file() else None


def _bundled_default_base_image(
    project_dir: Path,
    *,
    platform_name: str | None = None,
) -> Path | None:
    launcher = PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]
    executable = _bundled_native_executable(launcher)
    # An explicit platform is used for Docker targets and must not accidentally
    # reuse a host-native executable from a source checkout.
    if platform_name is None and executable is not None and executable.is_file():
        return executable
    return _ensure_native_image(launcher, platform_name=platform_name)


def _native_user_package_args(project_dir: Path) -> list[str]:
    classes_dir = project_dir / "__pyronaut__" / "classes"
    if not classes_dir.is_dir():
        return []
    packages = {
        ".".join(class_file.relative_to(classes_dir).parent.parts)
        for class_file in classes_dir.rglob("*.class")
        if class_file.parent != classes_dir
    }
    return [argument for package in sorted(packages) if package for argument in ("--user-package", package)]


def _docker_base_marker(project_dir: Path) -> Path:
    return project_dir / "__pyronaut__" / "native" / "base" / "docker-image"


def _configured_docker_base_image(project_dir: Path, docker_config: dict[str, str]) -> str | None:
    configured = docker_config.get("base_image")
    if configured:
        return configured
    marker = _docker_base_marker(project_dir)
    if marker.is_file():
        value = marker.read_text(encoding="utf-8").strip()
        return value or None
    return None


def _record_docker_base_image(project_dir: Path, image_name: str) -> None:
    marker = _docker_base_marker(project_dir)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(image_name + "\n", encoding="utf-8")


def _resolve_base_image_output(project_dir: Path, args: Sequence[str]) -> Path:
    configured = _extract_build_base_image_output(args) or _read_pyproject_build_base_image(project_dir)
    if configured is None:
        launcher = "pyronaut-run-python" if _is_python_runtime_project(project_dir) else "pyronaut-run"
        return project_dir / "__pyronaut__" / "native" / "base" / launcher
    path = Path(configured)
    return path if path.is_absolute() else (project_dir / path).resolve()


def _run_native_base_image_build(
    *,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    project_dir: Path,
    verbose: bool,
    java_home_provider: JavaHomeProvider | None,
) -> int:
    try:
        env = _build_non_test_resources_env("build", java_home_provider)
        _build_native_classpath(project_dir)
        output = _resolve_base_image_output(project_dir, args)
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    output.parent.mkdir(parents=True, exist_ok=True)
    delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
    if delegate_executable is None:
        print(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}", file=sys.stderr)
        return PRECONDITION_FAILED
    command = [
        delegate_executable,
        "--project-dir", str(project_dir),
        "--output", str(output),
        "--base-image",
    ]
    if _is_python_runtime_project(project_dir):
        command.append("--include-python")
    if verbose:
        command.append("--verbose")
    command.extend(_extract_native_build_passthrough_args(args))
    if _delegation_trace_enabled():
        print(shlex.join(command), file=sys.stderr)
    exit_code = runner(command, env)
    if exit_code == SUCCESS and not output.is_file():
        print(f"Base image build reported success but no binary was produced at: {output}", file=sys.stderr)
        return PRECONDITION_FAILED
    if exit_code == SUCCESS:
        print(f"Base image build complete: {output}")
    return exit_code


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
        "base_image": ("base-image", "baseImage"),
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
    # Wheel distributions place common launcher libraries beside each tool
    # under ``tools/shared``. Keep that sibling available in Docker contexts
    # so generated launchers can resolve ``../shared/lib``.
    shared_root = install_root.parent / "shared"
    if shared_root.is_dir():
        shutil.copytree(shared_root, target_dir.parent / "shared", dirs_exist_ok=True)
    return target_dir / "bin" / resolved.name


def _prepare_common_docker_app_context(project_dir: Path, context_dir: Path) -> Path:
    app_dir = context_dir / "app"
    pyronaut_dir = app_dir / "__pyronaut__"
    pyronaut_dir.mkdir(parents=True, exist_ok=True)
    (app_dir / "config").mkdir(parents=True, exist_ok=True)
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
) -> str:
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
        exclude_python=not _is_python_runtime_project(project_dir),
    )
    runner_name = _delegate_executable_name("run", project_dir)
    delegate_executable = resolver(runner_name)
    if delegate_executable is None:
        raise RuntimeError(f"Missing delegated executable: {runner_name}")
    _stage_delegate_distribution(delegate_executable, pyronaut_dir / "tools" / runner_name)
    return runner_name


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
    _copytree_if_exists(project_dir / "__pyronaut__" / "schemas", pyronaut_dir / "schemas")
    (pyronaut_dir / "schemas").mkdir(parents=True, exist_ok=True)
    delegate_executable = resolver(NATIVE_BUILD_EXECUTABLE)
    if delegate_executable is None:
        raise RuntimeError(f"Missing delegated executable: {NATIVE_BUILD_EXECUTABLE}")
    _stage_manifest_artifacts(
        source=runtime_manifest,
        target=pyronaut_dir / "resolved-runtime-dependencies",
        project_dir=project_dir,
        pyronaut_dir=pyronaut_dir,
        exclude_python=not _is_python_runtime_project(project_dir),
    )
    if _is_python_runtime_project(project_dir):
        _stage_python_runner_runtime_dependencies(pyronaut_dir, resolver)
    _stage_delegate_distribution(delegate_executable, pyronaut_dir / "tools" / "pyronaut-native-build")


def _prepare_bundled_docker_context(*, project_dir: Path, context_dir: Path) -> None:
    """Stage only application material for a prebuilt default runner image."""
    app_dir = _prepare_common_docker_app_context(project_dir, context_dir)
    pyronaut_dir = app_dir / "__pyronaut__"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
    _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
    _copytree_if_exists(project_dir / "__pyronaut__" / "schemas", pyronaut_dir / "schemas")
    (pyronaut_dir / "schemas").mkdir(parents=True, exist_ok=True)


def _stage_python_runner_runtime_dependencies(
    pyronaut_dir: Path,
    resolver: Callable[[str], str | None],
) -> None:
    """Keep Python runner support libraries available to PyronautRunMain."""
    runner = resolver(PYTHON_RUN_EXECUTABLE)
    if runner is None:
        raise RuntimeError(f"Missing delegated executable: {PYTHON_RUN_EXECUTABLE}")
    # Test and embedding callers may provide a logical delegate path without
    # an installed distribution. In that case there is nothing to stage; the
    # normal installed-wheel path supplies a real executable and is staged.
    if not Path(runner).exists():
        return
    manifest = pyronaut_dir / "resolved-runtime-dependencies"
    entries = manifest.read_text(encoding="utf-8").splitlines() if manifest.exists() else []
    for source in _delegate_lib_entries(runner):
        jar = Path(source)
        if jar.suffix != ".jar":
            continue
        target = pyronaut_dir / "m2-repository" / jar.name
        shutil.copy2(jar, target)
        relative = Path("__pyronaut__") / "m2-repository" / jar.name
        if str(relative) not in entries:
            entries.append(str(relative))
    manifest.write_text("".join(f"{entry}\n" for entry in entries), encoding="utf-8")


def _stage_runner_runtime_dependencies(pyronaut_dir: Path, runner: str) -> None:
    """Stage the selected production runner jars for JVM wheel execution."""
    if not Path(runner).exists():
        return
    manifest = pyronaut_dir / "resolved-runtime-dependencies"
    entries = manifest.read_text(encoding="utf-8").splitlines() if manifest.exists() else []
    for source in _delegate_lib_entries(runner):
        jar = Path(source)
        if jar.suffix != ".jar":
            continue
        target = pyronaut_dir / "m2-repository" / jar.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(jar, target)
        relative = Path("__pyronaut__") / "m2-repository" / jar.name
        if str(relative) not in entries:
            entries.append(str(relative))
    manifest.write_text("".join(f"{entry}\n" for entry in entries), encoding="utf-8")


def _prepare_crema_native_docker_context(
    *, project_dir: Path, context_dir: Path, resolver: Callable[[str], str | None]
) -> None:
    app_dir = _prepare_common_docker_app_context(project_dir, context_dir)
    pyronaut_dir = app_dir / "__pyronaut__"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
    _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
    _copytree_if_exists(project_dir / "__pyronaut__" / "schemas", pyronaut_dir / "schemas")
    (pyronaut_dir / "schemas").mkdir(parents=True, exist_ok=True)
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    if runtime_manifest.exists():
        _stage_manifest_artifacts(
            source=runtime_manifest,
            target=pyronaut_dir / "resolved-runtime-dependencies",
            project_dir=project_dir,
            pyronaut_dir=pyronaut_dir,
            exclude_python=not _is_python_runtime_project(project_dir),
            launcher_executable=resolver(NATIVE_BUILD_EXECUTABLE),
        )


def _manifest_docker_copy_lines(context_dir: Path, *, destination_root: str = "/app") -> list[str]:
    manifest = context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies"
    lines = [
        f"COPY app/__pyronaut__/resolved-runtime-dependencies {destination_root}/__pyronaut__/resolved-runtime-dependencies",
    ]
    if not manifest.is_file():
        return lines
    for entry in manifest.read_text(encoding="utf-8").splitlines():
        value = entry.strip()
        if not value or Path(value).is_absolute():
            continue
        source = context_dir / "app" / value
        if source.is_file():
            lines.append(f"COPY app/{value} {destination_root}/{value}")
    return lines


def _write_jvm_dockerfile(*, target: Path, base_image: str, runner_name: str,
                          runtime_copies: Sequence[str]) -> None:
    dockerfile = f"""\
FROM {base_image}
WORKDIR /app
COPY app/config/ /app/config/
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/tools/shared /app/__pyronaut__/tools/shared
COPY app/__pyronaut__/tools/{runner_name} /app/__pyronaut__/tools/{runner_name}
{chr(10).join(runtime_copies)}
ENTRYPOINT ["/app/__pyronaut__/tools/{runner_name}/bin/{runner_name}", "--project-dir", "/app"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _write_native_dockerfile(
    *,
    target: Path,
    builder_image: str,
    runtime_image: str,
    project_name: str,
    main_class: str,
    include_python: bool,
    verbose: bool,
    static_native: bool,
    passthrough_args: Sequence[str],
    runtime_copies: Sequence[str],
) -> None:
    output_binary = f"/workspace/app/__pyronaut__/native/{project_name}"
    build_command = [
        "/workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build",
        "--project-dir",
        "/workspace/app",
        "--output",
        output_binary,
    ]
    if verbose:
        build_command.append("--verbose")
    if include_python:
        build_command.append("--include-python")
    build_command.extend(passthrough_args)
    if static_native:
        build_command.extend(["--static", "--libc=musl"])
    builder_runtime_copies = "\n".join(line.replace(" /app/", " /workspace/app/") for line in runtime_copies)
    dockerfile = f"""\
FROM {builder_image} AS builder
WORKDIR /workspace
COPY app/pyproject.toml /workspace/app/pyproject.toml
COPY app/config/ /workspace/app/config/
COPY app/__pyronaut__/classes /workspace/app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /workspace/app/__pyronaut__/schemas
COPY app/__pyronaut__/tools/shared /workspace/app/__pyronaut__/tools/shared
COPY app/__pyronaut__/tools/pyronaut-native-build /workspace/app/__pyronaut__/tools/pyronaut-native-build
{builder_runtime_copies}
RUN chmod +x /workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build
RUN {shlex.join(build_command)}

FROM {runtime_image}
WORKDIR /app
COPY --from=builder {output_binary} /app/{project_name}
COPY app/pyproject.toml /app/pyproject.toml
COPY app/config/ /app/config/
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT ["/app/{project_name}", "--project-dir", "/app"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _write_crema_base_dockerfile(
    *,
    target: Path,
    builder_image: str,
    runtime_image: str,
    runner_name: str,
    include_python: bool,
    verbose: bool,
    static_native: bool,
    passthrough_args: Sequence[str],
    bundled_only: bool = False,
) -> None:
    output_binary = f"/workspace/base/{runner_name}"
    build_command = [
        "/workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build",
        "--project-dir", "/workspace/app",
        "--output", output_binary,
        "--base-image",
    ]
    if bundled_only:
        build_command.append("--default-base-image")
    if include_python:
        build_command.append("--include-python")
    if verbose:
        build_command.append("--verbose")
    build_command.extend(passthrough_args)
    if static_native:
        build_command.extend(["--static", "--libc=musl"])
    dockerfile = f"""\\
FROM {builder_image} AS builder
WORKDIR /workspace
COPY app/ /workspace/app/
RUN chmod +x /workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build
RUN {shlex.join(build_command)}

FROM {runtime_image} AS pyronaut-base
WORKDIR /opt/pyronaut
COPY --from=builder {output_binary} /opt/pyronaut/bin/{runner_name}

FROM pyronaut-base
WORKDIR /app
COPY app/config/ /app/config/
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT ["/opt/pyronaut/bin/{runner_name}", "--project-dir", "/app"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _write_bundled_application_dockerfile(
    *, target: Path, runtime_image: str, runner_name: str,
) -> None:
    """Build the application layer directly on top of a bundled runner."""
    target.write_text(
        f"""FROM {runtime_image} AS pyronaut-base
WORKDIR /opt/pyronaut
COPY bundled-base/{runner_name} /opt/pyronaut/bin/{runner_name}

FROM pyronaut-base
WORKDIR /app
COPY app/config/ /app/config/
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT [\"/opt/pyronaut/bin/{runner_name}\", \"--project-dir\", \"/app\"]
""",
        encoding="utf-8",
    )


def _write_crema_application_dockerfile(*, target: Path, base_image: str, runner_name: str) -> None:
    dockerfile = f"""\\
FROM {base_image}
WORKDIR /app
# Older Crema base images may contain the native-build distribution under the
# application directory. It is only needed while producing the image, never
# at runtime, so remove it from the final application layer.
RUN rm -rf /app/__pyronaut__/tools
COPY app/config/ /app/config/
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT ["/opt/pyronaut/bin/{runner_name}", "--project-dir", "/app"]
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
    target: str | None = None,
) -> list[str]:
    docker_executable = shutil.which("docker") or "docker"
    command = [docker_executable, "build"]
    if verbose:
        command.append("--progress=plain")
    for key, value in build_args.items():
        command.extend(["--build-arg", f"{key}={value}"])
    if target is not None:
        command.extend(["--target", target])
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
    base_image_build: bool = False,
    default_base_image: bool = False,
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
        for name in ("HTTP_PROXY", "HTTPS_PROXY", "FTP_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "ftp_proxy", "no_proxy"):
            if value := os.environ.get(name):
                build_args[name] = value
        if mode == "native":
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
            runner_name = PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else "pyronaut-run"
            if base_image_build:
                base_image = docker_config.get("base_image") or f"{image_name}:{project_version}-native-base"
                build_args["PYRONAUT_BASE_IMAGE"] = base_image
                dockerfile = context_dir / "DockerfileNativeBase"
                _prepare_native_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
                _write_crema_base_dockerfile(
                    target=dockerfile,
                    builder_image=builder_image,
                    runtime_image=runtime_image,
                    runner_name=runner_name,
                    include_python=_is_python_runtime_project(project_dir),
                    verbose=verbose,
                    static_native=static_native,
                    passthrough_args=_extract_native_build_passthrough_args(args),
                    bundled_only=False,
                )
                base_command = _build_docker_command(
                    dockerfile=dockerfile,
                    image_tag=base_image,
                    context_dir=context_dir,
                    verbose=verbose,
                    build_args=build_args,
                    target="pyronaut-base",
                )
                if _delegation_trace_enabled():
                    print(shlex.join(base_command), file=sys.stderr)
                base_exit = runner(base_command, None)
                if base_exit != SUCCESS:
                    return base_exit
                _record_docker_base_image(project_dir, base_image)
            elif default_base_image:
                # Crema base images are Linux containers even when the CLI is
                # running on macOS, so resolve the Linux bundle explicitly.
                try:
                    bundled = _bundled_default_base_image(project_dir, platform_name="linux")
                except RuntimeError as exc:
                    print(str(exc), file=sys.stderr)
                    return PRECONDITION_FAILED
                if bundled is None or not bundled.is_file():
                    print("The default native base image is not available for this platform.", file=sys.stderr)
                    return PRECONDITION_FAILED
                _prepare_bundled_docker_context(project_dir=project_dir, context_dir=context_dir)
                bundled_dir = context_dir / "bundled-base"
                bundled_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(bundled, bundled_dir / runner_name)
                (bundled_dir / runner_name).chmod(0o755)
                dockerfile = context_dir / "DockerfileNativeDefault"
                _write_bundled_application_dockerfile(
                    target=dockerfile,
                    runtime_image=runtime_image,
                    runner_name=runner_name,
                )
            elif (base_image := _configured_docker_base_image(project_dir, docker_config)) is not None:
                _prepare_crema_native_docker_context(
                    project_dir=project_dir, context_dir=context_dir, resolver=resolver
                )
                build_args["PYRONAUT_BASE_IMAGE"] = base_image
                custom = _resolve_build_dockerfile(
                    project_dir=project_dir,
                    configured_path=docker_config.get("dockerfile_native"),
                    default_name="DockerfileNative",
                )
                if custom is not None:
                    dockerfile = _copy_dockerfile_into_context(custom, context_dir)
                else:
                    dockerfile = context_dir / "DockerfileNativeCrema"
                    _write_crema_application_dockerfile(
                        target=dockerfile,
                        base_image=base_image,
                        runner_name=runner_name,
                    )
            else:
                _prepare_native_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
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
                        include_python=_is_python_runtime_project(project_dir),
                        verbose=verbose,
                        static_native=static_native,
                        passthrough_args=[*_extract_native_build_passthrough_args(args), *_native_user_package_args(project_dir)],
                        runtime_copies=_manifest_docker_copy_lines(context_dir),
                    )
        else:
            runner_name = _prepare_jvm_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
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
                    runner_name=runner_name,
                    runtime_copies=_manifest_docker_copy_lines(context_dir),
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
RUNTIME_MANIFEST = PROJECT_DIR / "__pyronaut__" / "resolved-runtime-dependencies"
CLASSES_DIR = PROJECT_DIR / "__pyronaut__" / "classes"


def main() -> None:
    try:
        from pyronaut_cli_v2 import cli as pyronaut_cli
    except Exception as exc:
        raise SystemExit("Pyronaut runtime is required to launch this JVM build: " + str(exc))

    if not CLASSES_DIR.is_dir():
        raise SystemExit(f"Missing compiled application classes: {{CLASSES_DIR}}")
    if not RUNTIME_MANIFEST.is_file():
        raise SystemExit(f"Missing runtime classpath manifest: {{RUNTIME_MANIFEST}}")
    entries = [str(CLASSES_DIR)]
    for value in RUNTIME_MANIFEST.read_text(encoding="utf-8").splitlines():
        value = value.strip()
        if not value:
            continue
        path = Path(value)
        resolved = path if path.is_absolute() else PROJECT_DIR / path
        if resolved.exists():
            entries.append(str(resolved))
    java_home = pyronaut_cli._ensure_graalvm_java_home(PROJECT_DIR)
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    java = pyronaut_cli._resolve_java_executable(env)
    command_line = [java, "-cp", os.pathsep.join(dict.fromkeys(entries)), MAIN_CLASS,
                    "--project-dir", str(PROJECT_DIR), *sys.argv[1:]]
    raise SystemExit(subprocess.run(command_line, env=env).returncode)
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
    raise SystemExit(subprocess.run([str(BINARY), "--project-dir", str(PROJECT_DIR), *sys.argv[1:]]).returncode)
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
    return _delegate(
        "validate-config",
        args,
        runner,
        resolver,
        env_overrides=env_overrides,
    )


def _is_external_build_project(project_dir: Path) -> bool:
    return (project_dir / "pom.xml").is_file() or any(
        (project_dir / name).is_file()
        for name in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
    )


def _default_dev_source(args: Sequence[str]) -> str | None:
    if _extract_project_dir(args) != ".":
        return None
    project_dir = Path.cwd()
    if (project_dir / "pyproject.toml").is_file() or _is_external_build_project(project_dir):
        return None
    main_source = project_dir / "main.py"
    return main_source.name if main_source.is_file() else None


def _resolve_build_mode(project_dir: Path, args: Sequence[str]) -> str:
    explicit = _extract_build_mode_flag(args)
    if explicit is not None:
        return explicit

    configured = _read_pyproject_build_mode(project_dir)
    if configured is not None:
        return configured
    return _read_pyproject_toolchain_type(project_dir)


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
    if _is_external_build_project(project_dir):
        # External Maven/Gradle builds may configure non-standard resource roots.
        # The install layout is the authoritative list used by processing and
        # validation, so include those roots in the dev restart snapshot too.
        layout_file = project_dir / "__pyronaut__" / "project-layout.properties"
        if layout_file.is_file():
            values: dict[str, str] = {}
            try:
                for line in layout_file.read_text(encoding="utf-8").splitlines():
                    if "=" in line and not line.lstrip().startswith("#"):
                        key, value = line.split("=", 1)
                        values[key.strip()] = value.strip()
                main = values.get("mainResources")
                test = values.get("testResources")
                if main or test:
                    return _ProjectLayout(
                        java_source_dir="src/main/java",
                        java_test_dir="src/test/java",
                        resources_dir=main or "src/main/resources",
                        test_resources_dir=test or "src/test/resources",
                    )
            except OSError:
                pass
        return _ProjectLayout(
            java_source_dir="src/main/java",
            java_test_dir="src/test/java",
            resources_dir="src/main/resources",
            test_resources_dir="src/test/resources",
        )
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
        return _default_toolchain_spec()
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return _default_toolchain_spec()
    toolchain = pyronaut.get("toolchain")
    if not isinstance(toolchain, dict):
        return _default_toolchain_spec()

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
    if not explicit:
        return _default_toolchain_spec()
    return _ToolchainSpec(distribution, version, java_version, release_tag, download_url, explicit)


def _default_toolchain_spec() -> _ToolchainSpec:
    return _packaged_toolchain_spec() or _ToolchainSpec(None, None, _GRAALVM_MIN_JDK_MAJOR)


def _packaged_toolchain_spec() -> _ToolchainSpec | None:
    defaults_file = Path(__file__).with_name(_PACKAGED_TOOLCHAIN_DEFAULTS)
    if not defaults_file.is_file():
        return None

    values: dict[str, str] = {}
    for line in defaults_file.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()

    distribution_raw = values.get("distribution")
    distribution = _normalize_toolchain_distribution(distribution_raw) if distribution_raw else None
    version = values.get("version") or None
    release_tag = values.get("release-tag") or None
    download_url = values.get("download-url") or None
    java_version = _GRAALVM_MIN_JDK_MAJOR
    java_version_raw = values.get("java-version")
    if java_version_raw:
        try:
            java_version = int(java_version_raw)
        except ValueError as exc:
            raise ValueError(f"Invalid packaged GraalVM toolchain java-version: {java_version_raw}") from exc

    explicit = any(value is not None for value in (distribution, version, release_tag, download_url))
    if not explicit:
        return None
    return _ToolchainSpec(distribution, version, java_version, release_tag, download_url, True)


def _read_pyproject_toolchain_type(project_dir: Path) -> str:
    return _toolchain_type_from_pyronaut_table(_read_pyproject_pyronaut_table(project_dir))


def _toolchain_type_from_pyronaut_table(pyronaut: dict[str, object] | None) -> str:
    if not isinstance(pyronaut, dict):
        return TOOLCHAIN_TYPE_JVM
    toolchain = pyronaut.get("toolchain")
    if not isinstance(toolchain, dict):
        return TOOLCHAIN_TYPE_JVM
    type_raw = toolchain.get("type")
    if type_raw is None:
        return TOOLCHAIN_TYPE_JVM
    if not isinstance(type_raw, str):
        raise ValueError("Invalid toolchain type in pyproject.toml. Use tool.pyronaut.toolchain.type = 'jvm' or 'native'")
    normalized = type_raw.strip().lower()
    if normalized in {TOOLCHAIN_TYPE_JVM, TOOLCHAIN_TYPE_NATIVE}:
        return normalized
    raise ValueError("Invalid toolchain type in pyproject.toml. Use tool.pyronaut.toolchain.type = 'jvm' or 'native'")


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
    configured = _read_pyproject_processor_mode_override(project_dir)
    return configured or TOOLCHAIN_TYPE_JVM


def _read_pyproject_processor_mode_override(project_dir: Path) -> str | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return None
    processor = pyronaut.get("processor")
    if not isinstance(processor, dict):
        return None
    mode = processor.get("mode")
    if mode is None:
        return None
    if not isinstance(mode, str):
        raise ValueError("Invalid processor mode in pyproject.toml. Use tool.pyronaut.processor.mode = 'jvm' or 'native'")
    normalized = mode.strip().lower()
    if normalized in {TOOLCHAIN_TYPE_JVM, TOOLCHAIN_TYPE_NATIVE}:
        return normalized
    raise ValueError("Invalid processor mode in pyproject.toml. Use tool.pyronaut.processor.mode = 'jvm' or 'native'")


def _read_pyproject_test_mode(project_dir: Path) -> str:
    configured = _read_pyproject_test_mode_override(project_dir)
    return configured or TOOLCHAIN_TYPE_JVM


def _read_pyproject_test_mode_override(project_dir: Path) -> str | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return None
    test = pyronaut.get("test")
    if not isinstance(test, dict):
        return None
    mode = test.get("mode")
    if mode is None:
        return None
    if not isinstance(mode, str):
        raise ValueError("Invalid test mode in pyproject.toml. Use tool.pyronaut.test.mode = 'jvm' or 'native'")
    normalized = mode.strip().lower()
    if normalized in {TOOLCHAIN_TYPE_JVM, TOOLCHAIN_TYPE_NATIVE}:
        return normalized
    raise ValueError("Invalid test mode in pyproject.toml. Use tool.pyronaut.test.mode = 'jvm' or 'native'")


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
        # Server settings use short names for connection details but retain the
        # full Micronaut property name for the project identity.
        value = settings.get(settings_key) or settings.get(property_name)
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

    venv_bin = venv_dir / "bin"
    venv_python = _resolve_virtualenv_python(venv_bin) if venv_bin.is_dir() else None
    if venv_python is None:
        # Do not poison the delegated process with a stale/broken virtualenv.
        # This commonly occurs when the pyenv installation used to create the
        # venv has since been replaced.
        fallback = dict(os.environ if env is None else env)
        fallback.pop("PYRONAUT_PYTHON_EXECUTABLE", None)
        fallback.pop("PYTHONHOME", None)
        return fallback

    activated = dict(os.environ if env is None else env)
    activated["VIRTUAL_ENV"] = str(venv_dir)
    activated.pop("PYTHONHOME", None)
    activated["PATH"] = _prepend_path_entry(activated.get("PATH", ""), str(venv_bin))
    activated["PYRONAUT_PYTHON_EXECUTABLE"] = str(venv_python)
    return activated


def _prepend_path_entry(path_value: str, entry: str) -> str:
    entries = [value for value in path_value.split(os.pathsep) if value]
    entries = [value for value in entries if value != entry]
    return os.pathsep.join([entry, *entries])


def _resolve_virtualenv_python(venv_bin: Path) -> Path | None:
    for name in ("python", "python3"):
        candidate = venv_bin / name
        if candidate.is_file():
            return candidate
    return None


def _is_compatible_virtualenv(venv_python: Path) -> bool:
    """Whether a project venv can provide packages to the embedded Python runtime."""
    if getattr(sys.implementation, "name", "") != "graalpy":
        return True
    try:
        resolved = venv_python.resolve(strict=True)
    except OSError:
        return False
    return "graalpy" in str(resolved).lower()


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


def _has_main_class_option(args: Sequence[str]) -> bool:
    return any(token == "--main-class" or token.startswith("--main-class=") for token in args)


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
        if token in {"--native", "--jvm", "--verbose", "--no-cache", "--no-validate", "--offline", "--docker", "--static", "--base-image", "--base-image=default"}:
            index += 1
            continue
        if token in {"--mode", "--main-class", "--project-dir", "--base-image-output", "--local-repository", "--local-repo", "--setup", "--name", "--version", "--python-src", "--java-src"}:
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
            or token.startswith("--base-image-output=")
            or token.startswith("--local-repository=")
            or token.startswith("--local-repo=")
            or token.startswith("--setup=")
            or token.startswith("--name=")
            or token.startswith("--version=")
            or token.startswith("--python-src=")
            or token.startswith("--java-src=")
        ):
            index += 1
            continue
        # Direct-source staging can leave the generated project path as a
        # positional token after option normalization. It is not a native-
        # image argument and must never be interpreted as the main class.
        if token.startswith("/") and Path(token).exists():
            index += 1
            continue
        passthrough.append(token)
        index += 1
    return passthrough


def _build_native_classpath(project_dir: Path) -> str:
    output_dir = _pyronaut_output_dir(project_dir)
    runtime_manifest = output_dir / "resolved-runtime-dependencies"
    classes_dir = output_dir / "classes"
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
    process_pass: str | None = None,
) -> int:
    validate_on_restart = True
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
            preflight_code = _run_preflight(
                str(project_root),
                no_cache,
                local_repository,
                execute,
                resolver,
                install=False,
                process_pass=process_pass,
            )
            if preflight_code != SUCCESS:
                return preflight_code

        try:
            command_line, env = _build_dev_delegate_invocation(
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
                        if validate_on_restart:
                            validation_code = _run_lifecycle_validation(
                                project_dir=str(project_root), scenario="dev",
                                runner=execute, resolver=resolver,
                                no_cache=no_cache, env_overrides=env_overrides,
                            )
                            if validation_code != SUCCESS:
                                refresh_code = validation_code
                                return
                        refresh_code = _run_preflight(
                            str(project_root),
                            no_cache,
                            local_repository,
                            execute,
                            resolver,
                            install=False,
                            process_pass=process_pass,
                        )
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
                    print(f"Failed to refresh artifacts for restart: {refresh_exception}", file=sys.stderr)
                    return INTERNAL_ERROR
                if refresh_code is None:
                    print("Failed to refresh artifacts for restart.", file=sys.stderr)
                    return INTERNAL_ERROR
                if refresh_code != SUCCESS:
                    print(f"Failed to refresh artifacts for restart (exit code {refresh_code}).", file=sys.stderr)
                    return int(refresh_code)
                snapshot = next_snapshot
                # The refresh worker already completed processing for the next
                # application launch; do not run the same preflight again at
                # the top of the restart loop.
                initial_preflight_done = True
                break
        except KeyboardInterrupt:
            _stop_managed_process(process)
            return 130


def _run_test_cycle(
    *,
    project_dir: Path,
    delegated_args: Sequence[str],
    no_cache: bool,
    execute: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    debug_vm: bool,
    tr_session: _OwnedTestResourcesSession | None,
    test_resources_env_overrides: dict[str, str] | None,
    no_validate: bool,
    java_home_provider: JavaHomeProvider | None,
    local_repository: str | None,
) -> tuple[int, dict[str, str] | None]:
    if _is_external_build_project(project_dir):
        preflight_code = _run_preflight(
            str(project_dir),
            no_cache,
            local_repository,
            execute,
            resolver,
            install=not (_pyronaut_output_dir(project_dir) / "project-layout.properties").exists(),
            process_pass="all",
        )
        if preflight_code != SUCCESS:
            return preflight_code, test_resources_env_overrides
    # The validator must see the newly started owned server. Starting it only
    # after validation leaves a stale URI from the previous dev session.
    if tr_session is not None and test_resources_env_overrides is None:
        tr_session.ensure_started(runner=execute, resolver=resolver, java_home_provider=java_home_provider)
        test_resources_env_overrides = tr_session.client_env_overrides()
    if no_validate:
        sys.stderr.write("[validation] skipped (--no-validate)\n")
    else:
        validation_code = _run_lifecycle_validation(
            project_dir=str(project_dir),
            scenario="test",
            runner=execute,
            resolver=resolver,
            no_cache=no_cache,
            env_overrides=test_resources_env_overrides,
        )
        if validation_code != SUCCESS:
            return validation_code, test_resources_env_overrides

    if not _is_external_build_project(project_dir):
        preflight_code = _run_preflight(
            str(project_dir),
            no_cache,
            local_repository,
            execute,
            resolver,
            install=False,
            process_pass="all",
        )
        if preflight_code != SUCCESS:
            return preflight_code, test_resources_env_overrides

    return (
        _delegate(
            "test",
            delegated_args,
            execute,
            resolver,
            debug_vm=debug_vm,
            env_overrides=test_resources_env_overrides,
            java_home_provider=java_home_provider,
        ),
        test_resources_env_overrides,
    )


def _run_test_continuously(
    *,
    project_dir: Path,
    delegated_args: Sequence[str],
    no_cache: bool,
    execute: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    debug_vm: bool,
    tr_session: _OwnedTestResourcesSession | None,
    test_resources_env_overrides: dict[str, str] | None,
    no_validate: bool,
    watch_poll_interval: float,
    watch_debounce_seconds: float,
    snapshotter: Callable[[Path], tuple[tuple[str, int, int], ...]],
    monotonic: Callable[[], float],
    sleep: Callable[[float], None],
    input_reader: Callable[[float | None], str | None] | None,
    java_home_provider: JavaHomeProvider | None,
    local_repository: str | None,
) -> int:
    project_root = project_dir.resolve()
    snapshot = snapshotter(project_root)
    watch_mode = False

    try:
        input_context = _continuous_input_context() if input_reader is None else _inert_input_context(input_reader)
        with input_context as (read_key, interactive):
            def _wait_for_watch_trigger() -> bool:
                nonlocal snapshot
                changed_at: float | None = None
                while True:
                    key = read_key(watch_poll_interval)
                    if key == "q":
                        return False
                    if key == "w":
                        continue
                    if key == " ":
                        return True
                    if key is not None:
                        continue

                    next_snapshot = snapshotter(project_root)
                    if next_snapshot == snapshot:
                        changed_at = None
                        continue

                    now = monotonic()
                    if changed_at is None:
                        changed_at = now
                        continue
                    if now - changed_at < watch_debounce_seconds:
                        continue
                    snapshot = next_snapshot
                    return True

            while True:
                exit_code, test_resources_env_overrides = _run_test_cycle(
                    project_dir=project_root,
                    delegated_args=delegated_args,
                    no_cache=no_cache,
                    execute=execute,
                    resolver=resolver,
                    debug_vm=debug_vm,
                    tr_session=tr_session,
                    test_resources_env_overrides=test_resources_env_overrides,
                    no_validate=no_validate,
                    java_home_provider=java_home_provider,
                    local_repository=local_repository,
                )
                snapshot = snapshotter(project_root)
                _print_continuous_test_banner()
                if not interactive:
                    return exit_code

                if watch_mode:
                    if not _wait_for_watch_trigger():
                        return SUCCESS
                    continue

                while True:
                    key = read_key(None)
                    if key == "q":
                        return SUCCESS
                    if key == "w":
                        watch_mode = True
                        if not _wait_for_watch_trigger():
                            return SUCCESS
                        break
                    if key == " ":
                        break
                    if key is not None:
                        continue
    except KeyboardInterrupt:
        return 130


def _print_continuous_test_banner() -> None:
    print("--------------------------------------")
    print('Continuous Testing Active. Press SPACE to run again, "w" to watch for changes, or "q" to exit.')


@contextlib.contextmanager
def _inert_input_context(
    input_reader: Callable[[float | None], str | None],
) -> Iterable[tuple[Callable[[float | None], str | None], bool]]:
    yield input_reader, True


@contextlib.contextmanager
def _continuous_input_context() -> Iterable[tuple[Callable[[float | None], str | None], bool]]:
    stream = sys.stdin
    if not hasattr(stream, "isatty") or not stream.isatty() or not hasattr(stream, "fileno"):
        yield (lambda _timeout=None: None, False)
        return

    try:
        import select
        import termios
        import tty
    except ImportError:
        yield (lambda _timeout=None: None, False)
        return

    fd = stream.fileno()
    try:
        original_settings = termios.tcgetattr(fd)
    except Exception:
        yield (lambda _timeout=None: None, False)
        return

    tty.setcbreak(fd)
    try:
        def read_key(timeout: float | None = None) -> str | None:
            try:
                if timeout is None:
                    ready, _, _ = select.select([stream], [], [])
                else:
                    ready, _, _ = select.select([stream], [], [], timeout)
                if not ready:
                    return None
                return stream.read(1) or None
            except (OSError, ValueError):
                return None

        yield read_key, True
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, original_settings)


def _build_dev_delegate_invocation(
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool,
    env_overrides: dict[str, str] | None,
    java_home_provider: JavaHomeProvider | None,
) -> tuple[list[str], dict[str, str]]:
    project_dir = Path(_extract_project_dir(args)).resolve()
    delegate_args, dev_jvm_args = _split_dev_delegate_options(args)
    if _control_panel_requested(project_dir, args) or _control_panel_dependency_declared(project_dir):
        dev_jvm_args.append("-Dmicronaut.control-panel.enabled=true")
    # Convenience options are consumed by the Python orchestrator and must not
    # leak into the native pyronaut-dev command line.
    # Keep the original arguments for toolchain selection so --jvm/--native
    # can override the project configuration. Remove those orchestration-only
    # flags from the native launcher command after selection.
    # CLI convenience flags are consumed above and represented as JVM
    # properties; do not pass them to the native pyronaut-dev parser.
    launch_args = delegate_args
    dev_command_line = _pyronaut_dev_native_command_line(
        "run",
        launch_args,
        resolver,
        classpath_command="dev",
        environment="dev",
        debug_vm=debug_vm,
        env_overrides=env_overrides,
        java_home_provider=java_home_provider,
    )
    if dev_command_line is not None:
        dev_command_line = [value for value in dev_command_line if value not in {"--jvm", "--native"}]
        # The native launcher accepts only its subcommand and source selectors;
        # convenience JVM properties are applied by the Python/fallback path.
        # Do not leak them into native picocli parsing.
        if _control_panel_requested(project_dir, args):
            executable_path = _resolve_pyronaut_dev_native_executable(resolver)
            control_panel = _direct_control_panel_classpath_entries(executable_path)
            if control_panel:
                classpath_property = next((i for i, value in enumerate(dev_command_line) if value.startswith("-Djava.class.path=")), None)
                if classpath_property is not None:
                    existing = dev_command_line[classpath_property].split("=", 1)[1]
                    dev_command_line[classpath_property] = f"-Djava.class.path={os.pathsep.join(dict.fromkeys([existing, *control_panel]))}"
                command_index = dev_command_line.index("run")
                dev_command_line[command_index:command_index] = [
                    "-Dmicronaut.control-panel.enabled=true",
                    "-Dmicronaut.control-panel.path=/control-panel",
                    "-Dmicronaut.control-panel.security.access=ANONYMOUS",
                    f"-Dpyronaut.dev.control.panel.class.path={os.pathsep.join(control_panel)}",
                ]
        env = _build_non_test_resources_env("dev", java_home_provider)
        if dev_jvm_args:
            env["JAVA_TOOL_OPTIONS"] = " ".join(dev_jvm_args)
        env = _merge_env_overrides(env, env_overrides)
        env = _apply_project_virtualenv(env, project_dir)
        return dev_command_line, env
    if _read_pyproject_toolchain_type(project_dir) == TOOLCHAIN_TYPE_NATIVE:
        raise RuntimeError(
            "The project requests the native toolchain, but pyronaut dev produced no native launcher invocation. "
            "Refusing to fall back to the JVM launcher."
        )
    # The JVM fallback uses pyronaut-run, which does not understand dev-only
    # convenience options. Convert those options into JVM properties before
    # constructing its invocation.
    command_line, env = _build_java_delegate_invocation(
        "run",
        delegate_args,
        resolver,
        debug_vm=debug_vm,
        env_overrides=env_overrides,
        java_home_provider=java_home_provider,
    )
    classpath_index = command_line.index("-cp") + 1
    classpath = _build_delegate_classpath("dev", project_dir, resolver)
    # The JVM fallback is executed by the production runner. Do not append the
    # pyronaut-dev distribution: it contains compiler, test, GraalPy and
    # tooling artifacts which can make the application classpath enormous and
    # accidentally expose Python runtime classes to Java projects.
    runner_name = PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]
    runner_executable = resolver(runner_name)
    if runner_executable is not None:
        classpath = os.pathsep.join([classpath, *_delegate_lib_entries(runner_executable)])
    command_line[classpath_index] = classpath
    test_resources_client_classpath = _build_native_test_resources_client_classpath(project_dir)
    if test_resources_client_classpath:
        command_line.insert(classpath_index - 1, f"-Dpyronaut.dev.test.resources.client.classpath={test_resources_client_classpath}")
    for jvm_arg in reversed(_build_test_resources_jvm_args(env_overrides)):
        command_line.insert(command_line.index("-cp"), jvm_arg)
    # Control Panel is opt-in for the JVM fallback. Explicit enablement below
    # is inserted after this default and therefore takes precedence.
    command_line.insert(command_line.index("-cp"), "-Dmicronaut.control-panel.enabled=false")
    if not _has_micronaut_environments_property(args):
        command_line.insert(command_line.index("-cp"), "-Dmicronaut.environments=dev")
    if _control_panel_requested(project_dir, args) or _control_panel_dependency_declared(project_dir):
        for property_name, value in reversed((
            ("micronaut.control-panel.enabled", "true"),
            ("micronaut.control-panel.path", "/control-panel"),
            ("micronaut.control-panel.security.access", "ANONYMOUS"),
        )):
            command_line.insert(command_line.index("-cp"), f"-D{property_name}={value}")
    for jvm_arg in reversed(dev_jvm_args):
        command_line.insert(command_line.index("-cp"), jvm_arg)
    command_line.insert(command_line.index("-cp"), "-Dpyronaut.external.development=true")
    return command_line, env


def _split_dev_delegate_options(args: Sequence[str]) -> tuple[list[str], list[str]]:
    delegated: list[str] = []
    jvm_args: list[str] = []
    index = 0
    while index < len(args):
        value = args[index]
        if value == "--port" and index + 1 < len(args):
            jvm_args.append(f"-Dmicronaut.server.port={args[index + 1]}")
            index += 2
            continue
        if value == "--control-panel":
            jvm_args.append("-Dmicronaut.control-panel.enabled=true")
            jvm_args.append("-Dmicronaut.control-panel.path=/control-panel")
            jvm_args.append("-Dmicronaut.control-panel.security.access=ANONYMOUS")
            index += 1
            continue
        if value.startswith("--port="):
            jvm_args.append(f"-Dmicronaut.server.port={value.removeprefix('--port=')}")
            index += 1
            continue
        if value == "--property" and index + 1 < len(args):
            jvm_args.append(f"-D{args[index + 1]}")
            index += 2
            continue
        if value.startswith("--property="):
            jvm_args.append(f"-D{value.removeprefix('--property=')}")
            index += 1
            continue
        if value.startswith("-D"):
            jvm_args.append(value)
            index += 1
            continue
        delegated.append(value)
        index += 1
    return delegated, jvm_args


def _control_panel_requested(project_dir: Path, args: Sequence[str]) -> bool:
    if "--control-panel" in args:
        return True
    table = _read_pyproject_pyronaut_table(project_dir)
    control_panel = table.get("control-panel") if isinstance(table, dict) else None
    if not isinstance(control_panel, dict):
        return False
    return control_panel.get("enabled") is True


def _control_panel_enabled_for_project(project_dir: Path) -> bool:
    if os.environ.get("PYRONAUT_CONTROL_PANEL_ENABLED", "").lower() == "true":
        return True
    if _control_panel_requested(project_dir, ()):
        return True
    return _control_panel_dependency_declared(project_dir)


def _control_panel_dependency_declared(project_dir: Path) -> bool:
    table = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(table, dict):
        return False
    dependencies = table.get("dependencies")
    if not isinstance(dependencies, dict):
        return False
    return any(
        isinstance(dependency, str) and "control-panel" in dependency.lower().replace("_", "-")
        for values in dependencies.values()
        if isinstance(values, list)
        for dependency in values
    )


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
    project_manifest = project_dir / "pyproject.toml"
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

    if project_manifest.exists():
        try:
            stat = project_manifest.stat()
        except OSError:
            pass
        else:
            entries.append(("pyproject.toml", int(stat.st_mtime_ns), int(stat.st_size)))

    entries.sort(key=lambda item: item[0])
    return tuple(entries)


def _snapshot_direct_source_inputs(args: Sequence[str]) -> tuple[tuple[str, int, int], ...]:
    ignored_dirs = {"__pyronaut__", ".pytest_cache", "build", ".gradle", "__pycache__", ".git"}
    entries: list[tuple[str, int, int]] = []
    seen_files: set[Path] = set()

    index = 0
    while index < len(args):
        token = args[index]
        if token == "--":
            index += 1
            continue
        if token in {"test", "--port", "--property", "--config", "--setup", "--report"}:
            index += 2
            continue
        if token == "--verbose":
            index += 2 if index + 1 < len(args) and not args[index + 1].startswith("-") else 1
            continue
        if token.startswith("--verbose="):
            index += 1
            continue
        if token == "--disable-test-resources":
            index += 1
            continue
        if token.startswith("-D") or token.startswith("--port=") or token.startswith("--property=") or token.startswith("--config=") or token.startswith("--setup=") or token.startswith("--report="):
            index += 1
            continue
        path = Path(token)
        index += 1
        paths = sorted(Path.cwd().glob(token)) if any(marker in token for marker in ("*", "?", "[")) else [path]
        if not paths:
            continue
        for path in paths:
            if path.is_dir():
                for current_root, dirs, files in __import__("os").walk(path):
                    dirs[:] = [name for name in dirs if name not in ignored_dirs and not name.startswith(".")]
                    base = Path(current_root)
                    for file_name in sorted(files):
                        if file_name.startswith("."):
                            continue
                        file_path = base / file_name
                        if file_path in seen_files:
                            continue
                        seen_files.add(file_path)
                        try:
                            stat = file_path.stat()
                        except OSError:
                            continue
                        try:
                            relative = file_path.relative_to(path).as_posix()
                        except ValueError:
                            relative = file_path.as_posix()
                        entries.append((f"{path.as_posix()}/{relative}", int(stat.st_mtime_ns), int(stat.st_size)))
                continue
            if path in seen_files:
                continue
            seen_files.add(path)
            try:
                stat = path.stat()
            except OSError:
                continue
            entries.append((path.as_posix(), int(stat.st_mtime_ns), int(stat.st_size)))

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
    if _read_env("PYRONAUT_DISABLE_AUTO_JAVA_HOME") == "true":
        return None
    return lambda: _ensure_graalvm_java_home(project_dir)


def _build_java_home_env(command: str, java_home_provider: JavaHomeProvider | None) -> dict[str, str] | None:
    if command not in {"dev", "run", "test", "build", "tui", "validate-config", "install", "process"}:
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


def _has_micronaut_environments_property(args: Sequence[str]) -> bool:
    for index, value in enumerate(args):
        if value.startswith("-Dmicronaut.environments="):
            return True
        if value.startswith("--property=micronaut.environments="):
            return True
        if value == "--property" and index + 1 < len(args) and args[index + 1].startswith("micronaut.environments="):
            return True
    return False


def _default_environment(command: str, args: Sequence[str]) -> str | None:
    if _has_micronaut_environments_property(args):
        return None
    if command == "dev":
        return "dev"
    if command == "test":
        return "test"
    return None


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


def _matches_requested_graalvm_home(java_home: Path, toolchain: _ToolchainSpec, *, require_release_tag: bool = True) -> bool:
    metadata = _read_graalvm_metadata(java_home)
    if metadata is None or metadata.java_version is None or metadata.java_version < toolchain.java_version:
        return False
    if require_release_tag and toolchain.release_tag is not None and not _matches_release_tagged_home(java_home, toolchain.release_tag):
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


def _matches_release_tagged_home(java_home: Path, release_tag: str) -> bool:
    expected = _graalvm_release_cache_dir_name(release_tag)
    for path in (java_home, *java_home.parents):
        if path.name == expected:
            return True
    return False


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
    # Development archives identify themselves in the build string and/or
    # installation directory (for example, graalvm-community-25.3.4.1-dev).
    # Check this before the generic "graalvm community" marker so CE dev
    # builds can satisfy an explicitly requested `distribution = "dev"`.
    if "dev" in lowered:
        return "dev"
    if "oracle graalvm" in lowered or "graalvm-jdk" in lowered:
        return "ee"
    if "graalvm community" in lowered or "graalvm ce" in lowered or "graalce" in lowered or "graalvm-community" in lowered:
        return "ce"
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


def _download_proxy(url: str) -> tuple[str | None, str | None]:
    """Return the configured proxy and its bypass list for a download URL."""
    environment = os.environ
    proxy = next(
        (
            environment.get(name)
            for name in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
            if environment.get(name)
        ),
        None,
    )
    no_proxy = next((environment.get(name) for name in ("NO_PROXY", "no_proxy") if environment.get(name)), None)
    if proxy:
        return _normalize_proxy_url(proxy), no_proxy

    settings_path = Path.home() / ".pyronaut" / "settings.toml"
    try:
        import tomllib

        with settings_path.open("rb") as settings_file:
            proxy_config = tomllib.load(settings_file).get("proxy", {})
        if isinstance(proxy_config, dict):
            configured_url = proxy_config.get("url")
            if isinstance(configured_url, str) and configured_url.strip():
                return _normalize_proxy_url(configured_url), _proxy_bypass_value(proxy_config)
            host = proxy_config.get("host")
            port = proxy_config.get("port")
            if isinstance(host, str) and host.strip() and port is not None:
                protocol = proxy_config.get("protocol", "http")
                credentials = ""
                username = proxy_config.get("username")
                password = proxy_config.get("password")
                if username is not None:
                    credentials = urllib.parse.quote(str(username), safe="")
                    if password is not None:
                        credentials += ":" + urllib.parse.quote(str(password), safe="")
                    credentials += "@"
                configured_url = f"{protocol}://{credentials}{host.strip()}:{int(port)}"
                return configured_url, _proxy_bypass_value(proxy_config)
    except (OSError, ValueError, TypeError, ImportError):
        pass

    maven_settings = Path.home() / ".m2" / "settings.xml"
    try:
        root = ET.parse(maven_settings).getroot()
        # Maven settings normally declare a default XML namespace. ElementTree
        # includes that namespace in every tag, so a namespace-free XPath would
        # silently miss the proxy and make downloads fail on VPN-only hosts.
        def local_name(element: ET.Element) -> str:
            return element.tag.rsplit("}", 1)[-1]

        proxy_containers = [element for element in root.iter() if local_name(element) == "proxies"]
        proxies = [
            proxy
            for container in proxy_containers
            for proxy in list(container)
            if local_name(proxy) == "proxy"
        ]

        def child_text(element: ET.Element, name: str) -> str | None:
            for child in list(element):
                if local_name(child) == name:
                    return child.text
            return None

        proxy_element = next(
            (element for element in proxies if (child_text(element, "active") or "").strip().lower() == "true"),
            None,
        )
        if proxy_element is None and proxies:
            proxy_element = proxies[0]
        if proxy_element is not None:
            protocol = (child_text(proxy_element, "protocol") or "http").strip()
            host = (child_text(proxy_element, "host") or "").strip()
            port = (child_text(proxy_element, "port") or "").strip()
            if host and port:
                credentials = ""
                username = child_text(proxy_element, "username")
                password = child_text(proxy_element, "password")
                if username:
                    credentials = urllib.parse.quote(username, safe="")
                    if password:
                        credentials += ":" + urllib.parse.quote(password, safe="")
                    credentials += "@"
                return f"{protocol}://{credentials}{host}:{int(port)}", child_text(proxy_element, "nonProxyHosts")
    except (OSError, ET.ParseError, ValueError, TypeError):
        pass
    return None, None


def _read_pyronaut_user_settings() -> dict[str, object]:
    settings_path = Path.home() / ".pyronaut" / "settings.toml"
    if not settings_path.is_file():
        return {}
    try:
        import tomllib

        with settings_path.open("rb") as settings_file:
            settings = tomllib.load(settings_file)
    except (OSError, ValueError, TypeError, ImportError) as exc:
        raise RuntimeError(f"Failed reading {settings_path}: {exc}") from exc
    if not isinstance(settings, dict):
        raise RuntimeError(f"Invalid Pyronaut settings in {settings_path}")
    return settings


def _installed_pyronaut_version() -> str:
    try:
        version = importlib.metadata.version("pyronaut")
        if version.strip():
            return version.strip()
    except importlib.metadata.PackageNotFoundError:
        pass

    version_file = Path(__file__).with_name("version.properties")
    if version_file.is_file():
        for line in version_file.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "pyronaut" and value.strip():
                return value.strip().replace("-SNAPSHOT", ".dev0")
    return "unknown"


def _native_image_platform(
    *,
    platform_name: str | None = None,
    machine_name: str | None = None,
) -> tuple[str, str]:
    current_platform = (platform_name or sys.platform).lower()
    if current_platform.startswith("linux"):
        os_segment = "linux"
    elif current_platform == "darwin":
        os_segment = "macos"
    else:
        raise RuntimeError(
            f"Native Pyronaut images are not available for platform '{platform_name or sys.platform}'. "
            "Supported platforms are Linux and macOS."
        )

    machine = (machine_name or platform.machine()).lower()
    if machine in {"x86_64", "amd64"}:
        arch = "amd64"
    elif machine in {"aarch64", "arm64"}:
        arch = "aarch64"
    else:
        raise RuntimeError(f"Native Pyronaut images are not available for architecture '{machine}'")
    return os_segment, arch


def _native_image_configuration() -> tuple[str, str]:
    settings = _read_pyronaut_user_settings()
    configured = settings.get(_NATIVE_IMAGE_SETTINGS_TABLE, {})
    if configured is None:
        configured = {}
    if not isinstance(configured, dict):
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}] must be a TOML table")

    base_url = configured.get("base-url", _NATIVE_IMAGE_BASE_URL)
    if not isinstance(base_url, str) or not base_url.strip():
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].base-url must be a non-empty URL")
    base_url = base_url.strip()
    parsed_base_url = urllib.parse.urlparse(base_url)
    if parsed_base_url.scheme in {"http", "https"}:
        if not parsed_base_url.netloc:
            raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].base-url must be an HTTP(S) URL or local directory")
    elif parsed_base_url.scheme == "file":
        if parsed_base_url.netloc not in {"", "localhost"} or not parsed_base_url.path:
            raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].base-url must be an HTTP(S) URL or local directory")
        base_url = str(Path(urllib.parse.unquote(parsed_base_url.path)).expanduser().resolve())
    elif parsed_base_url.scheme == "":
        base_url = str(Path(base_url).expanduser().resolve())
    else:
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].base-url must be an HTTP(S) URL or local directory")

    version = configured.get("version", _installed_pyronaut_version())
    if not isinstance(version, str) or not version.strip():
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].version must be a non-empty string")
    version = version.strip()
    if any(character in version for character in "/\\"):
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].version must not contain path separators")
    return (base_url.rstrip("/") + "/") if parsed_base_url.scheme in {"http", "https"} else base_url, version


def _native_image_local_archive(
    base_url: str,
    image_name: str,
    archive_name: str,
) -> Path | None:
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme in {"http", "https"}:
        return None
    root = Path(base_url).expanduser()
    if not root.is_dir():
        raise RuntimeError(f"Native image base directory does not exist: {root}")
    archive = root / image_name / "build" / "distributions" / archive_name
    if not archive.is_file():
        raise RuntimeError(
            f"Native image bundle not found at {archive}. Build {image_name} with Gradle or configure a matching version."
        )
    return archive


def _native_image_cache_path(
    image_name: str,
    *,
    version: str,
    os_segment: str,
    arch: str,
) -> Path:
    return Path.home() / ".pyronaut" / "bin" / version / f"{os_segment}-{arch}" / image_name


@contextlib.contextmanager
def _native_image_cache_lock(lock_path: Path):
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        try:
            import fcntl

            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        except ImportError:
            pass
        try:
            yield
        finally:
            try:
                import fcntl

                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
            except ImportError:
                pass


def _download_url_with_progress(url: str, destination: Path, label: str) -> None:
    proxy, bypass = _download_proxy(url)
    parsed_url = urllib.parse.urlparse(url)
    host = parsed_url.hostname or ""
    host_with_port = parsed_url.netloc.rsplit("@", 1)[-1]
    bypassed = _proxy_bypasses_host(host, bypass) or _proxy_bypasses_host(host_with_port, bypass)
    proxies = {} if proxy is None or bypassed else {parsed_url.scheme: proxy}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))

    with opener.open(url) as response, destination.open("wb") as output:
        total = int(response.headers.get("Content-Length", "0") or "0")
        downloaded = 0
        last_percent = -1
        print(f"{label}... 0%", end="", file=sys.stderr, flush=True)
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            downloaded += len(chunk)
            if total:
                percent = min(100, downloaded * 100 // total)
                if percent != last_percent:
                    last_percent = percent
                    print(f"\r{label}... {percent}%", end="", file=sys.stderr, flush=True)
        print(f"\r{label}... 100%", file=sys.stderr, flush=True)


def _download_native_image_archive(url: str, destination: Path, image_name: str) -> None:
    _download_url_with_progress(url, destination, f"Downloading {image_name}")


def _extract_native_image_archive(archive: Path, destination: Path, image_name: str) -> None:
    try:
        with tarfile.open(archive, "r:gz") as tar:
            members = tar.getmembers()
            expected_names = {image_name, f"./{image_name}"}
            executable_members = [member for member in members if member.name in expected_names]
            if len(executable_members) != 1 or not executable_members[0].isreg():
                raise RuntimeError(
                    f"Native image archive must contain a regular '{image_name}' executable"
                )
            root = destination.parent.resolve()
            for member in members:
                member_path = Path(member.name)
                if member_path.is_absolute() or ".." in member_path.parts:
                    raise RuntimeError(f"Native image archive contains an unsafe path: {member.name}")
                target = (root / member_path).resolve()
                if target != root and root not in target.parents:
                    raise RuntimeError(f"Native image archive contains an unsafe path: {member.name}")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                if not member.isreg():
                    raise RuntimeError(f"Native image archive contains unsupported entry: {member.name}")
                source = tar.extractfile(member)
                if source is None:
                    raise RuntimeError(f"Unable to read '{member.name}' from native image archive")
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("wb") as output:
                    shutil.copyfileobj(source, output)
    except (OSError, tarfile.TarError) as exc:
        raise RuntimeError(f"Unable to unpack native image archive: {exc}") from exc
    if not destination.is_file():
        raise RuntimeError(f"Native image archive did not extract '{image_name}'")
    destination.chmod(0o755)


def _ensure_native_image(
    image_name: str,
    *,
    platform_name: str | None = None,
    machine_name: str | None = None,
) -> Path:
    if image_name not in _NATIVE_IMAGE_COMMANDS:
        raise RuntimeError(f"Unsupported native Pyronaut image: {image_name}")
    base_url, version = _native_image_configuration()
    os_segment, arch = _native_image_platform(platform_name=platform_name, machine_name=machine_name)
    archive_name = f"{image_name}-{os_segment}-{arch}-{version}.tar.gz"
    local_archive = _native_image_local_archive(base_url, image_name, archive_name)
    url = local_archive.resolve().as_uri() if local_archive is not None else urllib.parse.urljoin(base_url, archive_name)
    executable = _native_image_cache_path(
        image_name,
        version=version,
        os_segment=os_segment,
        arch=arch,
    )
    metadata = executable.with_name(executable.name + ".json")
    lock = executable.with_name(executable.name + ".lock")
    expected_metadata: dict[str, object] = {
        "url": url,
        "version": version,
        "platform": f"{os_segment}-{arch}",
        # Version 3 isolates per-image manifests/resources in the shared
        # version/platform cache directory.
        "bundle-format": 3,
    }
    # A local checkout keeps the same URL while Gradle replaces the archive
    # in place. Record its cheap filesystem fingerprint so a rebuilt bundle is
    # unpacked once, while ordinary invocations remain a metadata-only cache
    # hit. Remote archives intentionally remain URL/version keyed; probing
    # them would add network latency to every command.
    if local_archive is not None:
        archive_stat = local_archive.stat()
        expected_metadata["archive-size"] = archive_stat.st_size
        expected_metadata["archive-mtime-ns"] = archive_stat.st_mtime_ns
        # Gradle can replace an archive while preserving its timestamp (for
        # example when build output is copied from another filesystem). The
        # inode-change timestamp catches that replacement without hashing a
        # several-hundred-megabyte archive on every invocation.
        expected_metadata["archive-ctime-ns"] = archive_stat.st_ctime_ns

    with _native_image_cache_lock(lock):
        if executable.is_file() and metadata.is_file():
            try:
                cached_metadata = json.loads(metadata.read_text(encoding="utf-8"))
            except (OSError, ValueError, TypeError):
                cached_metadata = None
            if cached_metadata == expected_metadata:
                # Do not touch an already-executable Mach-O on macOS. A
                # redundant chmod can invalidate/retrigger Gatekeeper's
                # ad-hoc signature validation and adds several seconds to
                # every delegated native command.
                if not os.access(executable, os.X_OK):
                    executable.chmod(0o755)
                return executable

        executable.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=f".{image_name}-", dir=executable.parent) as temp_dir:
            temp_root = Path(temp_dir)
            archive = temp_root / archive_name
            extracted = temp_root / "bundle" / image_name
            try:
                if local_archive is not None:
                    shutil.copy2(local_archive, archive)
                else:
                    _download_native_image_archive(url, archive, image_name)
                _extract_native_image_archive(archive, extracted, image_name)
            except Exception as exc:
                raise RuntimeError(f"Failed downloading {image_name} from {url}: {exc}") from exc
            metadata_tmp = temp_root / f"{image_name}.json"
            metadata_tmp.write_text(json.dumps(expected_metadata, sort_keys=True) + "\n", encoding="utf-8")
            # Keep the complete bundle alongside the executable. Native-image
            # resource files and classpath manifests are runtime inputs even
            # though the launcher itself is a single executable.
            for item in extracted.parent.iterdir():
                # Several native bundles contain identically named manifests
                # (native-compile-classpath.txt, native-provided-classpath.txt)
                # and resources. Keep those image-specific files separate;
                # otherwise provisioning pyronaut-run after pyronaut-dev
                # silently replaces the compiler manifest used by pyronaut-dev.
                if item.name in {"native-compile-classpath.txt", "native-provided-classpath.txt"}:
                    target = executable.parent / "resources" / image_name / item.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(item, target)
                    # Preserve the historical root-level location for
                    # compatibility with older tooling. New lookups always
                    # prefer the image-specific manifest above, so another
                    # image cannot affect the active compiler classpath.
                    legacy_target = executable.parent / item.name
                    if not legacy_target.exists():
                        shutil.copy2(target, legacy_target)
                    continue
                else:
                    target = executable.parent / item.name
                if item.is_dir():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copytree(item, target, dirs_exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(item, target)
            os.replace(metadata_tmp, metadata)
    return executable


def _normalize_proxy_url(value: str) -> str:
    value = value.strip()
    return value if "://" in value else f"http://{value}"


def _proxy_bypass_value(proxy_config: dict[str, object]) -> str | None:
    value = proxy_config.get("nonProxyHosts") or proxy_config.get("noProxyHosts")
    return str(value) if value is not None else None


def _proxy_bypasses_host(host: str, bypass: str | None) -> bool:
    if not bypass:
        return False
    patterns = [pattern.strip().lower() for pattern in re.split(r"[,|]", bypass) if pattern.strip()]
    host = host.lower()
    return any(
        fnmatch.fnmatch(host, pattern) or fnmatch.fnmatch(host.split(":", 1)[0], pattern)
        for pattern in patterns
    )


def _download_graalvm_archive(url: str, destination: Path) -> None:
    _download_url_with_progress(url, destination, "Downloading GraalVM SDK")


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
            _download_graalvm_archive(archive_url, archive_file)
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
            if not _matches_requested_graalvm_home(home, spec, require_release_tag=False):
                continue
            destination = jdks_root / _graalvm_cache_dir_name(home, spec)
            if destination.exists():
                normalized_existing = _normalize_extracted_home(destination) or destination
                if _matches_requested_graalvm_home(normalized_existing, spec):
                    return normalized_existing
                shutil.rmtree(destination, ignore_errors=True)

            source_root = home.parent.parent if home.name == "Home" and home.parent.name == "Contents" else home
            shutil.copytree(source_root, destination, dirs_exist_ok=True)
            normalized_destination = _normalize_extracted_home(destination) or destination
            if _matches_requested_graalvm_home(normalized_destination, spec):
                _warn_if_quarantined_graalvm(normalized_destination)
                return normalized_destination
    return None


def _graalvm_cache_dir_name(home: Path, toolchain: _ToolchainSpec) -> str:
    if toolchain.release_tag is not None:
        return _graalvm_release_cache_dir_name(toolchain.release_tag)
    if home.name == "Home" and home.parent.name == "Contents":
        return home.parent.parent.name
    return home.name


def _graalvm_release_cache_dir_name(release_tag: str) -> str:
    return release_tag.strip().replace("/", "_")


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

    if toolchain.release_tag is not None:
        return _resolve_dev_build_archive_url(os_segment, arch, ext, toolchain)

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
        base = f"graalvm-jdk-{version}-{_DEFAULT_JDK_VERSION}_{os_segment}-{arch}_bin.{ext}"
        return f"{_GDS_DOWNLOAD_URL}/{version}/latest/{base}"
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


def _has_tests_selection(args: Sequence[str]) -> bool:
    return any(token == "--tests" or token.startswith("--tests=") for token in args)


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


def _extract_offline(args: Sequence[str]) -> bool:
    return any(token == "--offline" for token in args)


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


def _strip_orchestrator_only_args(args: Sequence[str]) -> list[str]:
    """Remove CLI options consumed before invoking a native runner."""
    filtered: list[str] = []
    skip_next = False
    value_options = {"--local-repository", "--local-repo", "--color", "--progress"}
    flag_options = {"--offline", "--no-validate", "--no-cache"}
    for token in args:
        if skip_next:
            skip_next = False
            continue
        if token in value_options:
            skip_next = True
            continue
        if token in flag_options or any(token.startswith(option + "=") for option in value_options | flag_options):
            continue
        filtered.append(token)
    return filtered


def _extract_no_validate(args: Sequence[str]) -> bool:
    return any(token == "--no-validate" for token in args)


def _remove_no_validate(args: Sequence[str]) -> list[str]:
    return [token for token in args if token != "--no-validate"]


def _extract_continuous(args: Sequence[str]) -> bool:
    return any(token in {"-t", "--continuous"} for token in args)


def _remove_continuous(args: Sequence[str]) -> list[str]:
    return [token for token in args if token not in {"-t", "--continuous"}]


def _extract_project_dir(args: Sequence[str]) -> str:
    for index, token in enumerate(args):
        if token == "--project-dir" and index + 1 < len(args):
            return args[index + 1]
        if token.startswith("--project-dir="):
            return token.split("=", 1)[1]
    return "."


def _install_required(project_dir: Path) -> bool:
    cache_dir = _pyronaut_output_dir(project_dir)
    if _is_external_build_project(project_dir):
        return not (cache_dir / "project-layout.properties").exists()
    required_manifests = (
        cache_dir / "resolved-build-dependencies",
        cache_dir / "resolved-runtime-dependencies",
        cache_dir / "resolved-test-dependencies",
    )
    return not all(path.exists() for path in required_manifests)


def _process_required(project_dir: Path, command: str) -> bool:
    output_dir = _pyronaut_output_dir(project_dir)
    classes_ready = (output_dir / "classes").is_dir()
    if command == "test":
        test_classes_ready = (output_dir / "test-classes").is_dir()
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

    if command_name in {"pyronaut-run", "pyronaut-run-python"} and resolver is _resolve_executable:
        return str(_ensure_native_image(command_name))

    if fallback_to_resolver:
        return resolver(command_name)
    return None


def _resolve_pyronaut_dev_native_executable(resolver: Callable[[str], str | None]) -> str | None:
    env_key = DEV_NATIVE_EXECUTABLE.upper().replace("-", "_") + "_NATIVE_EXECUTABLE"
    override = _read_env(env_key)
    if override:
        return override

    bundled = _bundled_native_executable(DEV_NATIVE_EXECUTABLE)
    if bundled is not None and bundled.exists():
        return str(bundled)
    if resolver is not _resolve_executable:
        return resolver(DEV_NATIVE_EXECUTABLE)
    return str(_ensure_native_image(DEV_NATIVE_EXECUTABLE))


def _pyronaut_dev_native_command_line(
    command: str,
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    classpath_command: str | None = None,
    environment: str | None = None,
    debug_vm: bool = False,
    env_overrides: dict[str, str] | None = None,
    java_home_provider: JavaHomeProvider | None = None,
) -> list[str] | None:
    if command not in DEV_NATIVE_COMMANDS:
        return None
    project_dir = Path(_extract_project_dir(args)).resolve()
    try:
        if not _use_pyronaut_dev_native_toolchain(command, project_dir, debug_vm=debug_vm, args=args):
            return None
    except ValueError as exc:
        raise RuntimeError(str(exc)) from exc
    executable_path = _resolve_pyronaut_dev_native_executable(resolver)
    if executable_path is None:
        if _read_pyproject_toolchain_type(project_dir) == TOOLCHAIN_TYPE_NATIVE:
            raise RuntimeError(
                "Missing native delegated executable for pyronaut-dev: the project requests the native toolchain, "
                "but the native pyronaut-dev executable is not installed. "
                "Build or install pyronaut-dev instead of falling back to the JVM launcher."
            )
        if _is_external_build_project(project_dir):
            # SDK wheels can be installed without the optional native dev
            # executable. External JVM builds still have a complete delegated
            # launcher path, so fall back to it instead of rejecting dev/test.
            return None
        raise RuntimeError("Missing native delegated executable for pyronaut-dev. Build or install pyronaut-dev, or set tool.pyronaut.toolchain.type = 'jvm'.")
    jvm_args = _native_dev_java_home_jvm_args(java_home_provider) if command in {"dev", "run", "test"} else []
    # Coordinates contain ':', so use a delimiter independent of the host
    # path separator when passing the list through a system property.
    provided_artifacts = ",".join(sorted(_native_launcher_provided_artifact_coordinates(executable_path)))
    if provided_artifacts:
        # The native pyronaut-dev image embeds the compiler and annotation
        # processors. Tell the in-process processor to remove matching
        # project artifacts from its annotation-processor path as well as from
        # application classpaths.
        jvm_args.append(f"-Dpyronaut.dev.native.provided.artifacts={provided_artifacts}")
        provided_jars = _native_launcher_provided_jar_entries(executable_path)
        if provided_jars:
            # Metadata consumers inspect these shipped JARs directly; they are
            # deliberately not part of the application's runtime classpath.
            jvm_args.append(f"-Dpyronaut.dev.native.provided.jars={os.pathsep.join(provided_jars)}")
    selected_environment = (
        environment if environment is not None and not _has_micronaut_environments_property(args)
        else _default_environment(command, args)
    )
    if selected_environment is not None:
        jvm_args.append(f"-Dmicronaut.environments={selected_environment}")
    direct_source = _looks_like_direct_source_invocation(args)
    # External-project `run`/`test` preflight invokes the native `process`
    # command without a source selector. It still needs the compiler manifest
    # (notably micronaut-context-python and micronaut-inject-python) on the
    # processor classpath.
    compiler_classpath = (
        os.pathsep.join(_native_launcher_compile_classpath_entries(executable_path))
        if direct_source or command == "process"
        else ""
    )
    if compiler_classpath:
        # External Java sources need Pyronaut's Python annotation types and
        # processor support to compile, but those jars must not become part
        # of the application's runtime classpath.
        jvm_args.append(f"-Dpyronaut.dev.compiler.class.path={compiler_classpath}")
    effective_classpath_command = classpath_command or command
    if effective_classpath_command in {"dev", "run", "test", "validate-config"}:
        # Configuration validation resolves application-backed beans (for
        # example the default Serde ObjectMapper), so it needs the runtime
        # dependency classpath even though it does not launch the app.
        classpath_command = "run" if effective_classpath_command == "validate-config" else effective_classpath_command
        classpath = _build_native_application_classpath(classpath_command, project_dir, executable_path)
        if effective_classpath_command == "dev" and _control_panel_requested(project_dir, args):
            control_panel = _direct_control_panel_classpath_entries(executable_path)
            if control_panel:
                classpath = os.pathsep.join(dict.fromkeys([classpath, *control_panel]))
                jvm_args.extend((
                    "-Dmicronaut.control-panel.enabled=true",
                    "-Dmicronaut.control-panel.path=/control-panel",
                    "-Dmicronaut.control-panel.security.access=ANONYMOUS",
                    f"-Dpyronaut.dev.control.panel.class.path={os.pathsep.join(control_panel)}",
                ))
        elif effective_classpath_command == "run":
            jvm_args.append("-Dmicronaut.control-panel.enabled=false")
        jvm_args = [*jvm_args, f"-Djava.class.path={classpath}"]
        test_resources_client_classpath = _build_native_test_resources_client_classpath(project_dir)
        if test_resources_client_classpath:
            jvm_args.append(f"-Dpyronaut.dev.test.resources.client.classpath={test_resources_client_classpath}")
        jvm_args.extend(_build_test_resources_jvm_args(env_overrides))
    # Tool commands are wrappers around the corresponding Pyronaut delegate;
    # retain their resolver/progress/offline options. Runtime native launchers
    # do not understand those orchestration-only flags and must receive the
    # filtered form instead.
    forwarded_args = args if command == "install" else _strip_orchestrator_only_args(args)
    command_args = [value for value in forwarded_args if value not in {"--jvm", "--native"}]
    if command == "test" and _is_external_build_project(project_dir) and not any(
        value == "--select-class" or value.startswith("--select-class=") for value in command_args
    ):
        test_classes_root = _pyronaut_output_dir(project_dir) / "test-classes"
        if test_classes_root.is_dir():
            for class_file in sorted(test_classes_root.rglob("*.class")):
                if "$" in class_file.name or class_file.name in {"module-info.class", "package-info.class"}:
                    continue
                class_name = ".".join(class_file.relative_to(test_classes_root).with_suffix("").parts)
                command_args.extend(["--select-class", class_name])
    if command == "process":
        # Native images do not expose their launcher classpath to javac
        # automatically. Pass the API/compiler jars explicitly for processing;
        # they are recorded in native-compile-classpath.txt and are not runtime
        # application dependencies.
        process_compiler_classpath = compiler_classpath or os.pathsep.join(
            _native_launcher_compile_classpath_entries(executable_path)
        )
        if not process_compiler_classpath:
            return [executable_path, *jvm_args, command, *command_args]
        selected_pass = "all"
        for index, value in enumerate(command_args):
            if value == "--pass" and index + 1 < len(command_args):
                selected_pass = command_args[index + 1]
            elif value.startswith("--pass="):
                selected_pass = value.split("=", 1)[1]
        compiler_entries = process_compiler_classpath.split(os.pathsep)
        if selected_pass in {"all", "main"}:
            main_entries = _build_native_application_classpath_entries("process", project_dir)
            command_args.extend(["--classpath", os.pathsep.join(dict.fromkeys([*main_entries, *compiler_entries]))])
        if selected_pass in {"all", "test"}:
            test_entries = _build_native_application_classpath_entries("process-test", project_dir)
            command_args.extend(["--test-classpath", os.pathsep.join(dict.fromkeys([*test_entries, *compiler_entries]))])
    return [executable_path, *jvm_args, command, *command_args]


def _pyronaut_run_native_command_line(
    command: str,
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
) -> list[str] | None:
    """Build a production Crema launch command for a native-toolchain project."""
    if command != "run" or debug_vm:
        return None
    project_dir = Path(_extract_project_dir(args)).resolve()
    try:
        if not _use_pyronaut_dev_native_toolchain(command, project_dir, debug_vm=debug_vm, args=args):
            return None
    except ValueError as exc:
        raise RuntimeError(str(exc)) from exc
    executable_name = _delegate_executable_name(command, project_dir)
    executable_path = _resolve_native_preferred_executable(
        executable_name,
        resolver,
        fallback_to_resolver=False,
    )
    if executable_path is None:
        raise RuntimeError(
            f"Missing native production runtime for {executable_name}. "
            f"Build or install {executable_name}, or set tool.pyronaut.toolchain.type = 'jvm'."
        )
    classpath = _build_native_application_classpath(command, project_dir, executable_path)
    control_panel_property = (
        "-Dmicronaut.control-panel.enabled=true"
        if _control_panel_dependency_declared(project_dir)
        else "-Dmicronaut.control-panel.enabled=false"
    )
    command_line = [
        executable_path,
        control_panel_property,
        f"-Djava.class.path={classpath}",
        *_strip_orchestrator_only_args([value for value in args if value not in {"--jvm", "--native"}]),
    ]
    return command_line


def _native_dev_java_home_jvm_args(java_home_provider: JavaHomeProvider | None) -> list[str]:
    if java_home_provider is None:
        return []
    java_home = java_home_provider()
    if java_home is None or not java_home.strip():
        raise RuntimeError("Unable to locate or provision compatible GraalVM JDK (requires JDK 25+)")
    return [f"-Djava.home={java_home}"]


_UNSET_PYPROJECT_TABLE = object()


def _use_pyronaut_dev_native_toolchain(
    command: str,
    project_dir: Path,
    *,
    debug_vm: bool = False,
    args: Sequence[str] = (),
    pyronaut_table: dict[str, object] | None | object = _UNSET_PYPROJECT_TABLE,
) -> bool:
    # Callers that make several decisions for the same project can provide the
    # already-loaded table. This avoids reparsing pyproject.toml repeatedly.
    pyronaut = (
        _read_pyproject_pyronaut_table(project_dir)
        if pyronaut_table is _UNSET_PYPROJECT_TABLE
        else pyronaut_table
    )
    override = _extract_build_mode_flag(args)
    if override is not None:
        toolchain_type = override
    elif _is_external_build_project(project_dir) and pyronaut is None:
        toolchain_type = TOOLCHAIN_TYPE_NATIVE
    else:
        toolchain_type = _toolchain_type_from_pyronaut_table(pyronaut)
    if toolchain_type != TOOLCHAIN_TYPE_NATIVE:
        return False
    if command == "process" and _read_pyproject_processor_mode_override(project_dir) is not None:
        return False
    if command == "test":
        if debug_vm:
            return False
        if _read_pyproject_test_mode_override(project_dir) is not None:
            return False
    return True


def _looks_like_direct_source_invocation(argv: Sequence[str]) -> bool:
    if not argv:
        return False
    direct_options = {"--port", "--property", "-D", "--config", "--setup", "--report", "--disable-test-resources", "--control-panel", "--verbose", "--test", "--smoke", "--non-interactive", "--trace-delegation"}
    value_options = {"--port", "--property", "-D", "--config", "--setup"}
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--":
            return len(argv) > index + 1
        if arg in direct_options or arg.startswith("-D") or arg.startswith("--verbose="):
            if arg in value_options:
                index += 2
            elif arg in {"--report", "--verbose"} and index + 1 < len(argv) and not argv[index + 1].startswith("-"):
                index += 2
            else:
                index += 1
            continue
        if arg.endswith((".py", ".java")):
            return True
        try:
            if Path(arg).is_dir():
                return True
        except OSError:
            return False
        return False
    # Development options alone configure the project-based command; direct
    # source execution requires an explicit source selector.
    return False


def _normalize_report_argument(args: Sequence[str], project_dir: Path | None = None) -> list[str]:
    normalized: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--report":
            if index + 1 < len(args) and not args[index + 1].startswith("-"):
                normalized.extend(("--report-dir", args[index + 1]))
                index += 2
            else:
                normalized.extend(("--report-dir", str((_pyronaut_output_dir(project_dir or Path.cwd()) / "reports" / "tests"))))
                index += 1
            continue
        if token.startswith("--report="):
            normalized.extend(("--report-dir", token.split("=", 1)[1]))
        else:
            normalized.append(token)
        index += 1
    return normalized


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
                    "Build or install a native pyronaut-processor, or set tool.pyronaut.processor.mode = 'jvm'."
                )
            return executable_path

    if command == "run":
        try:
            if _use_pyronaut_dev_native_toolchain(command, project_dir, debug_vm=_extract_debug_vm(args), args=args):
                executable_name = _delegate_executable_name(command, project_dir)
                executable_path = _resolve_native_preferred_executable(
                    executable_name,
                    resolver,
                    # Tamboui invokes a configured executable itself; when a
                    # bundled native runtime is unavailable its JVM launcher
                    # remains a valid delegate for the interactive UI.
                    fallback_to_resolver=True,
                )
                if executable_path is None:
                    raise RuntimeError(
                        f"Missing native production runtime for {executable_name}. "
                        f"Build or install {executable_name}, or set tool.pyronaut.toolchain.type = 'jvm'."
                    )
                return executable_path
        except ValueError as exc:
            raise RuntimeError(str(exc)) from exc
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
            test_mode = TOOLCHAIN_TYPE_JVM
        if test_mode == "native":
            executable_path = _resolve_native_preferred_executable(
                executable_name,
                resolver,
                fallback_to_resolver=False,
            )
            if executable_path is None:
                raise RuntimeError(
                    "Missing native delegated executable for pyronaut-test. "
                    "Build or install a native pyronaut-test, or set tool.pyronaut.test.mode = 'jvm'."
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
    if _test_resources_disabled():
        return False
    if _is_external_build_project(project_dir):
        return _read_external_test_resources_enabled(project_dir)
    return _read_pyproject_test_resources_enabled(project_dir)


def _read_external_test_resources_enabled(project_dir: Path) -> bool:
    layout_file = _pyronaut_output_dir(project_dir) / "project-layout.properties"
    if not layout_file.exists():
        return False
    return any(
        line.strip().lower() == "testresourcesenabled=true"
        for line in layout_file.read_text(encoding="utf-8").splitlines()
    )


def _is_supported_platform(platform_name: str) -> bool:
    return platform_name.startswith("linux") or platform_name == "darwin"


def _print_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut [--version] [--tui [--smoke|--non-interactive]] <install|process|dev|run|test|build|validate-config|test-resources-server> [args...]\n")


def _print_build_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut build [--project-dir <dir>] [--native|--jvm|--mode=<native|jvm>] [--docker] [--static] [--base-image|--base-image=default] [--base-image-output <path>] [--name <name>] [--version <version>] [<source.java|source.py|source-dir>...] [--main-class <fqcn>] [--verbose] [--no-cache] [--no-validate]\n"
    )


def _print_run_usage(stream=None, command: str = "run") -> None:
    if stream is None:
        stream = sys.stdout
    _write_command_help(
        stream,
        usage_lines=[
            f"Usage: pyronaut {command} [-hV] [--debug-vm] [--no-cache] [--no-validate]"
            + (" [--control-panel]" if command == "dev" else ""),
            "                    [--classes-dir=<classesDir>]",
            "                    [--config-dir=<configDir>]",
            "                    [--main-class=<mainClass>]",
            "                    [--project-dir=<projectDir>] [<appArgs>...]",
            f"       pyronaut {command} [--port=<port>] [--property=<name=value>]",
            "                    [-D<name=value>] [--config=<file-or-dir>]",
            "                    [--setup=<pyproject.toml>]",
            (
                "                    [<source.py|source-dir>...]"
                if command == "dev"
                else "                    <source.py|source-dir>..."
            ),
        ],
        description=(
            "Run a processed Pyronaut application or direct Java/Python sources in development mode"
            if command == "dev"
            else "Run a processed Pyronaut application or direct Java/Python sources"
        ),
        options=[
            ("[<appArgs>...]", "Arguments passed to the processed application"),
            ("<source.java|source.py|source-dir>...", "Java or Python source files/directories for direct source execution"),
            *(
                [("main.py", "Detected in the current directory when no source or project build is present")]
                if command == "dev"
                else []
            ),
            ("-D<name=value>", "Set a Micronaut/system property for direct source execution"),
            ("--classes-dir=<classesDir>", "Processed classes directory"),
            *( [("--control-panel", "Enable the development Control Panel")] if command == "dev" else [] ),
            ("--config=<file-or-dir>", "Configuration file or directory for direct source execution"),
            ("--config-dir=<configDir>", "Processed application configuration directory"),
            ("--debug-vm", "Enable JVM JDWP debugging on port 5005"),
            *(
                [("--disable-test-resources", "Disable configured and conditionally inferred Test Resources")]
                if command == "dev"
                else []
            ),
            ("--verbose[=LOGGER]", "Enable verbose logging, optionally scoped to comma-separated logger names."),
            ("-h, --help", "Show this help message and exit."),
            ("--main-class=<mainClass>", "Main class to invoke"),
            ("--no-cache", "Bypass run preflight cache reads where applicable"),
            ("--no-validate", "Skip run scenario configuration validation"),
            ("--port=<port>", "Set micronaut.server.port for direct source execution"),
            ("--project-dir=<projectDir>", "Project directory containing pyproject.toml"),
            ("--property=<name=value>", "Set a Micronaut/system property for direct source execution"),
            ("--setup=<pyproject.toml>", "pyproject.toml to stage for direct source execution"),
            ("-V, --version", "Print version information and exit."),
        ],
    )


def _print_test_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    _write_command_help(
        stream,
        usage_lines=[
            "Usage: pyronaut test [-hV] [--debug-vm] [--no-cache] [--no-validate] [-t|--continuous]",
            "                     [--classes-dir=<classesDir>]",
            "                     [--config-dir=<configDir>]",
            "                     [--project-dir=<projectDir>]",
            "                     [--select-class=<selectClasses>]",
            "                     [--test-classes-dir=<testClassesDir>]",
            "                     [--tests=<tests>] [--tests-dir=<testsDir>]",
            "       pyronaut test <source>",
            "       pyronaut test <source> -- <test-source>",
            "                       [-D<name=value>] [--config=<file-or-dir>]",
            "                       [--setup=<pyproject.toml>]",
            "                       [--report[=<directory>]]",
            "                       <source.py|source-dir>...",
            "                       [-- <test-source.py|test-dir>...]",
        ],
        description="Run tests for a processed Pyronaut application or direct Java/Python JUnit 5 sources",
        options=[
            ("<source.java|source.py|source-dir>...", "Java or Python application sources for direct source test execution"),
            ("<test-source.java|test-source.py|test-dir>...", "Explicit Java or Python JUnit 5 test sources after --; omitted sources default to **Test.java or **Test.py"),
            ("-D<name=value>", "Set a Micronaut/system property for direct source execution"),
            ("--classes-dir=<classesDir>", "Processed classes directory"),
            ("--config=<file-or-dir>", "Configuration file or directory for direct source execution"),
            ("--config-dir=<configDir>", "Configuration directory"),
            ("--debug-vm", "Enable JVM JDWP debugging on port 5005"),
            ("--disable-test-resources", "Disable configured and conditionally inferred Test Resources"),
            ("--verbose[=LOGGER]", "Enable verbose logging, optionally scoped to comma-separated logger names."),
            ("-h, --help", "Show this help message and exit."),
            ("-t, --continuous", "Keep the test command running for interactive reruns"),
            ("--no-cache", "Bypass test preflight cache reads where applicable"),
            ("--no-validate", "Skip test scenario configuration validation"),
            ("--port=<port>", "Set micronaut.server.port for direct source execution"),
            ("--project-dir=<projectDir>", "Project directory containing pyproject.toml"),
            ("--property=<name=value>", "Set a Micronaut/system property for direct source execution"),
            ("--select-class=<selectClasses>", "Select class to execute"),
            ("--setup=<pyproject.toml>", "pyproject.toml to stage for direct source execution"),
            ("--report[=<directory>]", "Select the report directory (direct tests always report; default: __pyronaut__/reports/tests)"),
            ("--test-classes-dir=<testClassesDir>", "Processed test classes directory"),
            ("--tests=<tests>", "Select tests (Gradle-like). Repeatable."),
            ("--tests-dir=<testsDir>", "Python tests directory"),
            ("-V, --version", "Print version information and exit."),
        ],
    )


def _write_command_help(stream, *, usage_lines: Sequence[str], description: str, options: Sequence[tuple[str, str]]) -> None:
    color = _help_color_enabled(stream)
    for line in usage_lines:
        stream.write(line + "\n")
    stream.write(description + "\n")
    option_width = max(len(names) for names, _description in options)
    for names, option_description in options:
        stream.write(f"{_help_option(names, color):<{option_width + (_help_color_extra(color))}}  {option_description}\n")


def _help_option(value: str, color: bool) -> str:
    if not color:
        return value
    return _ANSI_YELLOW + value + _ANSI_RESET


def _help_color_extra(color: bool) -> int:
    if not color:
        return 0
    return len(_ANSI_YELLOW) + len(_ANSI_RESET)


def _help_color_enabled(stream) -> bool:
    return bool(getattr(stream, "isatty", lambda: False)()) and _read_env("NO_COLOR") is None


def _run_tui(
    *,
    argv: list[str],
    runner_with_env: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    from .tui.app import TuiApp, TuiOptions
    from .tui.reports import render_summary_line

    args = [a for a in argv if a != "--tui"]

    if _extract_flag(args, "--help") or _extract_flag(args, "-h"):
        return _delegate_to_tui_binary(["--help"], runner_with_env, resolver)
    if _extract_flag(args, "--version") or _extract_flag(args, "-V"):
        return _delegate_to_tui_binary(["--version"], runner_with_env, resolver)

    direct_source = _looks_like_direct_source_invocation(args)
    project_dir = Path.cwd().resolve() if direct_source else Path(_extract_project_dir(args)).resolve()
    try:
        tui_toolchain_type = _read_pyproject_toolchain_type(project_dir)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    smoke = _extract_flag(args, "--smoke") or _extract_flag(args, "--non-interactive")
    non_interactive = _extract_flag(args, "--non-interactive")
    initial_mode = "test" if _extract_flag(args, "--test") else "run"
    report_dir = (_extract_path_flag(args, "--report-dir") or _extract_tui_report_path(args, project_dir) or (_pyronaut_output_dir(project_dir) / "reports" / "tests")).resolve()
    trace_delegation = _delegation_trace_enabled() or _extract_flag(args, "--trace-delegation")

    if not smoke and not non_interactive:
        direct_dev_executable = None
        direct_args = None
        if direct_source:
            try:
                direct_dev_executable = _resolve_direct_source_dev_executable(args, resolver)
            except (RuntimeError, ValueError) as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
            if direct_dev_executable is None:
                print("Missing native delegated executable: pyronaut-dev", file=sys.stderr)
                return PRECONDITION_FAILED
            direct_args = _direct_tui_arguments(args)
            # The regular direct-source CLI supplies the native launcher
            # compiler/application classpaths as JVM properties. The TUI is a
            # second process, so forward those properties with the source
            # selectors as well.
            try:
                direct_env = _build_java_home_env("dev", java_home_provider)
                direct_args = [
                    *_build_direct_source_native_jvm_args(
                        direct_dev_executable,
                        direct_env,
                        command="dev",
                        environment="dev",
                        args=direct_args,
                    ),
                    *direct_args,
                ]
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
        return _run_tamboui_tui(
            project_dir=project_dir,
            initial_mode=initial_mode,
            control_panel=_extract_flag(args, "--control-panel"),
            report_dir=report_dir,
            trace_delegation=trace_delegation,
            runner=runner_with_env,
            resolver=resolver,
            java_home_provider=java_home_provider,
            toolchain_type=tui_toolchain_type,
            direct_dev_executable=direct_dev_executable,
            direct_args=direct_args,
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
        self._session_file = _pyronaut_output_dir(project_dir) / "test-resources-session.json"
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

    def ensure_started(
        self,
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
        java_home_provider: JavaHomeProvider | None = None,
    ) -> None:
        cache_dir = _pyronaut_output_dir(self._project_dir)
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
            java_home_provider=java_home_provider,
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
        self._register_shutdown(runner=runner, resolver=resolver, java_home_provider=java_home_provider)

    def stop_if_owned(
        self,
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
        java_home_provider: JavaHomeProvider | None = None,
    ) -> None:
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
            java_home_provider=java_home_provider,
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

    def _register_shutdown(
        self,
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
        java_home_provider: JavaHomeProvider | None = None,
    ) -> None:
        if self._shutdown_registered:
            return

        def _shutdown() -> None:
            try:
                self.stop_if_owned(runner=runner, resolver=resolver, java_home_provider=java_home_provider)
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
        if not self._settings_file.exists():
            return False
        env = _test_resources_client_env_from_settings(self._settings_file)
        if env is None:
            return False
        if _test_resources_server_available(self._settings_file):
            return True
        if self._session_file.exists():
            self._emit_status("[test-resources] stale session detected; starting owned server instead")
        else:
            self._emit_status("[test-resources] external settings detected but server unavailable; starting owned server instead")
        return False

    def _delegate_test_resources_server(
        self,
        args: list[str],
        *,
        runner: RunnerWithEnv,
        resolver: Callable[[str], str | None],
        java_home_provider: JavaHomeProvider | None = None,
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


def _direct_tui_arguments(args: Sequence[str]) -> list[str]:
    """Return direct-source arguments for the delegating TUI."""
    result: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token in {"--test", "--smoke", "--non-interactive", "--control-panel", "--trace-delegation"}:
            index += 1
            continue
        if token == "--project-dir":
            index += 2
            continue
        if token.startswith("--project-dir="):
            index += 1
            continue
        if token == "--report-dir":
            index += 2
            continue
        if token.startswith("--report-dir="):
            index += 1
            continue
        if token == "--report":
            index += 1
            if index < len(args) and not args[index].startswith("-"):
                index += 1
            continue
        if token.startswith("--report="):
            index += 1
            continue
        result.append(token)
        index += 1
    return result


def _extract_tui_report_path(args: Sequence[str], project_dir: Path) -> Path | None:
    """Resolve the optional direct-source --report path for the TUI."""
    for index, token in enumerate(args):
        if token.startswith("--report="):
            value = token.split("=", 1)[1].strip()
            return Path(value) if value else None
        if token != "--report":
            continue
        if index + 1 < len(args):
            value = args[index + 1]
            if not value.startswith("-") and not value.endswith((".java", ".py")):
                return Path(value)
        return _pyronaut_output_dir(project_dir) / "reports" / "tests"
    return None


def _run_tamboui_tui(
    *,
    project_dir: Path,
    initial_mode: str,
    control_panel: bool,
    report_dir: Path,
    trace_delegation: bool,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    java_home_provider: JavaHomeProvider | None = None,
    toolchain_type: str | None = None,
    direct_dev_executable: str | None = None,
    direct_args: Sequence[str] | None = None,
) -> int:
    tui_executable = _resolve_required_tui_executable(resolver)
    if tui_executable is None:
        return PRECONDITION_FAILED

    try:
        base_env = _build_java_home_env("tui", java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    base_env = _apply_project_virtualenv(base_env, project_dir)

    tr_session: _OwnedTestResourcesSession | None = None
    test_resources_env_overrides: dict[str, str] | None = None
    try:
        if _test_resources_enabled(project_dir) or not (project_dir / "pyproject.toml").exists():
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

    if direct_dev_executable is not None:
        delegated = {}
    else:
        delegated = {
            "validate-config": _resolve_delegate_executable_path("validate-config", ["--project-dir", str(project_dir)], resolver),
            "install": _resolve_delegate_executable_path("install", ["--project-dir", str(project_dir)], resolver),
            "process": _resolve_delegate_executable_path("process", ["--project-dir", str(project_dir)], resolver),
            "run": _resolve_delegate_executable_path("run", ["--project-dir", str(project_dir)], resolver),
            "test": _resolve_delegate_executable_path("test", ["--project-dir", str(project_dir)], resolver),
        }
    missing = [name for (name, path) in delegated.items() if path is None]
    if missing:
        print("Missing delegated executable(s): " + ", ".join(f"pyronaut-{name}" for name in missing), file=sys.stderr)
        return PRECONDITION_FAILED
    pyronaut_table = _read_pyproject_pyronaut_table(project_dir)
    if isinstance(pyronaut_table, dict):
        configured_toolchain = pyronaut_table.get("toolchain")
        if not isinstance(configured_toolchain, dict) or "type" not in configured_toolchain:
            toolchain_type = None
    else:
        toolchain_type = None
    if direct_dev_executable is not None:
        native_commands = []
    else:
        native_commands = None
    try:
        if native_commands is None:
            native_commands = [
                command
                for command in ("validate-config", "install", "process", "run", "test")
                if _use_pyronaut_dev_native_toolchain(
                    command,
                    project_dir,
                    pyronaut_table=pyronaut_table,
                )
            ]
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    native_dev_executable = None
    if native_commands:
        try:
            native_dev_executable = _resolve_pyronaut_dev_native_executable(resolver) or resolver(DEV_NATIVE_EXECUTABLE)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED
        if native_dev_executable is None:
            print(
                "Missing native delegated executable for pyronaut-dev. Build or install pyronaut-dev, or set tool.pyronaut.toolchain.type = 'jvm'.",
                file=sys.stderr,
            )
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
    ]
    if toolchain_type is not None:
        command_line.extend(["--toolchain-type", toolchain_type])
    if direct_dev_executable is not None:
        command_line.extend(["--direct-dev-executable", direct_dev_executable])
        for value in direct_args or ():
            command_line.extend(["--direct-arg", value])
    else:
        command_line.extend([
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
        ])
    if native_dev_executable is not None:
        command_line.extend(["--native-dev-executable", native_dev_executable])
        for command in native_commands:
            command_line.extend(["--native-command", command])
    if control_panel:
        command_line.append("--control-panel")
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
