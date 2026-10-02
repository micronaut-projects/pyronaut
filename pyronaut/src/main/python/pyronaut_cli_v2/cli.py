from __future__ import annotations

import atexit
import contextlib
import filecmp
import fnmatch
from functools import lru_cache
import hashlib
import importlib.metadata
import json
import shlex
import shutil
import signal
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
from typing import Callable, Iterable, Iterator, NamedTuple, Protocol, Sequence

from .progress import PROGRESS_EPOCH_ENV as _PROGRESS_EPOCH_ENV
from .progress import LaunchIndicator as _LaunchIndicator
from .progress import console as _progress_console
from .progress import format_bytes as _format_bytes
from .progress import progress_epoch_ms as _progress_epoch_ms
from . import aot_cache as _aot_cache
from . import doctor as _doctor

SUCCESS = 0
USAGE_ERROR = 2
PRECONDITION_FAILED = 8
PLATFORM_UNSUPPORTED = 9
INTERNAL_ERROR = 10

SUPPORTED_COMMANDS = {"setup", "update", "doctor", "clean", "install", "process", "dev", "run", "test", "build", "create", "validate-config", "test-resources-server"}
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
JAR_BUILD_EXECUTABLE = "pyronaut-jar-build"
PYTHON_RUN_EXECUTABLE = "pyronaut-run-python"
PACKAGING_FORMATS = {
    "fat-jar",
    "wheel-jvm",
    "wheel-native",
    "wheel-crema",
    "docker-jvm",
    "docker-native",
    "docker-crema",
}
DEFAULT_PACKAGING_FORMAT = "wheel-jvm"
_DEFAULT_JDK_VERSION = "25"
_DEFAULT_GRAALVM_DOWNLOAD_VERSION = f"{_DEFAULT_JDK_VERSION}i4"
_GDS_DOWNLOAD_URL = "https://gds.oracle.com/download/graal"
_SONATYPE_SNAPSHOTS_REPOSITORY = "https://central.sonatype.com/repository/maven-snapshots/"
_NATIVE_IMAGE_BASE_URL = "https://github.com/micronaut-projects/pyronaut/releases"
# Layout of an unpacked native image bundle in the shared version/platform cache
# directory. Version 4 nests copied language resources under the bundle's own
# "resources" directory; version 5 records the files contributed by each bundle.
# Bump this whenever the unpacked layout changes so an older cache is rebuilt
# rather than silently reused.
_NATIVE_IMAGE_BUNDLE_FORMAT = 5
_NATIVE_IMAGE_COMMANDS = {"pyronaut-dev", "pyronaut-run", "pyronaut-run-python"}
_SETUP_IMAGE_COMMANDS = ("pyronaut-dev", "pyronaut-run", "pyronaut-run-python")
# Jars a native launcher cannot run when they are loaded at runtime. The macOS watch
# service in micronaut-runtime-osx calls into JNA, whose native dispatch library fails
# in a Crema image (NoClassDefFoundError: java/lang/Object), and the failure stops the
# application context. Without them the image's default WatchService is used.
_NATIVE_UNSUPPORTED_ARTIFACT_IDS = frozenset({"micronaut-runtime-osx", "directory-watcher"})
_SETUP_SCHEMA_VERSION = 1
_SETUP_REQUIRED_MESSAGE = "Pyronaut setup is missing or stale. Run pyronaut setup."
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
# `pyronaut dev` and `pyronaut test` JVMs live too briefly, and restart too
# often, for the Graal host JIT to pay off: it competes with startup for CPU.
# Compile host code with C2, which compiles far less eagerly, and collect with
# ParallelGC, as micronaut-core does for its Python test JVMs. Truffle keeps
# compiling Python code, so options configured under graalpy.engine still apply.
_SHORT_LIVED_JVM_FLAGS = (
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:-UseJVMCICompiler",
    "-XX:+UseParallelGC",
)
_JDWP_FLAGS ="-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
_TEST_RESOURCES_ENV_TO_PROPERTY = {
    "MICRONAUT_TEST_RESOURCES_SERVER_URI": "micronaut.test.resources.server.uri",
    "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN": "micronaut.test.resources.server.access.token",
    "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT": "micronaut.test.resources.server.client.read.timeout",
    "MICRONAUT_TEST_RESOURCES_PROJECT_PATH_URI": "micronaut.test.resources.project-path-uri",
}
# Makes the Test Resources client a no-op, and stops the native pyronaut-dev
# bridge from loading it, instead of letting it read ~/.micronaut settings.
_TEST_RESOURCES_DISABLED_JVM_ARGS = (
    "-Dmicronaut.test.resources.enabled=false",
    "-Dpyronaut.dev.test.resources.bridge.enabled=false",
)
_TEST_RESOURCES_DISABLED_ENV = "PYRONAUT_TEST_RESOURCES_DISABLED"
# Env overrides recording `--disable-test-resources` for the launch builders.
_TEST_RESOURCES_DISABLED_OVERRIDES = {_TEST_RESOURCES_DISABLED_ENV: "true"}
# Names the server's log directory for the launcher we delegate to. Container
# pulls happen while the launcher owns the terminal, so the launcher mirrors
# that log itself: a line written here would be repainted away by its live
# progress region, taking a row of that region with it.
_TEST_RESOURCES_LOGS_DIR_ENV = "PYRONAUT_TEST_RESOURCES_LOGS_DIR"
_DEFAULT_PYTHON_SOURCE_DIR = "src"
_DEFAULT_PYTHON_TEST_DIR = "tests"
_DEFAULT_JAVA_SOURCE_DIR = "src-java"
_DEFAULT_JAVA_TEST_DIR = "test-java"
_DEFAULT_RESOURCES_DIR = "config"
_DEFAULT_TEST_RESOURCES_DIR = "tests-config"
_ANSI_YELLOW = "\033[33m"
_ANSI_RESET = "\033[0m"
_MICRONAUT_STARTER_RELEASES_URL = "https://github.com/micronaut-projects/micronaut-starter/releases/download"
_MICRONAUT_CREATE_MIN_VERSION = (5, 2, 0)
_MICRONAUT_CREATE_FIXED_ARGS = ("--lang", "python", "--build", "pyronaut", "--test", "pytest")
# Keep this list aligned with PythonFeatureValidator in micronaut-starter. It
# is deliberately a denylist: starter remains the source of truth for feature
# validation, while this keeps the local catalog from advertising known JVM-
# only features.
_PYRONAUT_CREATE_DENYLIST_BY_MINOR: dict[tuple[int, int], frozenset[str]] = {
    (5, 2): frozenset({
        "jackson-databind", "sourcegen-generator",
        "aws-codebuild-workflow-ci", "github-workflow-azure-container-instance",
        "github-workflow-azure-container-instance-graalvm", "github-workflow-ci",
        "github-workflow-docker-registry", "github-workflow-google-cloud-run",
        "github-workflow-google-cloud-run-graalvm", "github-workflow-graal-docker-registry",
        "github-workflow-oracle-cloud-functions", "github-workflow-oracle-cloud-functions-graalvm",
        "gitlab-workflow-ci", "google-cloud-workflow-ci", "oracle-cloud-devops-build-ci",
        "chatbots-basecamp-http", "chatbots-telegram-http", "http-client-jdk", "knative", "kubernetes",
        "config4k", "properties", "yaml", "data-hibernate-reactive", "hibernate-jpa", "hibernate-reactive-jpa",
        "jasync-sql", "mybatis", "assertj", "awaitility", "buildless", "hamcrest", "junit-params", "lombok",
        "mockito", "openrewrite", "aws-parameter-store", "aws-secrets-manager", "azure-key-vault",
        "coherence-distributed-configuration", "config-consul", "config-kubernetes", "gcp-secrets-manager",
        "netflix-archaius", "oracle-cloud-vault", "groovy-datetime", "groovy-dateutil", "groovy-ginq",
        "groovy-json", "groovy-sql", "groovy-toml", "groovy-xml", "groovy-yaml", "aws-alexa", "graalpy",
        "kapt", "kotlin-extension-functions", "ksp", "amazon-cloudwatch-logging", "azure-logging", "gcp-logging",
        "jul-to-slf4j", "liquibase-slf4j", "log4j2", "oracle-cloud-logging", "slf4j-simple", "slf4j-simple-logger",
        "jmx", "crac", "jib", "micronaut-aot", "shade", "opensearch-restclient",
        "http-poja", "http-server-jdk", "jetty-server", "ktor", "tomcat-server", "undertow-server",
        "amazon-api-gateway", "amazon-api-gateway-http", "aws-lambda", "aws-lambda-custom-runtime", "azure-function",
        "oracle-function", "discovery-kubernetes", "json-path", "json-smart", "junit-platform-suite-engine",
        "test-netty-leak", "hibernate-validator", "views-react",
    }),
}
_allow_draft_release = False
_validated_setup_manifest: dict[str, object] | None = None
# The project whose pyproject.toml enabled tool.pyronaut.toolchain.aot-cache
# for this invocation, or None when JVM launches run without an AOT cache.
_aot_cache_project_dir: Path | None = None


def _read_version_properties() -> dict[str, str]:
    """Return the wheel's ``version.properties`` (empty for a source checkout)."""
    values: dict[str, str] = {}
    version_file = Path(__file__).with_name("version.properties")
    if version_file.is_file():
        for line in version_file.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def _print_version() -> None:
    values = _read_version_properties()
    print(f"Pyronaut: {values.get('pyronaut', 'unknown')}")
    print(f"Micronaut Core: {values.get('micronaut.core', 'unknown')}")
    print(f"Micronaut Platform: {values.get('micronaut.platform', 'unknown')}")
    print(f"GraalPy: {values.get('graalpy', 'unknown')}")
    print(f"GraalPy Interpreter: {values.get('graalpy.pyenv', 'unknown')}")
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
    global _allow_draft_release, _validated_setup_manifest, _aot_cache_project_dir
    _allow_draft_release = _extract_flag(argv, "--allow-draft-release")
    _validated_setup_manifest = None
    option_argv, application_argv = _split_application_args(argv)
    argv = [value for value in option_argv if value != "--allow-draft-release"] + application_argv

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
    enforce_setup = (
        resolver is None
        and runner is None
        and runner_with_env is None
        and process_runner is None
    )

    if not argv:
        _print_usage()
        return USAGE_ERROR

    if argv[0] in {"-h", "--help"}:
        _print_usage()
        return SUCCESS

    if argv[0] in {"-V", "--version"}:
        _print_version()
        return SUCCESS

    command = argv[0]
    _aot_cache_project_dir = _aot_cache_project(argv[1:])
    if command == "setup":
        setup_args = list(argv[1:])
        if _extract_flag(setup_args, "--help") or _extract_flag(setup_args, "-h"):
            _print_setup_usage()
            return SUCCESS
        if not _is_supported_platform(current_platform):
            print("Pyronaut CLI v2 phase 1 supports macOS and Linux only.", file=sys.stderr)
            return PLATFORM_UNSUPPORTED
        return _run_setup(setup_args, execute)

    if command == "update":
        update_args = list(argv[1:])
        if _extract_flag(update_args, "--help") or _extract_flag(update_args, "-h"):
            _print_update_usage()
            return SUCCESS
        if not _is_supported_platform(current_platform):
            print("Pyronaut CLI v2 phase 1 supports macOS and Linux only.", file=sys.stderr)
            return PLATFORM_UNSUPPORTED
        return _run_update(update_args, execute)

    if command == "create":
        return _run_create(list(argv[1:]), execute, current_platform)

    if command == "doctor":
        doctor_args = list(argv[1:])
        if _extract_flag(doctor_args, "--help") or _extract_flag(doctor_args, "-h"):
            _print_doctor_usage()
            return SUCCESS
        return _run_doctor(doctor_args)

    if command == "clean":
        clean_args = list(argv[1:])
        if _extract_flag(clean_args, "--help") or _extract_flag(clean_args, "-h"):
            _print_clean_usage()
            return SUCCESS
        return _run_clean(clean_args)

    option_args = argv[: argv.index("--")] if "--" in argv else argv
    if "-V" in option_args[1:] or (
        command != "build" and "--version" in option_args[1:]
    ):
        _print_version()
        return SUCCESS

    if "--tui" in argv or argv[0] == "--tui":
        if (not any(value in {"-h", "--help", "-V", "--version"} for value in option_args)
                and enforce_setup and _setup_is_required()):
            if not _require_setup(argv):
                return PRECONDITION_FAILED
        tui_args = _split_tui_mode_token([value for value in argv if value != "--tui"])[1]
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

    forwarded_args = _normalize_project_flag(list(argv[1:]))
    forwarded_args = _normalize_no_cache_flag(forwarded_args)
    forwarded_args = _normalize_tests_selection_flag(forwarded_args)
    no_validate = _extract_no_validate(forwarded_args)
    forwarded_args = _remove_no_validate(forwarded_args)
    continuous = _extract_continuous(forwarded_args)
    forwarded_args = _remove_continuous(forwarded_args)
    try:
        debug_vm = _extract_debug_vm(forwarded_args)
    except ValueError as exc:
        return _usage_error(str(exc))
    forwarded_args = _remove_debug_vm(forwarded_args)

    if command not in SUPPORTED_COMMANDS:
        if _looks_like_direct_source_invocation(argv):
            if enforce_setup and _setup_is_required() and not _require_setup(argv):
                return PRECONDITION_FAILED
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

    if command == "build" and (_extract_flag(forwarded_args, "--help") or _extract_flag(forwarded_args, "-h")):
        _print_build_usage()
        return SUCCESS

    if _extract_flag(forwarded_args, "--help") or _extract_flag(forwarded_args, "-h"):
        _print_usage()
        return SUCCESS

    if enforce_setup and _setup_is_required() and not _require_setup(forwarded_args):
        return PRECONDITION_FAILED

    if debug_vm:
        port_ok, error = _check_port_available(5005)
        if not port_ok:
            print(
                "Cannot enable --debug-vm because port 5005 is already in use." + (f" ({error})" if error else ""),
                file=sys.stderr,
            )
            return PRECONDITION_FAILED

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
                debug_vm=debug_vm,
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
            debug_vm=debug_vm,
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
                debug_vm=debug_vm,
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
            debug_vm=debug_vm,
        )

    if not _is_supported_project_platform(current_platform):
        print(
            "Pyronaut CLI v2 phase 1 supports macOS and Linux; Windows project commands require "
            "PYRONAUT_DEV_NATIVE_EXECUTABLE.",
            file=sys.stderr,
        )
        return PLATFORM_UNSUPPORTED

    project_dir = _extract_project_dir(forwarded_args)
    # Report locations are selected by the native test launcher from the
    # project layout. Do not forward the CLI's internal report path option;
    # pyronaut-test intentionally does not expose --report-dir.
    try:
        no_cache = _extract_no_cache(forwarded_args)
        local_repository = _extract_local_repository(forwarded_args)
    except ValueError as exc:
        return _usage_error(str(exc))
    delegated_args = _strip_no_cache_flag(forwarded_args) if command in {"dev", "run", "test"} else forwarded_args
    delegated_args = _strip_local_repository_args(delegated_args) if command in {"dev", "run", "test"} else delegated_args
    delegated_args = (
        _strip_disable_test_resources_flag(delegated_args)
        if command in {"dev", "run", "test"}
        else delegated_args
    )
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
                platform_name=current_platform,
            )
        return _run_build(
            args=forwarded_args,
            runner=execute,
            resolver=locate,
            no_cache=no_cache,
            no_validate=no_validate,
            java_home_provider=effective_java_home_provider,
            platform_name=current_platform,
        )

    if command == "test-resources-server" and _is_test_resources_start(forwarded_args):
        install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
        if no_cache:
            install_args.append("--refresh")
        install_code = _delegate("install", install_args, execute, locate, java_home_provider=effective_java_home_provider)
        if install_code != SUCCESS:
            return install_code

    tr_session: _OwnedTestResourcesSession | None = None
    test_resources_env_overrides: dict[str, str] | None = None
    try:
        # Test Resources enablement for external builds comes from the layout
        # produced by install. Refresh it before deciding whether to own a
        # server; otherwise a stale layout from an earlier invocation can
        # incorrectly start Test Resources.
        external_install_done = False
        if command == "test" and _is_external_build_project(Path(project_dir)):
            install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
            if no_cache:
                install_args.append("--no-cache")
            install_code = _delegate("install", install_args, execute, locate, java_home_provider=effective_java_home_provider)
            if install_code != SUCCESS:
                return install_code
            external_install_done = True
        disable_test_resources_requested = _extract_flag(forwarded_args, "--disable-test-resources")
        test_resources_enabled = (
            not disable_test_resources_requested
            and _test_resources_enabled(Path(project_dir))
        )
        if command in {"dev", "run", "test"} and disable_test_resources_requested:
            # The flag is stripped from delegated_args; carry it to the
            # launch builders so they disable the client explicitly.
            test_resources_env_overrides = dict(_TEST_RESOURCES_DISABLED_OVERRIDES)
        if command in {"dev", "test"} and test_resources_enabled:
            tr_session = _OwnedTestResourcesSession(
                project_dir=Path(project_dir).resolve(),
                owner_command=shlex.join(["pyronaut", command, *forwarded_args]),
            )

        if auto_restart_mode:
            if no_validate:
                _progress_console().note("Configuration validation skipped (--no-validate)")
            else:
                validation_code = _run_lifecycle_validation(
                    project_dir=project_dir,
                    scenario=command,
                    runner=execute,
                    resolver=locate,
                    no_cache=no_cache,
                    env_overrides=test_resources_env_overrides,
                    java_home_provider=effective_java_home_provider,
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
                    java_home_provider=effective_java_home_provider,
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
                    java_home_provider=effective_java_home_provider,
                )
                if preflight_code != SUCCESS:
                    return preflight_code
            if no_validate:
                _progress_console().note("Configuration validation skipped (--no-validate)")
            else:
                validation_code = _run_lifecycle_validation(
                    project_dir=project_dir,
                    scenario=command,
                    runner=execute,
                    resolver=locate,
                    no_cache=no_cache,
                    env_overrides=test_resources_env_overrides,
                    java_home_provider=effective_java_home_provider,
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
                    java_home_provider=effective_java_home_provider,
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
                env_overrides=test_resources_env_overrides,
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
                    external_install_done=external_install_done,
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
                external_install_done=external_install_done,
            )
            return test_exit_code

        if command == "install" and not _has_direct_install_sources(forwarded_args):
            install_project_dir = Path(project_dir).resolve()
            if (install_project_dir / "pyproject.toml").is_file() or (install_project_dir / "requirements.txt").is_file():
                venv_code = _ensure_project_virtualenv(
                    install_project_dir,
                    execute,
                    refresh=_extract_flag(forwarded_args, "--refresh"),
                    offline=_extract_offline(forwarded_args),
                )
                if venv_code != SUCCESS:
                    return venv_code
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
    if command == "install":
        project_dir = Path(_extract_project_dir(args)).resolve()
        configured_local_repository = _extract_local_repository(args)
        _seed_bundled_pyronaut_maven_repository(
            project_dir,
            configured_local_repository,
            resolver("pyronaut-install"),
        )

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
            env_overrides=env_overrides,
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
            env = _apply_test_resources_disabled_java_tool_options(env, project_dir, args, env_overrides)
            if command == "test":
                env = _with_launcher_jvm_options(env, executable_path, _SHORT_LIVED_JVM_FLAGS)
            forwarded_args = _strip_orchestrator_only_args(
                [value for value in args if value not in {"--jvm", "--native"}]
            )
            command_line = [executable_path, *forwarded_args]
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
            command_line = [executable_path, *_strip_orchestrator_only_args(args)]
            if _delegation_trace_enabled():
                print(shlex.join(command_line), file=sys.stderr)
            try:
                env = _build_non_test_resources_env(command, java_home_provider)
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
            env = _merge_env_overrides(env, env_overrides)
            project_dir = Path(_extract_project_dir(args)).resolve()
            env = _apply_project_virtualenv(env, project_dir)
            env = _apply_test_resources_disabled_java_tool_options(env, project_dir, args, env_overrides)
            env = _with_launcher_jvm_options(env, executable_path, _SHORT_LIVED_JVM_FLAGS)
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
    install_args = list(args)
    if command == "install" and _extract_local_repository(install_args) is None:
        configured_local_repository = _read_env(LOCAL_REPOSITORY_ENV)
        if configured_local_repository:
            install_args.extend(["--local-repository", configured_local_repository])
    command_line = [executable_path, *install_args]
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
    debug_vm: bool = False,
) -> int:
    forwarded_args = _strip_orchestrator_only_args(
        [value for value in args if value not in {"--jvm", "--native"}]
    )
    try:
        executable_path = _resolve_direct_source_dev_executable(forwarded_args, resolver, debug_vm=debug_vm)
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
    if debug_vm:
        env = _apply_debug_vm_env(env)
    env = _apply_direct_source_jvm_options(env, executable_path, command, args, debug_vm=debug_vm)
    jvm_args = _build_direct_source_native_jvm_args(
        executable_path,
        env,
        command=command,
        environment=_default_environment(command, forwarded_args),
        # Keep orchestration flags available while constructing JVM
        # properties. In particular, --control-panel is stripped from the
        # delegated argument list below but must still enable its classpath.
        args=args,
    )
    forwarded_args = [
        "-Dmicronaut.control-panel.enabled=true" if value == "--control-panel" else value
        for value in forwarded_args
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
    debug_vm: bool = False,
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
            debug_vm=debug_vm,
        )
    try:
        executable_path = _resolve_direct_source_dev_executable(args, resolver, debug_vm=debug_vm)
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
    if debug_vm:
        env = _apply_debug_vm_env(env)
    env = _apply_direct_source_jvm_options(env, executable_path, command, args, debug_vm=debug_vm)
    if command == "dev":
        env = _apply_project_virtualenv(env, Path.cwd())
    snapshot = _snapshot_direct_source_inputs(args)
    with _sigterm_as_keyboard_interrupt():
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


def _resolve_direct_source_dev_executable(
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool = False,
) -> str | None:
    """Select the direct-source launcher, honoring the command-line override."""
    if _direct_source_uses_jvm(args, debug_vm=debug_vm):
        bundled = _bundled_executable(DEV_NATIVE_EXECUTABLE)
        return str(bundled) if bundled is not None and bundled.exists() else resolver(DEV_NATIVE_EXECUTABLE)
    return _resolve_pyronaut_dev_native_executable(resolver) or resolver(DEV_NATIVE_EXECUTABLE)


def _direct_source_uses_jvm(args: Sequence[str], *, debug_vm: bool = False) -> bool:
    if debug_vm:
        # JDWP needs a HotSpot JVM; the native launcher cannot load the agent.
        return True
    mode = _extract_build_mode_flag(args)
    if mode is None and (Path.cwd() / "pyproject.toml").is_file():
        mode = _read_pyproject_toolchain_type(Path.cwd())
    return mode == TOOLCHAIN_TYPE_JVM


def _apply_direct_source_jvm_options(
    env: dict[str, str] | None,
    executable_path: str,
    command: str,
    args: Sequence[str],
    *,
    debug_vm: bool = False,
) -> dict[str, str] | None:
    if command not in {"dev", "test"} or not _direct_source_uses_jvm(args, debug_vm=debug_vm):
        return env
    return _with_launcher_jvm_options(env, executable_path, _SHORT_LIVED_JVM_FLAGS)

def _apply_debug_vm_env(env: dict[str, str] | None) -> dict[str, str]:
    """Enable JDWP for launcher-script delegates through JAVA_TOOL_OPTIONS."""
    updated = dict(env) if env is not None else dict(os.environ)
    existing = updated.get("JAVA_TOOL_OPTIONS", "").strip()
    updated["JAVA_TOOL_OPTIONS"] = f"{existing} {_JDWP_FLAGS}".strip()
    return updated


@contextlib.contextmanager
def _sigterm_as_keyboard_interrupt() -> Iterator[None]:
    """Route SIGTERM through the Ctrl-C path so child processes and atexit cleanup run.

    The default SIGTERM action terminates the interpreter without running atexit
    handlers, which leaks the Test Resources server retained across direct-source
    restarts when an IDE, process manager or ``kill`` stops ``pyronaut dev``.
    """
    if threading.current_thread() is not threading.main_thread():
        yield
        return

    def _interrupt(signum, frame):  # noqa: ARG001
        raise KeyboardInterrupt

    previous = signal.signal(signal.SIGTERM, _interrupt)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


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

    direct_args = _strip_orchestrator_only_args(args)
    forwarded_args = [
        "-Dmicronaut.control-panel.enabled=true" if value == "--control-panel" else value
        for value in direct_args
        if value not in {"--jvm", "--native"}
    ]
    command_line = [
        executable_path,
        *_build_direct_source_native_jvm_args(
            executable_path,
            env,
            command=command,
            environment=_default_environment(command, direct_args),
            # --control-panel is removed from direct_args before delegation;
            # retain the original flags for JVM-property construction.
            args=args,
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
            _stop_managed_process(process, interrupted=True)
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
                jvm_args.append(f"-Dpyronaut.dev.application.class.path={classpath}")
        if "--control-panel" in args or any(value == "-Dmicronaut.control-panel.enabled=true" for value in args):
            # Resolve Control Panel artifacts bundled with the launcher wheel;
            # do not resolve them from project manifests or Maven local.
            # pyronaut-dev adds them to the direct-source runtime classloader,
            # selecting optional panels once declared dependencies resolve.
            # Keep every panel module on that one loader: the core module
            # reads each panel's default configuration through its own
            # classloader, and optional panels must see the application's
            # libraries (for example, Hikari for the datasource panel).
            bundled_control_panel = _direct_control_panel_classpath_entries(executable_path)
            if bundled_control_panel:
                jvm_args.append(f"-Dpyronaut.dev.control.panel.class.path={os.pathsep.join(bundled_control_panel)}")
                classpath = os.pathsep.join(
                    entry for entry in classpath.split(os.pathsep)
                    if entry and not _is_control_panel_artifact(Path(entry).name)
                )
        if classpath:
            # PyronautDevMain creates the runtime classloader from the
            # java.class.path property in the native image.
            jvm_args.append(f"-Djava.class.path={classpath}")
    return jvm_args


def _direct_control_panel_classpath_entries(executable_path: str) -> list[str]:
    entries: list[str] = []
    executable = Path(executable_path)
    launcher_root = executable.parent.parent
    launcher_lib = launcher_root / "lib"
    if Path(executable_path).name in _NATIVE_IMAGE_COMMANDS:
        packaged_root = _packaged_tool_dir(Path(executable_path).name)
        packaged_lib = packaged_root / "lib"
        if (packaged_lib / "control-panel").is_dir():
            launcher_root = packaged_root
            launcher_lib = packaged_lib
        else:
            # Native images are cached separately from the resolved JVM tool
            # distribution. The latter owns the optional Control Panel jars.
            resolved_root = _resolved_tool_distribution_dir("pyronaut-dev")
            if resolved_root is not None:
                launcher_root = resolved_root
                launcher_lib = resolved_root / "lib"
    bundled_dir = launcher_lib / "control-panel"
    descriptor = launcher_root / "bin" / "pyronaut-tool-classpath.tsv"
    if descriptor.is_file():
        for line in descriptor.read_text(encoding="utf-8").splitlines():
            fields = line.split("\t")
            if len(fields) == 7 and fields[0] == "control-panel":
                entry = bundled_dir / fields[6]
                if entry.is_file() and not entry.name.endswith("-sources.jar"):
                    entries.append(str(entry))
        if entries:
            return list(dict.fromkeys(entries))
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
            short_lived=command in {"dev", "test"},
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
    short_lived: bool = False,
) -> tuple[list[str], dict[str, str] | None]:
    project_dir = Path(_extract_project_dir(args)).resolve()
    env = _build_non_test_resources_env(command, java_home_provider)
    env = _merge_env_overrides(env, env_overrides)
    env = _apply_project_virtualenv(env, project_dir)
    java_exec = _resolve_java_executable(env)
    classpath = _build_delegate_classpath(command, project_dir, resolver, env_overrides)
    jvm_args = _build_delegate_jvm_args(debug_vm, short_lived=short_lived)
    if (environment := _default_environment(command, args)) is not None:
        jvm_args.append(f"-Dmicronaut.environments={environment}")
    jvm_args.extend(_build_test_resources_jvm_args(env_overrides))
    jvm_args.extend(_test_resources_disabled_jvm_args(project_dir, args, env_overrides))

    forwarded_args = _strip_orchestrator_only_args(
        [value for value in args if value not in {"--jvm", "--native"}]
    )
    command_line = [java_exec, *jvm_args, "-cp", classpath, JAVA_MAIN_BY_COMMAND[command], *forwarded_args]
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


def _build_delegate_classpath(
    command: str,
    project_dir: Path,
    resolver: Callable[[str], str | None],
    env_overrides: dict[str, str] | None = None,
) -> str:
    client_disabled = _test_resources_client_disabled(project_dir, env_overrides=env_overrides)
    cache_dir = _pyronaut_output_dir(project_dir)
    external = _read_external_layout(project_dir)
    if external is not None:
        key = "developmentRuntimeClasspath" if command == "dev" else "runtimeClasspath" if command == "run" else "testClasspath"
        entries = list(external.get(key, []))
        if command in {"dev", "test"} and not client_disabled:
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
    if client_disabled:
        # The pyronaut-test launcher ships the Test Resources client for
        # projects that use it. Keep it off the classpath when disabled.
        delegate_entries = [
            entry for entry in delegate_entries
            if not _is_native_test_resources_client_artifact(Path(entry).name)
        ]
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
            if test_classes_dir.is_dir():
                # Test processing merges production and test sources. Put its
                # VFS first so package initializers include test-only Java
                # imports instead of being shadowed by production output.
                entries.append(str(test_classes_dir.resolve()))
            if classes_dir.is_dir():
                entries.append(str(classes_dir.resolve()))
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
            if not _test_resources_client_disabled(project_dir):
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
        if test_classes_dir.is_dir():
            entries.append(str(test_classes_dir.resolve()))
        if classes_dir.is_dir():
            entries.append(str(classes_dir.resolve()))
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


def _native_launcher_compile_classpath_entries(
    launcher_executable: str | None,
    local_repository: str | None = None,
) -> list[str]:
    if not launcher_executable:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    image_name = Path(launcher_executable).name
    if image_name not in _NATIVE_IMAGE_COMMANDS:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    if local_repository or not _setup_is_required():
        if not _native_launcher_manifest_entries(
            launcher_executable, "native-compile-classpath.txt"
        ):
            return []
        entries = _native_compile_descriptor_entries(launcher_executable)
        repositories = []
        configured_repository = (
            _setup_local_repository(("--local-repository", local_repository))
            if local_repository
            else _setup_local_repository(())
        )
        for repository in (configured_repository, Path.cwd() / ".pyronaut-m2", Path.home() / ".m2" / "repository"):
            repository = repository.resolve()
            if repository not in repositories:
                repositories.append(repository)
        executable_path = Path(launcher_executable).resolve()
        library_roots = []
        for library_root in (
            executable_path.parent / "lib",
            executable_path.parent.parent / "lib",
            executable_path.parents[2] / "install" / f"micronaut-{image_name}" / "lib",
        ):
            library_root = library_root.resolve()
            if library_root not in library_roots:
                library_roots.append(library_root)
        resolved = []
        for entry in entries:
            fields = entry.split("\t")
            path = next(
                (
                    repository.joinpath(*fields[1].split("."), fields[2], fields[3], fields[6])
                    for repository in repositories
                    if repository.joinpath(*fields[1].split("."), fields[2], fields[3], fields[6]).is_file()
                ),
                None,
            )
            if path is None:
                path = next((root / fields[6] for root in library_roots if (root / fields[6]).is_file()), None)
            if path is None:
                raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
            resolved.append(path)
        if resolved:
            return [str(path) for path in resolved]
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    try:
        manifest = _validated_setup_manifest or _read_valid_setup_manifest(())
        image = manifest["images"][image_name]
        entries = _native_compile_descriptor_entries(launcher_executable)
        if image["descriptorSha256"] != _native_descriptor_hash(entries):
            raise ValueError("native descriptor changed")
        resolved = image["classpath"]
        if len(resolved) != len(entries) or not all(isinstance(path, str) and Path(path).is_file() for path in resolved):
            raise ValueError("native classpath is incomplete")
        return list(resolved)
    except (KeyError, OSError, RuntimeError, TypeError, ValueError) as exc:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE) from exc


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
    if _versioned_jar_artifact_id(file_name) in _NATIVE_UNSUPPORTED_ARTIFACT_IDS:
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


# Optional Control Panel modules and the application artifacts that enable
# them. Keep in sync with io.micronaut.pyronaut.config.model.ControlPanelFeature.
_CONTROL_PANEL_FEATURES: dict[str, frozenset[str]] = {
    "micronaut-control-panel-datasource": frozenset({"micronaut-jdbc"}),
    "micronaut-control-panel-hibernate": frozenset({"hibernate-core"}),
    "micronaut-control-panel-kafka": frozenset({"micronaut-kafka"}),
    "micronaut-control-panel-object-storage": frozenset({"micronaut-object-storage-core"}),
    "micronaut-control-panel-cache": frozenset({
        "micronaut-cache-caffeine",
        "micronaut-cache-ehcache",
        "micronaut-cache-hazelcast",
        "micronaut-cache-infinispan",
    }),
}


def _select_control_panel_entries(bundled: Sequence[str], application_entries: Iterable[str]) -> list[str]:
    """Keep optional panel modules only when the application uses the library they inspect."""
    application_artifact_ids = _versioned_jar_artifact_ids(Path(entry).name for entry in application_entries)
    selected: list[str] = []
    for entry in bundled:
        triggers = _CONTROL_PANEL_FEATURES.get(_versioned_jar_artifact_id(Path(entry).name) or "")
        if triggers is None or triggers & application_artifact_ids:
            selected.append(entry)
    return selected


def _control_panel_application_entries(project_dir: Path) -> list[str]:
    """Return the unfiltered dev classpath used to detect optional panels."""
    if not _pyronaut_output_dir(project_dir).is_dir():
        return []
    try:
        return _build_native_application_classpath_entries("dev", project_dir)
    except RuntimeError:
        return []


def _is_test_launcher_provided_artifact(entry: str) -> bool:
    file_name = Path(entry).name
    return (
        file_name.startswith("micronaut-context-python-")
        or file_name.startswith("micronaut-pyronaut-pytest-")
    )


def _build_native_test_resources_client_classpath(
    project_dir: Path,
    env_overrides: dict[str, str] | None = None,
) -> str:
    if _test_resources_client_disabled(project_dir, env_overrides=env_overrides):
        return ""
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
    java_home_provider: JavaHomeProvider | None = None,
) -> int:
    if install:
        install_args = ["--project-dir", project_dir, *_local_repository_install_args(local_repository)]
        if no_cache:
            install_args.append("--no-cache")
        install_code = _delegate(
            "install",
            install_args,
            runner,
            resolver,
            java_home_provider=java_home_provider,
        )
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
    # Native pyronaut-dev resolves its embedded compiler descriptor from the
    # selected local repository. Keep that repository available while building
    # the native command; the delegate strips this orchestration-only option
    # before invoking the Java processor. JVM processor delegates must not see
    # it because their CLI intentionally does not accept resolver options.
    if local_repository:
        try:
            if _use_pyronaut_dev_native_toolchain("process", Path(project_dir), args=process_args):
                process_args.extend(_local_repository_install_args(local_repository))
        except ValueError:
            pass
    # A prior cached processing run may have been interrupted or its output
    # removed. Force regeneration when the expected classes directory is
    # missing; otherwise `dev` can incorrectly reuse the external-build cache
    # and fail during classpath assembly.
    if no_cache:
        process_args.append("--no-cache")
    return _delegate(
        "process",
        process_args,
        runner,
        resolver,
        java_home_provider=java_home_provider,
    )


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
    the CLI's Java classpath. Copying the wheel's Pyronaut module JARs and a
    matching BOM into the selected (or default) local repository gives those
    modules normal Maven coordinates and avoids requiring a snapshot
    publication for every SDK wheel build.
    """
    if install_executable is None:
        return
    repository = local_repository or _read_env(LOCAL_REPOSITORY_ENV)
    target_root = (
        Path(repository).expanduser().resolve()
        if repository is not None
        else Path.home() / ".m2" / "repository"
    )
    launcher_dir = Path(install_executable).resolve().parent.parent
    lib_dirs = (launcher_dir / "lib", _launcher_shared_lib_dir(install_executable))
    pom_dirs = tuple(dict.fromkeys(lib_dir.parent / "maven-poms" for lib_dir in lib_dirs))
    jars = {jar for lib_dir in lib_dirs if lib_dir.is_dir() for jar in lib_dir.glob("micronaut-pyronaut-*.jar")}
    staged_artifacts: dict[str, str] = {}
    for jar in sorted(jars):
        match = re.match(r"^(micronaut-pyronaut-[^-].*)-(\d+[^/]*)\.jar$", jar.name)
        if match is None:
            continue
        artifact, version = match.groups()
        staged_artifacts[artifact] = version
        # The wheel is produced from this repository, whose published group
        # is stable across modules.
        artifact_dir = target_root / "io" / "micronaut" / "pyronaut" / artifact / version
        artifact_dir.mkdir(parents=True, exist_ok=True)
        staged_jar = artifact_dir / jar.name
        if not staged_jar.exists() or staged_jar.stat().st_size != jar.stat().st_size:
            shutil.copy2(jar, staged_jar)
        pom = artifact_dir / f"{artifact}-{version}.pom"
        packaged_pom = next(
            (pom_dir / pom.name for pom_dir in pom_dirs if (pom_dir / pom.name).is_file()),
            None,
        )
        if packaged_pom is not None:
            if not pom.exists() or not filecmp.cmp(pom, packaged_pom, shallow=False):
                shutil.copy2(packaged_pom, pom)
        elif not pom.exists():
            pom.write_text(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                "<modelVersion>4.0.0</modelVersion>"
                f"<groupId>io.micronaut.pyronaut</groupId><artifactId>{artifact}</artifactId>"
                f"<version>{version}</version></project>",
                encoding="utf-8",
            )
    if not staged_artifacts:
        return
    bom_version = sorted(set(staged_artifacts.values()))[0]
    bom_dependencies = "".join(
        "<dependency>"
        "<groupId>io.micronaut.pyronaut</groupId>"
        f"<artifactId>{artifact}</artifactId>"
        f"<version>{version}</version>"
        "</dependency>"
        for artifact, version in sorted(staged_artifacts.items())
    )
    bom = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<modelVersion>4.0.0</modelVersion>"
        "<groupId>io.micronaut.pyronaut</groupId>"
        "<artifactId>micronaut-pyronaut-bom</artifactId>"
        f"<version>{bom_version}</version><packaging>pom</packaging>"
        "<dependencyManagement><dependencies>"
        f"{bom_dependencies}"
        "</dependencies></dependencyManagement></project>"
    )
    bom_dir = target_root / "io" / "micronaut" / "pyronaut" / "micronaut-pyronaut-bom" / bom_version
    bom_dir.mkdir(parents=True, exist_ok=True)
    bom_file = bom_dir / f"micronaut-pyronaut-bom-{bom_version}.pom"
    packaged_bom = next(
        (pom_dir / bom_file.name for pom_dir in pom_dirs if (pom_dir / bom_file.name).is_file()),
        None,
    )
    if packaged_bom is not None:
        if not bom_file.is_file() or not filecmp.cmp(bom_file, packaged_bom, shallow=False):
            shutil.copy2(packaged_bom, bom_file)
    elif not bom_file.is_file() or bom_file.read_text(encoding="utf-8") != bom:
        bom_file.write_text(bom, encoding="utf-8")


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
        "--project-dir", "--project", "--mode", "--main-class",
        "--name", "--version", "--setup", "--local-repository", "--local-repo",
        "--include-native-binary",
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


def _direct_build_arguments(args: Sequence[str], staging_project: Path, root: Path) -> list[str]:
    selectors = set(_direct_build_source_selectors(args))
    docker = _extract_build_docker(args)
    value_options = {"--project-dir", "--project", "--name", "--version", "--setup"}
    result: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--include-native-binary" and index + 1 < len(args):
            result.extend([token, _resolve_native_binary_include(args[index + 1], root)])
            index += 2
            continue
        if token.startswith("--include-native-binary="):
            result.append(
                "--include-native-binary="
                + _resolve_native_binary_include(token.split("=", 1)[1], root)
            )
            index += 1
            continue
        if token.startswith("--native-base="):
            configured = token.split("=", 1)[1]
            # A custom base is a launcher path for host builds; with --docker
            # it names an image and is passed through unchanged.
            if _is_custom_native_base_build(configured) and not docker:
                native_base = Path(configured).expanduser()
                result.append(
                    "--native-base="
                    + str(native_base if native_base.is_absolute() else (root / native_base).resolve())
                )
            else:
                result.append(token)
            index += 1
            continue
        if token.startswith("--pgo="):
            profiles = []
            for profile in token.split("=", 1)[1].split(","):
                path = Path(profile.strip()).expanduser()
                profiles.append(
                    profile if _is_http_url(profile) or path.is_absolute() else str((root / path).resolve())
                )
            result.append("--pgo=" + ",".join(profiles))
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
    platform_name: str | None,
) -> int:
    root = Path(_extract_project_dir(args)).resolve()
    selectors = _direct_build_source_selectors(args)
    try:
        _extract_build_native_binary_includes(args)
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
            # IDE metadata is only useful for `pyronaut install` on the user's
            # own sources; the build only needs the resolved manifests.
            "--no-ide-support",
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

        return _run_build(
            args=_direct_build_arguments(args, staging_project, root),
            runner=runner,
            resolver=resolver,
            no_cache=no_cache,
            no_validate=no_validate,
            java_home_provider=java_home_provider,
            project_metadata=(project_name, project_version),
            preflight_install=False,
            platform_name=platform_name,
            dist_dir=root / "dist",
            # The staged pyproject.toml is regenerated for every direct build.
            record_native_base=False,
        )
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
    platform_name: str | None = None,
    dist_dir: Path | None = None,
    record_native_base: bool = True,
) -> int:
    if _extract_flag(args, "--help") or _extract_flag(args, "-h"):
        _print_build_usage()
        return SUCCESS

    project_dir = Path(_extract_project_dir(args)).resolve()
    # Direct-source builds stage a project under __pyronaut__ but publish the
    # artifacts next to the user's sources.
    dist_dir = dist_dir or project_dir / "dist"
    verbose = _extract_build_verbose(args)
    try:
        packaging_format = _resolve_packaging_format(project_dir, args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR
    fat_jar = packaging_format == "fat-jar"
    mode = "jvm" if packaging_format in {"fat-jar", "wheel-jvm", "docker-jvm"} else "native"
    docker_build = packaging_format.startswith("docker-")
    static_native = _extract_build_static(args)
    # Bare --native-base selects the bundled default. A path (or, with
    # --docker, an image name) builds that custom base and records it in
    # pyproject.toml; an HTTP(S) URL packages against an existing launcher.
    cli_native_base = _extract_build_native_base(args)
    custom_native_base_build = _is_custom_native_base_build(cli_native_base)
    try:
        additional_native_binaries = _extract_build_native_binary_includes(args)
        pgo_profile_values = _extract_build_pgo_profiles(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return USAGE_ERROR
    if pgo_profile_values and not custom_native_base_build:
        print(
            "--pgo requires a custom native base build: --native-base=<path>, or --docker --native-base=<image>",
            file=sys.stderr,
        )
        return USAGE_ERROR
    configured_native_base = _read_pyproject_build_native_base(project_dir)
    configured_docker_base_image = None
    if docker_build:
        configured_docker_base_image = _read_pyproject_build_docker_config(project_dir).get("base_image")
    selected_native_base = cli_native_base if cli_native_base is not None else configured_native_base
    if packaging_format == "wheel-crema":
        default_native_base = selected_native_base is None or _is_default_native_base(selected_native_base)
    elif packaging_format == "docker-crema":
        if cli_native_base is None and configured_docker_base_image is not None:
            selected_native_base = None
        default_native_base = _is_default_native_base(selected_native_base) or (
            selected_native_base is None and configured_docker_base_image is None
        )
    else:
        default_native_base = False
        selected_native_base = None
    if additional_native_binaries and (
        not docker_build
        or mode != "native"
        or custom_native_base_build
        or not (default_native_base or selected_native_base is not None)
    ):
        print(
            "--include-native-binary requires a native Docker build with a bundled native base",
            file=sys.stderr,
        )
        return USAGE_ERROR
    reusable_base_configured = configured_native_base is not None or (
        packaging_format == "docker-native" and configured_docker_base_image is not None
    )
    if packaging_format in {"wheel-native", "docker-native"} and reusable_base_configured:
        print(
            f"{packaging_format} cannot use a configured native base; select "
            f"{'docker-crema' if docker_build else 'wheel-crema'} instead",
            file=sys.stderr,
        )
        return USAGE_ERROR
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
        _progress_console().note("Configuration validation skipped (--no-validate)")
    else:
        validation_code = _run_lifecycle_validation(
            project_dir=str(project_dir),
            scenario="production",
            runner=runner,
            resolver=resolver,
            no_cache=no_cache,
            java_home_provider=java_home_provider,
        )
        if validation_code != SUCCESS:
            return validation_code

    preflight = _run_preflight(
        str(project_dir),
        no_cache,
        None,
        runner,
        resolver,
        install=preflight_install,
        java_home_provider=java_home_provider,
    )
    if preflight != SUCCESS:
        return preflight

    try:
        pgo_profiles = _resolve_pgo_profiles(
            pgo_profile_values, project_dir=project_dir, offline=_extract_offline(args)
        )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED

    if custom_native_base_build and not docker_build:
        native_base_exit = _run_native_base_build(
            args=args,
            runner=runner,
            resolver=resolver,
            project_dir=project_dir,
            output=_resolve_native_base_output(project_dir, cli_native_base),
            pgo_profiles=pgo_profiles,
            verbose=verbose,
            java_home_provider=java_home_provider,
        )
        if native_base_exit != SUCCESS:
            return native_base_exit
        if record_native_base:
            _record_native_base_configuration(project_dir, "wheel-crema", ("build", "native-base"), cli_native_base)

    if fat_jar:
        return _run_fat_jar_build(
            project_dir=project_dir,
            project_name=project_name,
            project_version=project_version,
            runner=runner,
            resolver=resolver,
            java_home_provider=java_home_provider,
            dist_dir=dist_dir,
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
            custom_base_image=cli_native_base if custom_native_base_build else None,
            pgo_profiles=pgo_profiles,
            record_native_base=record_native_base,
            default_native_base=default_native_base,
            selected_native_base=selected_native_base,
            additional_native_binaries=additional_native_binaries,
        )

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
        configured_base = None
        if packaging_format == "wheel-crema" and selected_native_base is not None and not default_native_base:
            configured_base = selected_native_base
        if default_native_base:
            try:
                configured_base = _bundled_default_native_base(project_dir, platform_name=platform_name)
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
        if default_native_base and configured_base is None:
            print("The default native base is not available for this platform.", file=sys.stderr)
            return PRECONDITION_FAILED
        if default_native_base:
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
                "--native-base",
                "--default-native-base",
                "--default-native-base-path",
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
            try:
                _stage_native_base_executable(
                    configured_base,
                    project_dir=project_dir,
                    target=output_binary,
                    offline=_extract_offline(args),
                )
            except RuntimeError as exc:
                print(str(exc), file=sys.stderr)
                return PRECONDITION_FAILED
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
            wheel_exit = _build_wheel(
                runner,
                python_exec,
                staging_dir=staging_dir,
                dist_dir=dist_dir,
                project_name=project_name,
                offline=_extract_offline(args),
                stage=lambda: _prepare_build_wheel_staging(
                    project_dir=project_dir,
                    staging_dir=staging_dir,
                    project_name=project_name,
                    project_version=project_version,
                    mode="native",
                    main_class=main_class,
                    runner_executable=None,
                    native_bundle_dir=Path(configured_base).parent if default_native_base else None,
                    native_launcher_executable=str(configured_base) if default_native_base else None,
                ),
            )
        if wheel_exit == SUCCESS:
            console = _progress_console()
            console.success(f"Native wheel build complete. Artifacts are in: {_display_path(dist_dir)}")
            console.hint(f"Install with: {python_exec} -m pip install {_display_path(dist_dir)}/*.whl")
            console.hint(f"Native binary staged from: {_display_path(output_binary)}")
        return wheel_exit

    with tempfile.TemporaryDirectory(prefix="pyronaut-build-jvm-") as staging_root:
        staging_dir = Path(staging_root) / "stage"
        exit_code = _build_wheel(
            runner,
            python_exec,
            staging_dir=staging_dir,
            dist_dir=dist_dir,
            project_name=project_name,
            offline=_extract_offline(args),
            stage=lambda: _prepare_build_wheel_staging(
                project_dir=project_dir,
                staging_dir=staging_dir,
                project_name=project_name,
                project_version=project_version,
                mode="jvm",
                main_class=main_class,
                runner_executable=resolver(PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]),
            ),
        )
    if exit_code == SUCCESS:
        console = _progress_console()
        console.success(f"Wheel build complete. Artifacts are in: {_display_path(dist_dir)}")
        console.hint(f"Install with: {python_exec} -m pip install {_display_path(dist_dir)}/*.whl")
        console.hint(f"Run the project with: {project_name}")
    return exit_code


def _build_wheel(
    runner: RunnerWithEnv,
    python_exec: str,
    *,
    staging_dir: Path,
    dist_dir: Path,
    project_name: str,
    offline: bool,
    stage: Callable[[], None],
) -> int:
    """Stage the launcher package and run ``pip wheel`` as one step.

    pip's own output only matters when it fails, so it is shown then.
    """
    console = _progress_console()
    with console.step("Building wheel", done="Built wheel") as step:
        stage()
        wheel_command = [
            python_exec,
            "-m",
            "pip",
            "wheel",
            "--no-deps",
            *(["--no-build-isolation"] if offline else []),
            "--wheel-dir",
            str(dist_dir),
            str(staging_dir),
        ]
        if _delegation_trace_enabled():
            print(shlex.join(wheel_command), file=sys.stderr)
        exit_code = _run_showing_output_on_failure(runner, wheel_command, None)
        step.failed = exit_code != SUCCESS
        if exit_code == SUCCESS:
            prefix = _built_wheel_prefix(project_name)
            built = sorted(dist_dir.glob(f"{prefix}-*.whl"), key=lambda wheel: wheel.stat().st_mtime) if prefix else []
            if built:
                step.done_label = f"Built {built[-1].name}"
    return exit_code


def _run_showing_output_on_failure(runner: RunnerWithEnv, command_line: list[str], env: dict[str, str] | None) -> int:
    """Run a chatty command whose output only matters when it fails.

    Injected runners (tests) are used as they are; the real runner captures
    the output and prints it above the live region on a non-zero exit.
    """
    if runner is not _run_subprocess:
        return runner(command_line, env)
    try:
        completed = subprocess.run(command_line, check=False, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    except KeyboardInterrupt:
        return 130
    except OSError as exception:
        print(f"Failed executing delegated command: {exception}", file=sys.stderr)
        return INTERNAL_ERROR
    if completed.returncode != 0:
        console = _progress_console()
        for line in completed.stdout.splitlines():
            console.print(line)
    return int(completed.returncode)


def _run_fat_jar_build(
    *,
    project_dir: Path,
    project_name: str,
    project_version: str,
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    java_home_provider: JavaHomeProvider | None,
    dist_dir: Path | None = None,
) -> int:
    delegate_executable = resolver(JAR_BUILD_EXECUTABLE)
    if delegate_executable is None:
        print(f"Missing delegated executable: {JAR_BUILD_EXECUTABLE}", file=sys.stderr)
        return PRECONDITION_FAILED

    cache_dir = _pyronaut_output_dir(project_dir)
    classes_dir = cache_dir / "classes"
    if not classes_dir.is_dir():
        print(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.", file=sys.stderr)
        return PRECONDITION_FAILED

    try:
        classpath = [
            entry for entry in _build_delegate_classpath("run", project_dir, resolver).split(os.pathsep)
            if entry and (Path(entry).is_dir() or Path(entry).suffix.lower() == ".jar")
        ]
        env = _build_non_test_resources_env("build", java_home_provider)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED

    external = _read_external_layout(project_dir)
    if external is not None:
        resource_directories = [Path(entry) for entry in external.get("mainResources", []) if Path(entry).is_dir()]
    else:
        layout = _read_pyproject_sources(project_dir)
        configured_resources = [layout.resources_dir, *layout.additional_resources_dirs]
        resource_directories = [
            resolved for configured in configured_resources
            if (resolved := _resolve_layout_dir(project_dir, configured)).is_dir()
        ]

    dist_dir = dist_dir or project_dir / "dist"
    dist_dir.mkdir(parents=True, exist_ok=True)
    output = dist_dir / f"{_jar_file_component(project_name)}-{_jar_file_component(project_version)}.jar"
    with tempfile.TemporaryDirectory(prefix="pyronaut-build-jar-") as temporary:
        classpath_file = Path(temporary) / "classpath.txt"
        classpath_file.write_text("".join(f"{entry}\n" for entry in classpath), encoding="utf-8")
        command = [
            delegate_executable,
            "--output", str(output),
            "--classes-dir", str(classes_dir),
            "--classpath-file", str(classpath_file),
            "--name", project_name,
            "--version", project_version,
            "--main-class", "io.micronaut.pyronaut.run.PyronautRunMain",
        ]
        for resource_directory in resource_directories:
            command.extend(["--resource-dir", str(resource_directory)])
        if _delegation_trace_enabled():
            print(shlex.join(command), file=sys.stderr)
        exit_code = runner(command, env)
    if exit_code != SUCCESS:
        return exit_code
    if not output.is_file():
        print(f"FAT JAR build reported success but no artifact was produced at: {output}", file=sys.stderr)
        return PRECONDITION_FAILED
    console = _progress_console()
    console.success(f"FAT JAR build complete: {_display_path(output)}")
    console.hint(f"Run it with: java -jar {_display_path(output)}")
    return SUCCESS


def _jar_file_component(value: str) -> str:
    normalized = re.sub(r"[^0-9A-Za-z._-]+", "-", value.strip())
    normalized = re.sub(r"-+", "-", normalized).strip("-.")
    return normalized or "application"


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
        try:
            import tomli as tomllib
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
    native_bundle_dir: Path | None = None,
    native_launcher_executable: str | None = None,
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
        _stage_native_bundle_support(binary_path.parent, pyronaut_dir / "native")
        if native_bundle_dir is not None:
            _stage_native_bundle_support(native_bundle_dir, pyronaut_dir / "native")
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
                launcher_executable=native_launcher_executable,
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


def _stage_native_bundle_support(bundle_dir: Path, target_dir: Path) -> None:
    """Keep external native-image runtime inputs beside the staged launcher."""
    target_dir.mkdir(parents=True, exist_ok=True)
    if bundle_dir.resolve() == target_dir.resolve():
        # A native base configured beside its own output already has the
        # bundle laid out correctly; copying it onto itself fails.
        return
    _copytree_if_exists(bundle_dir / "resources", target_dir / "resources")
    for pattern in ("*.so", "*.dylib", "*.dll"):
        for runtime_library in bundle_dir.glob(pattern):
            if runtime_library.is_file():
                target = target_dir / runtime_library.name
                if runtime_library.resolve() != target.resolve():
                    shutil.copy2(runtime_library, target)


def _remove_native_bundle_support(bundle_dir: Path) -> None:
    resources = bundle_dir / "resources"
    if resources.is_dir():
        shutil.rmtree(resources)
    for pattern in ("*.so", "*.dylib", "*.dll"):
        for runtime_library in bundle_dir.glob(pattern):
            if runtime_library.is_file():
                runtime_library.unlink()


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


def _extract_build_native_base(args: Sequence[str]) -> str | None:
    """Return the selected native base; bare ``--native-base`` means ``default``."""
    selected: str | None = None
    for token in args:
        if token == "--":
            break
        if token == "--native-base":
            value = "default"
        elif token.startswith("--native-base="):
            value = token.split("=", 1)[1].strip()
            if not value:
                raise ValueError("Invalid empty value for --native-base")
        else:
            continue
        if selected is not None and selected != value:
            raise ValueError("Conflicting --native-base values; select only one native base")
        selected = value
    return selected


def _is_default_native_base(value: str | None) -> bool:
    return value is not None and value.strip().lower() == "default"


def _is_http_url(value: str) -> bool:
    return urllib.parse.urlparse(value).scheme in {"http", "https"}


def _is_custom_native_base_build(value: str | None) -> bool:
    """Whether ``--native-base=<value>`` names a custom base that the build must create."""
    return value is not None and not _is_default_native_base(value) and not _is_http_url(value)


def _extract_build_pgo_profiles(args: Sequence[str]) -> list[str]:
    """Return the ``--pgo=<profile>[,<profile>...]`` values, which may be repeated."""
    profiles: list[str] = []
    for token in args:
        if token == "--":
            break
        if token == "--pgo":
            raise ValueError("--pgo requires a profile: --pgo=<profile.iprof>[,<profile.iprof>...]")
        if token.startswith("--pgo="):
            values = [value.strip() for value in token.split("=", 1)[1].split(",")]
            if not all(values):
                raise ValueError("Invalid empty value for --pgo")
            profiles.extend(values)
    return profiles


def _resolve_pgo_profiles(profiles: Sequence[str], *, project_dir: Path, offline: bool) -> list[Path]:
    """Resolve local PGO profiles and download HTTP(S) ones under ``__pyronaut__/pgo``."""
    resolved: list[Path] = []
    for index, profile in enumerate(profiles):
        parsed = urllib.parse.urlparse(profile)
        if _is_http_url(profile):
            if not parsed.netloc:
                raise RuntimeError(f"Invalid PGO profile URL: {profile}")
            if offline:
                raise RuntimeError(f"Cannot download PGO profile while offline: {profile}")
            name = Path(parsed.path).name or "profile.iprof"
            path = project_dir / "__pyronaut__" / "pgo" / f"{index}-{name}"
            path.parent.mkdir(parents=True, exist_ok=True)
            try:
                _download_url_with_progress(profile, path, "Downloading PGO profile")
            except Exception as exc:
                path.unlink(missing_ok=True)
                raise RuntimeError(f"Failed downloading PGO profile from {profile}: {exc}") from exc
        elif parsed.scheme and len(parsed.scheme) > 1:
            raise RuntimeError(f"PGO profile must be a local path or HTTP(S) URL: {profile}")
        else:
            path = Path(profile).expanduser()
            path = path if path.is_absolute() else (project_dir / path).resolve()
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f"PGO profile does not exist or is empty: {path}")
        resolved.append(path)
    return resolved


def _extract_build_native_binary_includes(args: Sequence[str]) -> list[str]:
    includes: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--":
            break
        if token == "--include-native-binary":
            if index + 1 >= len(args):
                raise ValueError("Missing value for --include-native-binary")
            value = args[index + 1].strip()
            if not value:
                raise ValueError("Invalid empty value for --include-native-binary")
            includes.append(value)
            index += 2
            continue
        if token.startswith("--include-native-binary="):
            value = token.split("=", 1)[1].strip()
            if not value:
                raise ValueError("Invalid empty value for --include-native-binary")
            includes.append(value)
        index += 1
    return includes


def _resolve_native_binary_include(value: str, root: Path) -> str:
    value = value.strip()
    if value in _NATIVE_IMAGE_COMMANDS:
        return value
    path = Path(value).expanduser()
    return str(path if path.is_absolute() else (root / path).resolve())


def _read_pyproject_build_native_base(project_dir: Path) -> str | None:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return None
    build = pyronaut.get("build")
    if not isinstance(build, dict):
        return None
    return _read_pyproject_string(build, "native-base")


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


def _bundled_default_native_base(project_dir: Path, *, platform_name: str | None = None) -> Path | None:
    launcher = PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]
    return _ensure_native_image(launcher, platform_name=platform_name)


def _stage_native_base_executable(
    configured: str,
    *,
    project_dir: Path,
    target: Path,
    offline: bool,
) -> None:
    """Copy or download the configured native base to ``target`` as one step."""
    console = _progress_console()
    with console.step(f"Staging native base {configured}", done=f"Staged native base {configured}") as step:
        _stage_native_base_executable_into(configured, project_dir=project_dir, target=target, offline=offline)
        step.done_label = f"Staged native base {configured} ({_format_bytes(target.stat().st_size)})"


def _stage_native_base_executable_into(
    configured: str,
    *,
    project_dir: Path,
    target: Path,
    offline: bool,
) -> None:
    parsed = urllib.parse.urlparse(configured)
    target.parent.mkdir(parents=True, exist_ok=True)
    if parsed.scheme in {"http", "https"}:
        if not parsed.netloc:
            raise RuntimeError(f"Invalid native base URL: {configured}")
        if offline:
            raise RuntimeError("Cannot download --native-base while offline")
        _remove_native_bundle_support(target.parent)
        try:
            _download_url_with_progress(configured, target, "Downloading native base")
        except Exception as exc:
            target.unlink(missing_ok=True)
            raise RuntimeError(f"Failed downloading native base from {configured}: {exc}") from exc
    elif parsed.scheme:
        raise RuntimeError("Native base must be a local path or HTTP(S) URL")
    else:
        source = Path(configured).expanduser()
        source = source if source.is_absolute() else (project_dir / source).resolve()
        if not source.is_file():
            raise RuntimeError(
                f"Configured native base does not exist: {source}. Build it with: pyronaut build --native-base={configured}"
            )
        if source.resolve() != target.resolve():
            if source.parent.resolve() != target.parent.resolve():
                _remove_native_bundle_support(target.parent)
            shutil.copy2(source, target)
            _stage_native_bundle_support(source.parent, target.parent)
    if not target.is_file() or target.stat().st_size == 0:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"Native base is empty or missing: {configured}")
    target.chmod(0o755)


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


_TOML_TABLE_HEADER = re.compile(r"^\s*\[\s*([A-Za-z0-9_.-]+)\s*\]\s*(?:#.*)?$")


def _set_toml_string(text: str, table: str, key: str, value: str) -> str:
    """Set ``key = "value"`` in ``[table]``, preserving the rest of the document."""
    assignment = f"{key} = {json.dumps(value)}\n"
    lines = text.splitlines(keepends=True)
    header_index = next(
        (index for index, line in enumerate(lines)
         if (match := _TOML_TABLE_HEADER.match(line)) and match.group(1) == table),
        None,
    )
    if header_index is None:
        prefix = text if not text or text.endswith("\n") else text + "\n"
        return prefix + ("\n" if prefix.strip() else "") + f"[{table}]\n" + assignment
    end = next(
        (index for index in range(header_index + 1, len(lines)) if lines[index].lstrip().startswith("[")),
        len(lines),
    )
    key_pattern = re.compile(rf"^\s*(?:{re.escape(key)}|\"{re.escape(key)}\")\s*=")
    for index in range(header_index + 1, end):
        if key_pattern.match(lines[index]):
            lines[index] = assignment
            return "".join(lines)
    insert_at = end
    while insert_at > header_index + 1 and not lines[insert_at - 1].strip():
        insert_at -= 1
    if insert_at > 0 and not lines[insert_at - 1].endswith("\n"):
        lines[insert_at - 1] += "\n"
    lines.insert(insert_at, assignment)
    return "".join(lines)


def _record_native_base_configuration(
    project_dir: Path, packaging_format: str, key_path: Sequence[str], value: str
) -> None:
    """Point ``pyproject.toml`` at a freshly built custom native base.

    Later ``pyronaut build`` invocations then reuse the base without rebuilding
    it. The edit is verified by re-parsing the document; if the existing layout
    cannot be updated safely, the file is left untouched and the user is told
    which settings to add.
    """
    import copy
    import tomllib

    pyproject = project_dir / "pyproject.toml"
    updates = [
        (("tool", "pyronaut", "packaging"), "format", packaging_format),
        (("tool", "pyronaut", *key_path[:-1]), key_path[-1], value),
    ]
    settings = ", ".join(f"{'.'.join((*table, key))} = {json.dumps(item)}" for table, key, item in updates)
    console = _progress_console()
    try:
        original = pyproject.read_text(encoding="utf-8") if pyproject.is_file() else ""
        expected = copy.deepcopy(tomllib.loads(original))
        updated = original
        for table, key, item in updates:
            updated = _set_toml_string(updated, ".".join(table), key, item)
            target = expected
            for part in table:
                target = target.setdefault(part, {})
            target[key] = item
        if tomllib.loads(updated) != expected:
            raise ValueError("unexpected pyproject.toml layout")
    except (OSError, ValueError) as exc:
        console.warn(f"Could not update {_display_path(pyproject)} ({exc}); set {settings} to reuse the native base")
        return
    if updated != original:
        pyproject.write_text(updated, encoding="utf-8")
        console.note(f"Configured {_display_path(pyproject)}: {settings}")


def _resolve_native_base_output(project_dir: Path, configured: str) -> Path:
    path = Path(configured).expanduser()
    return path if path.is_absolute() else (project_dir / path).resolve()


def _run_native_base_build(
    *,
    args: Sequence[str],
    runner: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    project_dir: Path,
    output: Path,
    verbose: bool,
    java_home_provider: JavaHomeProvider | None,
    pgo_profiles: Sequence[Path] = (),
) -> int:
    try:
        env = _build_non_test_resources_env("build", java_home_provider)
        _build_native_classpath(project_dir)
    except RuntimeError as exc:
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
        "--native-base",
    ]
    if pgo_profiles:
        command.append("--pgo=" + ",".join(str(profile) for profile in pgo_profiles))
    if _is_python_runtime_project(project_dir):
        command.append("--include-python")
    if verbose:
        command.append("--verbose")
    command.extend(_extract_native_build_passthrough_args(args))
    if _delegation_trace_enabled():
        print(shlex.join(command), file=sys.stderr)
    exit_code = runner(command, env)
    if exit_code == SUCCESS and not output.is_file():
        print(f"Native base build reported success but no binary was produced at: {output}", file=sys.stderr)
        return PRECONDITION_FAILED
    if exit_code == SUCCESS:
        _progress_console().success(f"Native base build complete: {_display_path(output)}")
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
    _exclude_native_configuration_resources(pyronaut_dir / "classes")
    _stage_native_build_configuration(project_dir, app_dir)
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


def _prepare_bundled_docker_context(
    *, project_dir: Path, context_dir: Path, launcher_executable: str,
) -> None:
    """Stage application material and dependencies for a prebuilt default runner."""
    app_dir = _prepare_common_docker_app_context(project_dir, context_dir)
    pyronaut_dir = app_dir / "__pyronaut__"
    classes_dir = project_dir / "__pyronaut__" / "classes"
    if not classes_dir.is_dir():
        raise RuntimeError(f"Missing processed classes directory: {classes_dir}. Run pyronaut process first.")
    runtime_manifest = project_dir / "__pyronaut__" / "resolved-runtime-dependencies"
    if not runtime_manifest.exists():
        raise RuntimeError(f"Missing runtime classpath manifest: {runtime_manifest}. Run pyronaut install first.")
    _copytree_if_exists(classes_dir, pyronaut_dir / "classes")
    _exclude_native_configuration_resources(pyronaut_dir / "classes")
    _copytree_if_exists(project_dir / "__pyronaut__" / "schemas", pyronaut_dir / "schemas")
    (pyronaut_dir / "schemas").mkdir(parents=True, exist_ok=True)
    _stage_manifest_artifacts(
        source=runtime_manifest,
        target=pyronaut_dir / "resolved-runtime-dependencies",
        project_dir=project_dir,
        pyronaut_dir=pyronaut_dir,
        launcher_executable=launcher_executable,
    )


def _stage_bundled_native_base(source: Path, target: Path) -> None:
    """Stage only the files belonging to the selected native launcher."""
    metadata_path = source.with_name(source.name + ".json")
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        bundle_files = metadata.get("bundle-files")
    except (OSError, TypeError, ValueError):
        bundle_files = None
    if not isinstance(bundle_files, list) or not all(isinstance(path, str) for path in bundle_files):
        raise RuntimeError(f"Native launcher bundle metadata is missing for {source.name}")
    for relative in bundle_files:
        relative_path = Path(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise RuntimeError(f"Native launcher bundle contains an unsafe path: {relative}")
        source_file = source.parent / relative_path
        if not source_file.is_file():
            raise RuntimeError(f"Native launcher bundle is missing '{relative}'")
        target_file = target / relative_path
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, target_file)


def _stage_additional_native_binaries(
    includes: Sequence[str], *, project_dir: Path, target: Path,
) -> None:
    for include in includes:
        if include in _NATIVE_IMAGE_COMMANDS:
            _stage_bundled_native_base(
                _ensure_native_image(include, platform_name="linux"), target
            )
            continue
        source = Path(include).expanduser()
        source = source if source.is_absolute() else project_dir / source
        if not source.is_file():
            raise RuntimeError(f"Additional native binary does not exist: {include}")
        target_file = target / source.name
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target_file)


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
    _exclude_native_configuration_resources(pyronaut_dir / "classes")
    _stage_native_build_configuration(project_dir, app_dir)
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


def _exclude_native_configuration_resources(classes_dir: Path) -> None:
    """Native Docker images load application configuration from ``/app/config``."""
    suffixes = {".properties", ".toml", ".yaml", ".yml"}
    for resource in classes_dir.iterdir() if classes_dir.is_dir() else ():
        if resource.suffix not in suffixes:
            continue
        if resource.name.startswith("application.") or resource.name.startswith("application-"):
            resource.unlink()


def _stage_native_build_configuration(project_dir: Path, app_dir: Path) -> None:
    """Stage native build resources without embedding runtime application config."""
    source = project_dir / "config"
    target = app_dir / "native-build-config"
    target.mkdir(parents=True, exist_ok=True)
    for entry in source.iterdir() if source.is_dir() else ():
        if entry.is_file() and (
            entry.name.startswith("application.") or entry.name.startswith("application-")
        ):
            continue
        destination = target / entry.name
        if entry.is_dir():
            shutil.copytree(entry, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(entry, destination)


def _manifest_docker_copy_lines(context_dir: Path, *, destination_root: str = "/app") -> list[str]:
    manifest = context_dir / "app" / "__pyronaut__" / "resolved-runtime-dependencies"
    lines = [
        f"COPY app/__pyronaut__/resolved-runtime-dependencies {destination_root}/__pyronaut__/resolved-runtime-dependencies",
    ]
    repository = context_dir / "app" / "__pyronaut__" / "m2-repository"
    if repository.is_dir():
        lines.append(f"COPY app/__pyronaut__/m2-repository/ {destination_root}/__pyronaut__/m2-repository/")
    return lines


def _additional_resource_docker_copy_lines(context_dir: Path, *, destination_root: str = "/app") -> list[str]:
    layout = _read_pyproject_sources(context_dir / "app")
    return [
        f"COPY app/{resource_dir}/ {destination_root}/{resource_dir}/"
        for resource_dir in layout.additional_resources_dirs
        if (context_dir / "app" / resource_dir).is_dir()
    ]


def _write_jvm_dockerfile(*, target: Path, base_image: str, runner_name: str,
                          runtime_copies: Sequence[str]) -> None:
    dockerfile = f"""\
FROM {base_image}
WORKDIR /app
EXPOSE 8080
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
    resource_copies: Sequence[str],
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
    builder_resource_copies = "\n".join(line.replace(" /app/", " /workspace/app/") for line in resource_copies)
    dockerfile = f"""\
FROM {builder_image} AS builder
WORKDIR /workspace
COPY app/pyproject.toml /workspace/app/pyproject.toml
COPY app/native-build-config/ /workspace/app/config/
COPY app/__pyronaut__/classes /workspace/app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /workspace/app/__pyronaut__/schemas
COPY app/__pyronaut__/tools/shared /workspace/app/__pyronaut__/tools/shared
COPY app/__pyronaut__/tools/pyronaut-native-build /workspace/app/__pyronaut__/tools/pyronaut-native-build
{builder_runtime_copies}
{builder_resource_copies}
RUN chmod +x /workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build
RUN {shlex.join(build_command)}
RUN if [ -d /workspace/app/__pyronaut__/native/resources ]; then mv /workspace/app/__pyronaut__/native/resources /workspace/native-language-resources; else mkdir /workspace/native-language-resources; fi

FROM {runtime_image}
WORKDIR /app
EXPOSE 8080
COPY --from=builder /workspace/app/__pyronaut__/native/ /app/
COPY --from=builder /workspace/native-language-resources/ /app/resources/
COPY app/pyproject.toml /app/pyproject.toml
COPY app/config/ /app/config/
{chr(10).join(resource_copies)}
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
    resource_copies: Sequence[str],
    bundled_only: bool = False,
    pgo_profiles: Sequence[str] = (),
) -> None:
    output_binary = f"/workspace/base/{runner_name}"
    build_command = [
        "/workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build",
        "--project-dir", "/workspace/app",
        "--output", output_binary,
        "--native-base",
    ]
    if bundled_only:
        build_command.append("--default-native-base")
    if pgo_profiles:
        build_command.append("--pgo=" + ",".join(pgo_profiles))
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
RUN rm -rf /workspace/app/config && cp -a /workspace/app/native-build-config/. /workspace/app/config/
RUN chmod +x /workspace/app/__pyronaut__/tools/pyronaut-native-build/bin/pyronaut-native-build
RUN {shlex.join(build_command)}

FROM {runtime_image} AS pyronaut-base
WORKDIR /opt/pyronaut
COPY --from=builder /workspace/base/ /opt/pyronaut/bin/

FROM pyronaut-base
WORKDIR /app
EXPOSE 8080
COPY app/pyproject.toml /app/pyproject.toml
COPY app/config/ /app/config/
{chr(10).join(resource_copies)}
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT ["/opt/pyronaut/bin/{runner_name}", "--project-dir", "/app"]
"""
    target.write_text(dockerfile, encoding="utf-8")


def _write_bundled_application_dockerfile(
    *, target: Path, runtime_image: str, runner_name: str,
    runtime_copies: Sequence[str], resource_copies: Sequence[str],
) -> None:
    """Build the application layer directly on top of a bundled runner."""
    target.write_text(
        f"""FROM {runtime_image} AS pyronaut-base
WORKDIR /opt/pyronaut
COPY bundled-base/ /opt/pyronaut/bin/

FROM pyronaut-base
WORKDIR /app
EXPOSE 8080
COPY app/pyproject.toml /app/pyproject.toml
COPY app/config/ /app/config/
{chr(10).join(runtime_copies)}
{chr(10).join(resource_copies)}
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT [\"/opt/pyronaut/bin/{runner_name}\", \"--project-dir\", \"/app\"]
""",
        encoding="utf-8",
    )


def _write_crema_application_dockerfile(
    *, target: Path, base_image: str, runner_name: str, resource_copies: Sequence[str]
) -> None:
    dockerfile = f"""\\
FROM {base_image}
WORKDIR /app
EXPOSE 8080
# Older Crema base images may contain the native-build distribution under the
# application directory. It is only needed while producing the image, never
# at runtime, so remove it from the final application layer.
RUN rm -rf /app/__pyronaut__/tools
COPY app/config/ /app/config/
{chr(10).join(resource_copies)}
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
    custom_base_image: str | None = None,
    pgo_profiles: Sequence[Path] = (),
    record_native_base: bool = True,
    default_native_base: bool = False,
    selected_native_base: str | None = None,
    additional_native_binaries: Sequence[str] = (),
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
            if custom_base_image is not None:
                base_image = custom_base_image
                build_args["PYRONAUT_BASE_IMAGE"] = base_image
                dockerfile = context_dir / "DockerfileNativeBase"
                with _preparing_docker_context():
                    _prepare_native_docker_context(project_dir=project_dir, context_dir=context_dir, resolver=resolver)
                # Profiles are only needed by the builder stage, which sees
                # the context's app/ directory as /workspace/app.
                container_pgo_profiles = []
                for index, profile in enumerate(pgo_profiles):
                    staged = Path("__pyronaut__") / "pgo" / f"{index}-{profile.name}"
                    (context_dir / "app" / staged).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(profile, context_dir / "app" / staged)
                    container_pgo_profiles.append(f"/workspace/app/{staged.as_posix()}")
                _write_crema_base_dockerfile(
                    target=dockerfile,
                    builder_image=builder_image,
                    runtime_image=runtime_image,
                    runner_name=runner_name,
                    include_python=_is_python_runtime_project(project_dir),
                    verbose=verbose,
                    static_native=static_native,
                    passthrough_args=_extract_native_build_passthrough_args(args),
                    pgo_profiles=container_pgo_profiles,
                    resource_copies=_additional_resource_docker_copy_lines(context_dir),
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
                base_exit = _run_docker_command(runner, base_command, image_tag=base_image)
                if base_exit != SUCCESS:
                    return base_exit
                if record_native_base:
                    _record_native_base_configuration(
                        project_dir, "docker-crema", ("build", "docker", "base-image"), base_image
                    )
            elif default_native_base:
                # Docker contexts require a Linux launcher even when the CLI
                # is running on another operating system.
                try:
                    bundled = _bundled_default_native_base(project_dir, platform_name="linux")
                except RuntimeError as exc:
                    print(str(exc), file=sys.stderr)
                    return PRECONDITION_FAILED
                if bundled is None or not bundled.is_file():
                    print("The default native base is not available for this platform.", file=sys.stderr)
                    return PRECONDITION_FAILED
                bundled_dir = context_dir / "bundled-base"
                try:
                    with _preparing_docker_context():
                        _prepare_bundled_docker_context(
                            project_dir=project_dir, context_dir=context_dir, launcher_executable=str(bundled),
                        )
                        _stage_bundled_native_base(bundled, bundled_dir)
                        _stage_additional_native_binaries(
                            additional_native_binaries, project_dir=project_dir, target=bundled_dir
                        )
                except RuntimeError as exc:
                    print(str(exc), file=sys.stderr)
                    return PRECONDITION_FAILED
                (bundled_dir / runner_name).chmod(0o755)
                dockerfile = context_dir / "DockerfileNativeDefault"
                _write_bundled_application_dockerfile(
                    target=dockerfile,
                    runtime_image=runtime_image,
                    runner_name=runner_name,
                    runtime_copies=_manifest_docker_copy_lines(context_dir),
                    resource_copies=_additional_resource_docker_copy_lines(context_dir),
                )
            elif selected_native_base is not None:
                bundled = context_dir / "bundled-base" / runner_name
                try:
                    _stage_native_base_executable(
                        selected_native_base,
                        project_dir=project_dir,
                        target=bundled,
                        offline=_extract_offline(args),
                    )
                except RuntimeError as exc:
                    print(str(exc), file=sys.stderr)
                    return PRECONDITION_FAILED
                try:
                    with _preparing_docker_context():
                        _prepare_bundled_docker_context(
                            project_dir=project_dir,
                            context_dir=context_dir,
                            launcher_executable=str(bundled),
                        )
                        _stage_additional_native_binaries(
                            additional_native_binaries,
                            project_dir=project_dir,
                            target=context_dir / "bundled-base",
                        )
                except RuntimeError as exc:
                    print(str(exc), file=sys.stderr)
                    return PRECONDITION_FAILED
                dockerfile = context_dir / "DockerfileNativeBase"
                _write_bundled_application_dockerfile(
                    target=dockerfile,
                    runtime_image=runtime_image,
                    runner_name=runner_name,
                    runtime_copies=_manifest_docker_copy_lines(context_dir),
                    resource_copies=_additional_resource_docker_copy_lines(context_dir),
                )
            elif (base_image := docker_config.get("base_image")) is not None:
                with _preparing_docker_context():
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
                        resource_copies=_additional_resource_docker_copy_lines(context_dir),
                    )
            else:
                with _preparing_docker_context():
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
                        resource_copies=_additional_resource_docker_copy_lines(context_dir),
                    )
        else:
            with _preparing_docker_context():
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
        exit_code = _run_docker_command(runner, command, image_tag=image_tag)
    if exit_code == SUCCESS:
        console = _progress_console()
        console.success(f"Docker image build complete: {image_tag}")
        console.hint(f"Run it with: docker run --rm -p 8080:8080 {image_tag}")
    return exit_code


@contextlib.contextmanager
def _preparing_docker_context() -> Iterator[None]:
    """Show the staging of launchers, dependencies and resources as one step."""
    with _progress_console().step("Preparing Docker build context", done="Prepared Docker build context"):
        yield


def _display_path(path: Path) -> str:
    """Show ``path`` relative to the working directory when it lies beneath it."""
    try:
        return str(path.resolve().relative_to(Path.cwd().resolve()))
    except (OSError, ValueError):
        return str(path)


def _run_docker_command(runner: RunnerWithEnv, command: list[str], *, image_tag: str) -> int:
    """Run ``docker build`` as a step, letting Docker own the terminal meanwhile."""
    console = _progress_console()
    # Suspend first so the row is never drawn beside Docker's own output and
    # the summary line prints without a redraw in between.
    with console.suspend():
        with console.step(f"Building Docker image {image_tag}", done=f"Built Docker image {image_tag}") as step:
            exit_code = runner(command, None)
            step.failed = exit_code != SUCCESS
    return exit_code


def _launcher_package_name(project_name: str) -> str:
    normalized = re.sub(r"[^0-9A-Za-z_]", "_", project_name.strip().replace("-", "_"))
    normalized = re.sub(r"_+", "_", normalized).strip("_")
    if not normalized:
        normalized = "pyronaut_app"
    if normalized[0].isdigit():
        normalized = f"pyronaut_{normalized}"
    return f"{normalized}_launcher"


def _built_wheel_prefix(project_name: str) -> str:
    """The file name prefix pip gives wheels built for ``project_name``."""
    return re.sub(r"[-_.]+", "_", project_name.strip()).strip("_")


def _remove_existing_built_wheels(dist_dir: Path, project_name: str) -> None:
    prefix = _built_wheel_prefix(project_name)
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
    java_home_provider: JavaHomeProvider | None = None,
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
        java_home_provider=java_home_provider,
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
    packaging_format = _resolve_packaging_format(project_dir, args)
    return "jvm" if packaging_format in {"fat-jar", "wheel-jvm", "docker-jvm"} else "native"


def _resolve_packaging_format(project_dir: Path, args: Sequence[str]) -> str:
    # Parse and validate the configured value even when command-line flags
    # override it. This keeps removed keys and invalid enum values rejected
    # when lifecycle validation is explicitly disabled.
    def present(option: str) -> bool:
        return any(token == option or token.startswith(option + "=") for token in args)

    if present("--base-image") or present("--base-image-output"):
        raise ValueError("Unsupported base-image option; use --native-base=<default|path|image|url>")
    if present("--native-base-output"):
        raise ValueError(
            "Unsupported option --native-base-output; use --native-base=<path> "
            "(or --native-base=<image> with --docker) to build a custom native base"
        )
    configured = _read_pyproject_packaging_format(project_dir)
    jar = _extract_flag(args, "--jar")
    explicit_mode = _extract_build_mode_flag(args)
    docker = _extract_build_docker(args)
    static = _extract_build_static(args)
    native_base_value = _extract_build_native_base(args)

    if jar:
        conflicts = explicit_mode is not None or docker or static or native_base_value is not None or _has_main_class_option(args)
        if conflicts:
            raise ValueError("--jar cannot be combined with --main-class, JVM/native, Docker, static, or native-base options")
        return "fat-jar"

    if explicit_mode is not None or docker or static or native_base_value is not None:
        mode = explicit_mode or ("native" if native_base_value is not None else "jvm")
        if native_base_value is not None and mode == "jvm":
            raise ValueError("--native-base is only supported for native builds")
        if mode == "jvm":
            return "docker-jvm" if docker else "wheel-jvm"
        if native_base_value is not None:
            return "docker-crema" if docker else "wheel-crema"
        return "docker-native" if docker else "wheel-native"

    if configured == "fat-jar" and _has_main_class_option(args):
        if not _packaging_format_configured(project_dir):
            # The FAT JAR default for Java always launches PyronautRunMain; a
            # custom main class keeps the previous JVM wheel default.
            return DEFAULT_PACKAGING_FORMAT
        raise ValueError("--main-class is not supported for fat-jar packaging; PyronautRunMain is always used")
    return configured


def _extract_build_mode_flag(args: Sequence[str]) -> str | None:
    selected: str | None = None
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--native":
            value = "native"
        elif token == "--jvm":
            value = "jvm"
        elif token == "--mode":
            if index + 1 >= len(args):
                raise ValueError("Missing value for --mode. Use native|jvm")
            value = args[index + 1].strip().lower()
            index += 1
            if value not in {"native", "jvm"}:
                raise ValueError("Invalid value for --mode. Use native|jvm")
        elif token.startswith("--mode="):
            value = token.split("=", 1)[1].strip().lower()
            if value not in {"native", "jvm"}:
                raise ValueError("Invalid value for --mode. Use native|jvm")
        else:
            index += 1
            continue
        if selected is not None and selected != value:
            raise ValueError("Conflicting build modes; select only one of --native, --jvm, or --mode")
        selected = value
        index += 1
    return selected


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


def _default_packaging_format(project_dir: Path) -> str:
    """Java applications default to a FAT JAR; Python applications to a wheel."""
    if _is_external_build_project(project_dir):
        return "fat-jar"
    if not (project_dir / "pyproject.toml").is_file():
        return DEFAULT_PACKAGING_FORMAT
    java_root = project_dir / _read_pyproject_sources(project_dir).java_source_dir
    if java_root.is_dir() and any(java_root.rglob("*.java")):
        return "fat-jar"
    return DEFAULT_PACKAGING_FORMAT


def _packaging_format_configured(project_dir: Path) -> bool:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    packaging = pyronaut.get("packaging") if isinstance(pyronaut, dict) else None
    return isinstance(packaging, dict) and "format" in packaging


def _read_pyproject_packaging_format(project_dir: Path) -> str:
    pyronaut = _read_pyproject_pyronaut_table(project_dir)
    if not isinstance(pyronaut, dict):
        return _default_packaging_format(project_dir)
    build = pyronaut.get("build")
    if isinstance(build, dict):
        if "base-image" in build:
            raise ValueError(
                "Unsupported configuration 'tool.pyronaut.build.base-image'; use 'tool.pyronaut.build.native-base'"
            )
        if "mode" in build:
            raise ValueError("Unsupported configuration 'tool.pyronaut.build.mode'; use 'tool.pyronaut.packaging.format'")
    packaging = pyronaut.get("packaging")
    if packaging is None:
        return _default_packaging_format(project_dir)
    if not isinstance(packaging, dict):
        raise ValueError("Invalid [tool.pyronaut.packaging]: expected a table")
    if "format" not in packaging:
        return _default_packaging_format(project_dir)
    value = packaging["format"]
    if not isinstance(value, str):
        raise ValueError("Invalid tool.pyronaut.packaging.format: expected a string")
    normalized = value.strip()
    if normalized not in PACKAGING_FORMATS:
        options = ", ".join(sorted(PACKAGING_FORMATS))
        raise ValueError(f"Invalid tool.pyronaut.packaging.format '{value}'. Use one of: {options}")
    return normalized


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
    shared_server = test_resources.get("shared-server", test_resources.get("sharedServer"))
    return isinstance(shared_server, bool) and shared_server


def _resolve_test_resources_logs_dir(project_dir: Path, settings_file: Path) -> Path:
    test_resources = _read_pyproject_test_resources_table(project_dir)
    if isinstance(test_resources, dict):
        # "logs-dir" is the spelling the pyproject schema documents; the
        # camel-cased form is accepted as well.
        configured = test_resources.get("logs-dir")
        if not isinstance(configured, str):
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

    venv_bin = _virtualenv_bin(venv_dir)
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


def _virtualenv_bin(venv_dir: Path) -> Path:
    scripts = venv_dir / "Scripts"
    return scripts if scripts.is_dir() else venv_dir / "bin"


def _resolve_virtualenv_python(venv_bin: Path) -> Path | None:
    for name in ("python.exe", "python3.exe", "python", "python3"):
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
    sanitized.pop(_TEST_RESOURCES_LOGS_DIR_ENV, None)
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
        if token in {"--native", "--jvm", "--jar", "--verbose", "--no-cache", "--no-validate", "--offline", "--docker", "--static", "--native-base"} or token.startswith(("--native-base=", "--pgo=")):
            index += 1
            continue
        if token in {"--mode", "--main-class", "--project-dir", "--local-repository", "--local-repo", "--setup", "--name", "--version", "--python-src", "--java-src", "--include-native-binary"}:
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
            or token.startswith("--local-repository=")
            or token.startswith("--local-repo=")
            or token.startswith("--setup=")
            or token.startswith("--name=")
            or token.startswith("--version=")
            or token.startswith("--python-src=")
            or token.startswith("--java-src=")
            or token.startswith("--include-native-binary=")
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
    # On the JVM toolchain the application runs in the reloading development
    # runtime, which watches and compiles the sources itself: only a change to
    # the dependencies needs a new process.
    source_snapshotter = snapshotter
    reload_in_process = _dev_reload_in_process(project_root, run_args, debug_vm=debug_vm, resolver=resolver)
    snapshotter = _snapshot_dependency_inputs if reload_in_process else source_snapshotter
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
                java_home_provider=java_home_provider,
            )
            if preflight_code != SUCCESS:
                return preflight_code

        # A change to pyproject.toml may switch the toolchain, the reload mode or
        # the dependencies that provide micronaut-dev: decide again, and watch
        # the sources again when the next process cannot reload them itself.
        reload_now = _dev_reload_in_process(project_root, run_args, debug_vm=debug_vm, resolver=resolver)
        if reload_now != reload_in_process:
            reload_in_process = reload_now
            snapshotter = _snapshot_dependency_inputs if reload_in_process else source_snapshotter
            snapshot = snapshotter(project_root)

        try:
            command_line, env = _build_dev_delegate_invocation(
                run_args,
                resolver,
                debug_vm=debug_vm,
                env_overrides=env_overrides,
                java_home_provider=java_home_provider,
                reload_in_process=reload_in_process,
            )
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return PRECONDITION_FAILED

        if _delegation_trace_enabled():
            print(shlex.join(command_line), file=sys.stderr)

        relaunch_marker = _pyronaut_output_dir(project_root).joinpath(*_DEV_RELAUNCH_MARKER)
        relaunch_marker.unlink(missing_ok=True)
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
                    if reload_in_process and int(code) == DEV_RELAUNCH_STATUS and relaunch_marker.is_file():
                        relaunch_marker.unlink(missing_ok=True)
                        # The development runtime spent its generation budget and
                        # closed: start it again, from the classes it compiled.
                        print("Dev mode: the generation budget is spent, relaunching the application.", file=sys.stderr)
                        snapshot = snapshotter(project_root)
                        initial_preflight_done = False
                        break
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
                                java_home_provider=java_home_provider,
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
                            java_home_provider=java_home_provider,
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
            _stop_managed_process(process, interrupted=True)
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
    external_install_done: bool = False,
) -> tuple[int, dict[str, str] | None]:
    prepare_code, test_resources_env_overrides = _prepare_test_cycle(
        project_dir=project_dir,
        no_cache=no_cache,
        execute=execute,
        resolver=resolver,
        tr_session=tr_session,
        test_resources_env_overrides=test_resources_env_overrides,
        no_validate=no_validate,
        java_home_provider=java_home_provider,
        local_repository=local_repository,
        external_install_done=external_install_done,
    )
    if prepare_code != SUCCESS:
        return prepare_code, test_resources_env_overrides
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


def _prepare_test_cycle(
    *,
    project_dir: Path,
    no_cache: bool,
    execute: RunnerWithEnv,
    resolver: Callable[[str], str | None],
    tr_session: _OwnedTestResourcesSession | None,
    test_resources_env_overrides: dict[str, str] | None,
    no_validate: bool,
    java_home_provider: JavaHomeProvider | None,
    local_repository: str | None,
    external_install_done: bool = False,
    process_pass: str = "all",
) -> tuple[int, dict[str, str] | None]:
    """
    What a test run needs before its tests run: an external build's
    processing, the owned Test Resources server, the configuration validation
    and the processing of the project.
    """
    if _is_external_build_project(project_dir):
        preflight_code = _run_preflight(
            str(project_dir),
            no_cache,
            local_repository,
            execute,
            resolver,
            install=not external_install_done
            and not (_pyronaut_output_dir(project_dir) / "project-layout.properties").exists(),
            process_pass="all",
            java_home_provider=java_home_provider,
        )
        if preflight_code != SUCCESS:
            return preflight_code, test_resources_env_overrides
    # The validator must see the newly started owned server. Starting it only
    # after validation leaves a stale URI from the previous dev session.
    if tr_session is not None and test_resources_env_overrides is None:
        tr_session.ensure_started(runner=execute, resolver=resolver, java_home_provider=java_home_provider)
        test_resources_env_overrides = tr_session.client_env_overrides()
    if no_validate:
        _progress_console().note("Configuration validation skipped (--no-validate)")
    else:
        validation_code = _run_lifecycle_validation(
            project_dir=str(project_dir),
            scenario="test",
            runner=execute,
            resolver=resolver,
            no_cache=no_cache,
            env_overrides=test_resources_env_overrides,
            java_home_provider=java_home_provider,
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
            process_pass=process_pass,
            java_home_provider=java_home_provider,
        )
        if preflight_code != SUCCESS:
            return preflight_code, test_resources_env_overrides
    return SUCCESS, test_resources_env_overrides


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
    external_install_done: bool = False,
) -> int:
    project_root = project_dir.resolve()
    if _test_reload_in_process(project_root, delegated_args, debug_vm=debug_vm, resolver=resolver):
        return _run_tests_in_process(
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
            # without a terminal no key can end the session: run the tests once
            once=input_reader is None and not _stdin_is_terminal(),
        )
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
                    external_install_done=external_install_done,
                )
                # Only the first cycle can reuse the install performed by run().
                external_install_done = False
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


TEST_CONTINUOUS_RESTART = "restart"
TEST_CONTINUOUS_PROCESS = "process"
TEST_RELOAD_MAIN = "io.micronaut.pyronaut.dev.PyronautTestReload"
# Asks the native pyronaut-dev image to run its test command in test mode.
TEST_RELOAD_PROPERTY = "pyronaut.test.reload"
# The artifacts a native pyronaut-dev image holds when its test command can run
# in test mode, in process.
_NATIVE_TEST_RUNTIME_ARTIFACTS = frozenset({"io.micronaut:micronaut-dev", "io.micronaut:micronaut-dev-test-report"})
# Written by test mode before it exits with DEV_RELAUNCH_STATUS.
_TEST_RELAUNCH_MARKER = ("micronaut-dev", "test", "relaunch")
# The options of the test command that test mode supports; any other runs the
# tests in a new process for every run, as before.
_TEST_RELOAD_OPTIONS = {"--project-dir", "--tests", "--verbose", "--debug-vm", "--jvm", "--native"}
_TEST_RELOAD_VALUE_OPTIONS = {"--project-dir", "--tests"}
_TEST_MODE_RUNNER_CLASS = "io/micronaut/dev/test/TestRunner.class"
_MICRONAUT_DEV_MODULES = ("micronaut-dev", "micronaut-dev-test-report", "micronaut-dev-livereload")
_JUNIT_PLATFORM_LAUNCHER_JAR = re.compile(r"^junit-platform-launcher-\d[^/\\]*\.jar$")


def _read_test_continuous(project_dir: Path) -> str:
    """How pyronaut test -t runs the tests again: tool.pyronaut.test.continuous, 'restart' by default."""
    data = _read_pyproject_data(project_dir)
    section = data.get("tool", {}) if isinstance(data, dict) else {}
    for key in ("pyronaut", "test"):
        section = section.get(key, {}) if isinstance(section, dict) else {}
    value = section.get("continuous", TEST_CONTINUOUS_RESTART) if isinstance(section, dict) else TEST_CONTINUOUS_RESTART
    return value if value in {TEST_CONTINUOUS_RESTART, TEST_CONTINUOUS_PROCESS} else TEST_CONTINUOUS_RESTART


def _test_reload_supports_args(args: Sequence[str]) -> bool:
    """Whether test mode supports every option the test command would receive."""
    option_args, _ = _split_application_args(_strip_orchestrator_only_args(args))
    skip_next = False
    for token in option_args:
        if skip_next:
            skip_next = False
            continue
        name = token.split("=", 1)[0]
        if not token.startswith("-"):
            return False
        if name not in _TEST_RELOAD_OPTIONS and not name.startswith("--verbose"):
            return False
        if token in _TEST_RELOAD_VALUE_OPTIONS:
            skip_next = True
    return True


def _test_reload_in_process(
    project_dir: Path,
    args: Sequence[str],
    *,
    debug_vm: bool = False,
    resolver: Callable[[str], str | None] | None = None,
) -> bool:
    """
    Whether pyronaut test -t runs the tests in one process, in the test mode of
    the micronaut-dev runtime, rather than processing the project and starting
    a new test process for every run.

    The conditions are those of reloading dev mode: a Pyronaut project, not an
    external build, with no restart exclusion inside a watched root, and the
    command's options must be ones test mode supports. On the JVM toolchain the
    development runtime must hold micronaut-dev with its test mode; on the
    native toolchain the pyronaut-dev image must hold it, with its live test
    report, as the image's parent tier.
    """
    if _is_external_build_project(project_dir) or _read_test_continuous(project_dir) == TEST_CONTINUOUS_PROCESS:
        return False
    if not _test_reload_supports_args(args):
        return False
    if _restart_excludes_under_watched_roots(project_dir, tests=True):
        return False
    try:
        native = (
            not debug_vm
            and _extract_build_mode_flag(args) != TOOLCHAIN_TYPE_JVM
            and _read_pyproject_test_mode(project_dir) == TOOLCHAIN_TYPE_NATIVE
        ) or _use_pyronaut_dev_native_toolchain("test", project_dir, debug_vm=debug_vm, args=args)
    except ValueError:
        return False
    if native:
        executable = _resolve_pyronaut_dev_native_executable(resolver or _resolve_executable)
        return executable is not None and _NATIVE_TEST_RUNTIME_ARTIFACTS <= _native_launcher_provided_artifact_coordinates(executable)
    manifest = _pyronaut_output_dir(project_dir) / "resolved-development-runtime-dependencies"
    try:
        entries = _read_manifest_entries(manifest)
    except RuntimeError:
        return False
    return any(
        _MICRONAUT_DEV_JAR.match(Path(entry).name) and _has_test_mode(Path(entry))
        for entry in entries
    )


def _has_test_mode(micronaut_dev_jar: Path) -> bool:
    """Whether a micronaut-dev jar has the test mode, which a snapshot before it lacks."""
    try:
        with zipfile.ZipFile(micronaut_dev_jar) as jar:
            jar.getinfo(_TEST_MODE_RUNNER_CLASS)
        return True
    except (OSError, KeyError, zipfile.BadZipFile):
        return False


def _run_tests_in_process(
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
    once: bool = False,
) -> int:
    """
    Continuous testing in one JVM: validate and process the project once, then
    run the test mode of the development runtime, which compiles every change
    in process and runs the tests it affects on a new class loader generation,
    and reads the keys of the terminal itself until it exits. Without a
    terminal, as in CI, the tests run once, as the loop's first cycle does.
    """
    # The development runtime compiles the tests itself, against the processed
    # classes: processing them here would be thrown away.
    prepare_code, test_resources_env_overrides = _prepare_test_cycle(
        project_dir=project_dir,
        no_cache=no_cache,
        execute=execute,
        resolver=resolver,
        tr_session=tr_session,
        test_resources_env_overrides=test_resources_env_overrides,
        no_validate=no_validate,
        java_home_provider=java_home_provider,
        local_repository=local_repository,
        process_pass="main",
    )
    if prepare_code != SUCCESS:
        return prepare_code
    try:
        command_line, env = _build_test_reload_invocation(
            delegated_args,
            resolver,
            debug_vm=debug_vm,
            env_overrides=test_resources_env_overrides,
            java_home_provider=java_home_provider,
        )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    if once:
        command_line.insert(_test_reload_property_index(command_line), "-Dmicronaut.dev.test.once=true")
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    relaunch_marker = _pyronaut_output_dir(project_dir).joinpath(*_TEST_RELAUNCH_MARKER)
    try:
        while True:
            relaunch_marker.unlink(missing_ok=True)
            code = execute(command_line, env)
            if int(code) == DEV_RELAUNCH_STATUS and relaunch_marker.is_file():
                relaunch_marker.unlink(missing_ok=True)
                # The development runtime spent its generation budget, which a
                # native image has since it never unloads a generation's
                # classes, and closed: start it again, from what it compiled.
                print("Continuous testing: the generation budget is spent, relaunching the test process.", file=sys.stderr)
                continue
            return code
    except KeyboardInterrupt:
        return 130


def _test_reload_property_index(command_line: Sequence[str]) -> int:
    """Where a system property goes: before -cp on the JVM, before the command of the native image."""
    if "-cp" in command_line:
        return command_line.index("-cp")
    return command_line.index("test")


def _stdin_is_terminal() -> bool:
    stream = sys.stdin
    try:
        return bool(stream is not None and stream.isatty())
    except (AttributeError, ValueError, OSError):
        return False


def _build_test_reload_invocation(
    args: Sequence[str],
    resolver: Callable[[str], str | None],
    *,
    debug_vm: bool,
    env_overrides: dict[str, str] | None,
    java_home_provider: JavaHomeProvider | None,
) -> tuple[list[str], dict[str, str] | None]:
    """
    The test command's JVM invocation, its classpath and options, running the
    test mode launcher of pyronaut-dev instead: with the Pyronaut compiler, the
    launcher's own jar and micronaut-dev, its live HTML report and its
    LiveReload server.
    """
    project_dir = Path(_extract_project_dir(args)).resolve()
    native = _pyronaut_dev_native_command_line(
        "test",
        args,
        resolver,
        debug_vm=debug_vm,
        env_overrides=env_overrides,
        java_home_provider=java_home_provider,
    )
    if native is not None:
        return _native_test_reload_invocation(native, project_dir, env_overrides, java_home_provider)
    command_line, env = _build_java_delegate_invocation(
        "test",
        [value for value in args if value not in {"--jvm", "--native"}],
        resolver,
        debug_vm=debug_vm,
        env_overrides=env_overrides,
        java_home_provider=java_home_provider,
        # C2 and ParallelGC, as for the test JVM this one replaces
        short_lived=True,
    )
    command_line[command_line.index(JAVA_MAIN_BY_COMMAND["test"])] = TEST_RELOAD_MAIN
    cache_dir = _pyronaut_output_dir(project_dir)
    dev_jar = _pyronaut_dev_jar(resolver)
    if dev_jar is None:
        raise RuntimeError(
            "Continuous testing in one JVM needs the pyronaut-dev launcher. Run pyronaut setup, "
            "or set tool.pyronaut.test.continuous = 'process' to start a new test process for every run."
        )
    runtime = _test_mode_runtime_entries(
        _read_manifest_entries(cache_dir / "resolved-development-runtime-dependencies")
    )
    classpath_index = command_line.index("-cp") + 1
    classpath = command_line[classpath_index].split(os.pathsep)
    if not any(_JUNIT_PLATFORM_LAUNCHER_JAR.match(Path(entry).name) for entry in classpath):
        raise RuntimeError(
            "Continuous testing in one JVM needs junit-platform-launcher on the test classpath. "
            "Run pyronaut install, or set tool.pyronaut.test.continuous = 'process'."
        )
    build_entries = _read_manifest_entries(cache_dir / "resolved-build-dependencies")
    compiler = _dev_compiler_classpath(build_entries, resolver)
    command_line[classpath_index] = os.pathsep.join(
        dict.fromkeys([*_without_other_versions(classpath, compiler), *compiler, *runtime, dev_jar])
    )
    return command_line, env


def _native_test_reload_invocation(
    command_line: list[str],
    project_dir: Path,
    env_overrides: dict[str, str] | None,
    java_home_provider: JavaHomeProvider | None,
) -> tuple[list[str], dict[str, str] | None]:
    """
    The native pyronaut-dev test command, running the test mode of the
    micronaut-dev runtime the image holds. Its classpath, the parent tier, is
    the project's dependencies alone: the processed classes and the resource
    directories are the reloadable tier, read through each generation, which
    a parent-first class path would hide.
    """
    command_line = list(command_line)
    for index, value in enumerate(command_line):
        if value.startswith("-Djava.class.path="):
            entries = value.split("=", 1)[1].split(os.pathsep)
            command_line[index] = "-Djava.class.path=" + os.pathsep.join(
                entry for entry in entries if entry and Path(entry).suffix == ".jar"
            )
    # The image holds the development runtime: the tests run in this process,
    # each generation's classes defined at runtime, until the generation
    # budget is spent.
    command_line.insert(command_line.index("test"), f"-D{TEST_RELOAD_PROPERTY}=true")
    env = _build_non_test_resources_env("test", java_home_provider)
    env = _merge_env_overrides(env, env_overrides)
    env = _apply_project_virtualenv(env, project_dir)
    return command_line, env


def _test_mode_runtime_entries(development_entries: Sequence[str]) -> list[str]:
    """
    micronaut-dev, micronaut-dev-test-report and micronaut-dev-livereload from
    the development runtime; a module it lacks, as after an install by an older
    Pyronaut, is taken from the Maven repository micronaut-dev comes from, at
    its version, when it is there.
    """
    by_module: dict[str, str] = {}
    for entry in development_entries:
        name = Path(entry).name
        for module in _MICRONAUT_DEV_MODULES:
            if re.match(rf"^{re.escape(module)}-\d[^/\\]*\.jar$", name):
                by_module.setdefault(module, entry)
    dev = by_module.get("micronaut-dev")
    if dev is None:
        raise RuntimeError(
            "Continuous testing in one JVM needs micronaut-dev (Micronaut 5.3 or later) in the development runtime. "
            "Run pyronaut install, or set tool.pyronaut.test.continuous = 'process'."
        )
    dev_path = Path(dev)
    version = dev_path.parent.name
    entries = [dev]
    for module in _MICRONAUT_DEV_MODULES[1:]:
        entry = by_module.get(module)
        if entry is None:
            sibling = dev_path.parent.parent.parent / module / version / f"{module}-{version}.jar"
            entry = str(sibling) if sibling.is_file() else None
        if entry is not None:
            entries.append(entry)
    return entries


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
    reload_in_process: bool = False,
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
            control_panel = _select_control_panel_entries(
                _direct_control_panel_classpath_entries(executable_path),
                _control_panel_application_entries(project_dir),
            )
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
        command_index = dev_command_line.index("run")
        # A native image reads no JAVA_TOOL_OPTIONS: the convenience properties
        # (--port, --property, -D) go on its command line, ahead of the command.
        native_properties = [*dev_jvm_args]
        if reload_in_process:
            # The image holds the development runtime of micronaut-dev: the
            # application reloads in this process, each generation's classes
            # defined at runtime, until the generation budget is spent.
            native_properties.append(f"-D{DEV_RELOAD_PROPERTY}=true")
        dev_command_line[command_index:command_index] = native_properties
        env = _build_non_test_resources_env("dev", java_home_provider)
        if dev_jvm_args:
            env["JAVA_TOOL_OPTIONS"] = " ".join(dev_jvm_args)
        env = _merge_env_overrides(env, env_overrides)
        env = _apply_project_virtualenv(env, project_dir)
        return dev_command_line, env
    if (
        _read_pyproject_toolchain_type(project_dir) == TOOLCHAIN_TYPE_NATIVE
        and _extract_build_mode_flag(args) != TOOLCHAIN_TYPE_JVM
    ):
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
        short_lived=True,
    )
    classpath_index = command_line.index("-cp") + 1
    classpath = _build_delegate_classpath("dev", project_dir, resolver, env_overrides)
    # The JVM fallback is executed by the production runner. Do not append the
    # pyronaut-dev distribution: it contains compiler, test, GraalPy and
    # tooling artifacts which can make the application classpath enormous and
    # accidentally expose Python runtime classes to Java projects.
    runner_name = PYTHON_RUN_EXECUTABLE if _is_python_runtime_project(project_dir) else COMMAND_TO_EXECUTABLE["run"]
    runner_executable = resolver(runner_name)
    if runner_executable is not None:
        classpath = os.pathsep.join([classpath, *_delegate_lib_entries(runner_executable)])
    command_line[classpath_index] = classpath
    test_resources_client_classpath = _build_native_test_resources_client_classpath(project_dir, env_overrides)
    if test_resources_client_classpath:
        command_line.insert(classpath_index - 1, f"-Dpyronaut.dev.test.resources.client.classpath={test_resources_client_classpath}")
    for jvm_arg in reversed([
        *_build_test_resources_jvm_args(env_overrides),
        *_test_resources_disabled_jvm_args(project_dir, args, env_overrides),
    ]):
        if jvm_arg not in command_line:
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
    if reload_in_process:
        # The development runtime compiles changed sources in this JVM with the
        # Pyronaut compiler, which must be on the launch classpath, as the
        # Kotlin compiler is for Gradle. The annotation processors are not: the
        # compiler loads them from the processor path in a loader of their own,
        # so they stay out of the application's class loading. The runtime
        # entries come first, so their versions win.
        build_entries = _read_manifest_entries(_pyronaut_output_dir(project_dir) / "resolved-build-dependencies")
        # The reloading launcher itself lives in pyronaut-dev: only its own jar
        # joins, the rest of that distribution stays out of the application.
        dev_jar = _pyronaut_dev_jar(resolver)
        if dev_jar is None:
            raise RuntimeError(
                "Reloading in dev mode needs the pyronaut-dev launcher. Run pyronaut setup, "
                "or set tool.pyronaut.dev.reload = 'process' to restart the process for every change."
            )
        classpath_index = command_line.index("-cp") + 1
        compiler = _dev_compiler_classpath(build_entries, resolver)
        command_line[classpath_index] = os.pathsep.join(
            dict.fromkeys([
                *_without_other_versions(command_line[classpath_index].split(os.pathsep), compiler),
                *compiler,
                dev_jar,
            ])
        )
        command_line.insert(command_line.index("-cp"), f"-D{DEV_RELOAD_PROPERTY}=true")
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


def _stop_managed_process(process: ManagedProcess, *, interrupted: bool = False) -> bool:
    training = isinstance(process, _aot_cache.ManagedProcess)
    if training and interrupted:
        # The child got the same Ctrl-C (SIGINT, or the console event on
        # Windows). Let a JVM training an AOT cache finish writing its
        # configuration as it exits before terminating it.
        try:
            process.wait(timeout=process.stop_timeout)
            return True
        except Exception:
            pass
    try:
        process.terminate()
        process.wait(timeout=process.stop_timeout if training else 3)
        return True
    except Exception:
        try:
            process.kill()
            process.wait(timeout=5)
            return True
        except Exception:
            return False


DEV_RELOAD_PROPERTY = "pyronaut.dev.reload"
DEV_RELOAD_RESTART = "restart"
DEV_RELOAD_PROCESS = "process"
# The status the development runtime exits with once its generation budget
# (micronaut.dev.max-generations) is spent: MicronautDevMain.RELAUNCH. The
# native image never unloads a generation's classes, so it has a budget by
# default, and the CLI starts the process again.
DEV_RELAUNCH_STATUS = 3
# Written by the runtime under __pyronaut__ before it exits with that status, so
# that an application exiting with status 3 itself is not started again.
_DEV_RELAUNCH_MARKER = ("micronaut-dev", "relaunch")
_NATIVE_DEV_RUNTIME_ARTIFACT = "io.micronaut:micronaut-dev"
_MICRONAUT_DEV_JAR = re.compile(r"^micronaut-dev-\d[^/\\]*\.jar$")


def _read_dev_reload(project_dir: Path) -> str:
    """How dev mode applies a change: tool.pyronaut.dev.reload, 'restart' by default."""
    data = _read_pyproject_data(project_dir)
    section = data.get("tool", {}) if isinstance(data, dict) else {}
    for key in ("pyronaut", "dev"):
        section = section.get(key, {}) if isinstance(section, dict) else {}
    value = section.get("reload", DEV_RELOAD_RESTART) if isinstance(section, dict) else DEV_RELOAD_RESTART
    return value if value in {DEV_RELOAD_RESTART, DEV_RELOAD_PROCESS} else DEV_RELOAD_RESTART


def _dev_reload_in_process(
    project_dir: Path,
    args: Sequence[str],
    *,
    debug_vm: bool = False,
    resolver: Callable[[str], str | None] | None = None,
) -> bool:
    """
    Whether dev mode runs the application in the reloading development runtime of
    micronaut-dev rather than restarting the process for every change.

    The project must be a Pyronaut project, not an external build. On the JVM
    toolchain its development runtime must hold micronaut-dev, which Micronaut
    5.3 and later provide. On the native toolchain the pyronaut-dev image must
    hold it: the image is the runtime's parent tier and defines each
    generation's classes at runtime; an image built without it restarts the
    process.
    """
    if _is_external_build_project(project_dir) or _read_dev_reload(project_dir) == DEV_RELOAD_PROCESS:
        return False
    if _restart_excludes_under_watched_roots(project_dir):
        # The development runtime watches its source and configuration roots
        # whole; an exclusion inside one is honored by restarting the process.
        return False
    try:
        native = _use_pyronaut_dev_native_toolchain("dev", project_dir, debug_vm=debug_vm, args=args)
    except ValueError:
        return False
    if native:
        executable = _resolve_pyronaut_dev_native_executable(resolver or _resolve_executable)
        return executable is not None and _NATIVE_DEV_RUNTIME_ARTIFACT in _native_launcher_provided_artifact_coordinates(executable)
    manifest = _pyronaut_output_dir(project_dir) / "resolved-development-runtime-dependencies"
    try:
        entries = _read_manifest_entries(manifest)
    except RuntimeError:
        return False
    return any(_MICRONAUT_DEV_JAR.match(Path(entry).name) for entry in entries)


_DEV_COMPILER_JAR = re.compile(
    r"^(micronaut-inject-python|micronaut-inject-java|micronaut-core-processor|micronaut-sourcegen-[a-z-]+"
    r"|javaparser-[a-z-]+|asm(-[a-z]+)?|checker-qual)-\d[^/\\]*\.jar$"
)


_PYRONAUT_DEV_JAR = re.compile(r"^micronaut-pyronaut-dev-\d[^/\\]*\.jar$")


def _pyronaut_dev_jar(resolver: Callable[[str], str | None]) -> str | None:
    """The jar of the pyronaut-dev JVM launcher, which holds the reloading launcher."""
    executable = resolver(COMMAND_TO_EXECUTABLE["dev"])
    if executable is None:
        return None
    for entry in _delegate_lib_entries(executable, include_control_panel=False):
        if _PYRONAUT_DEV_JAR.match(Path(entry).name):
            return entry
    return None


def _dev_compiler_entries(build_entries: Sequence[str]) -> list[str]:
    """The Pyronaut compiler and what it needs beyond the application runtime, from the build scope."""
    return [entry for entry in build_entries if _DEV_COMPILER_JAR.match(Path(entry).name)]


def _dev_compiler_classpath(build_entries: Sequence[str], resolver: Callable[[str], str | None] | None) -> list[str]:
    """
    The Pyronaut compiler the development runtime compiles with: the build
    scope's, at the versions of the pyronaut-processor launcher, which
    ``pyronaut process`` runs, so that a compilation in process writes the
    classes the processed output holds, byte for byte, and a first edit
    changes only what it edits. The launcher aligns versions the build scope
    does not, such as sourcegen's, whose bytecode writer orders a class's
    constants differently from one version to the next. A jar at the same
    version stays the build scope's: the same jar at two paths would hold the
    compiler's Python file system twice, which GraalPy refuses.
    """
    entries = _dev_compiler_entries(build_entries)
    executable = resolver(COMMAND_TO_EXECUTABLE["process"]) if resolver is not None else None
    if executable is None:
        return entries
    try:
        launcher_entries = [
            entry for entry in _delegate_lib_entries(executable, include_control_panel=False)
            if _DEV_COMPILER_JAR.match(Path(entry).name)
        ]
    except (OSError, RuntimeError):
        return entries
    launcher: dict[str, tuple[str, str]] = {}
    for entry in launcher_entries:
        match = _VERSIONED_JAR.match(Path(entry).name)
        if match:
            launcher[match.group(1)] = (match.group(2), entry)
    aligned: list[str] = []
    seen: set[str] = set()
    for entry in entries:
        match = _VERSIONED_JAR.match(Path(entry).name)
        if match is None:
            aligned.append(entry)
            continue
        seen.add(match.group(1))
        version, launcher_entry = launcher.get(match.group(1), (match.group(2), entry))
        aligned.append(entry if version == match.group(2) else launcher_entry)
    # what the launcher's versions need that the build scope's did not
    if any(name.startswith("micronaut-inject-python") for name in seen):
        aligned.extend(entry for artifact, (_, entry) in launcher.items() if artifact not in seen)
    return list(dict.fromkeys(aligned))


_VERSIONED_JAR = re.compile(r"^(.+?)-(\d[^/\\]*)\.jar$")


def _without_other_versions(entries: Sequence[str], compiler: Sequence[str]) -> list[str]:
    """
    A launch classpath without the jars of the compiler's artifacts at other
    versions, as the build scope's on a test classpath, which would come first.
    """
    chosen: dict[str, str] = {}
    for entry in compiler:
        match = _VERSIONED_JAR.match(Path(entry).name)
        if match:
            chosen[match.group(1)] = entry
    kept: list[str] = []
    for entry in entries:
        match = _VERSIONED_JAR.match(Path(entry).name)
        if match and match.group(1) in chosen and chosen[match.group(1)] != entry:
            continue
        kept.append(entry)
    return kept


def _snapshot_dependency_inputs(project_dir: Path) -> tuple[tuple[str, int, int], ...]:
    """
    What needs a new process when the sources reload in process: the files
    declaring the dependencies, and whether each configured source and resource
    directory exists, since the development runtime watches the directories it
    was started with and a directory created later must be added to them.
    """
    entries: list[tuple[str, int, int]] = []
    for name in ("pyproject.toml", "requirements.txt"):
        file_path = project_dir / name
        try:
            stat = file_path.stat()
        except OSError:
            continue
        entries.append((name, stat.st_mtime_ns, stat.st_size))
    layout = _read_pyproject_sources(project_dir)
    for root_name in (
        layout.python_source_dir,
        layout.java_source_dir,
        layout.resources_dir,
        *layout.additional_resources_dirs,
    ):
        root = _resolve_layout_dir(project_dir, root_name)
        entries.append((f"dir:{root.as_posix()}", int(root.is_dir()), 0))
    entries.extend(_processor_option_inputs(project_dir))
    return tuple(entries)


def _restart_excludes_under_watched_roots(project_dir: Path, *, tests: bool = False) -> bool:
    """Whether a restart exclusion lies inside a source or configuration root, or a test root with ``tests``."""
    excludes = _read_dev_restart_excludes(project_dir)
    if not excludes:
        return False
    layout = _read_pyproject_sources(project_dir)
    root_names = [layout.python_source_dir, layout.java_source_dir, layout.resources_dir]
    if tests:
        # test mode watches every resource root whole, the additional ones included
        root_names.extend((layout.python_test_dir, layout.java_test_dir, layout.test_resources_dir))
        root_names.extend((*layout.additional_resources_dirs, *layout.additional_test_resources_dirs))
    roots = [_resolve_layout_dir(project_dir, root_name).resolve() for root_name in root_names]
    for exclude in excludes:
        excluded = (project_dir / exclude).resolve()
        if any(excluded == root or root in excluded.parents for root in roots):
            return True
    return False


def _processor_option_inputs(project_dir: Path) -> list[tuple[str, int, int]]:
    """
    The annotation processor options set in the application configuration.

    pyronaut process reads them from the configuration directory, and the
    development runtime compiles with the options of its start, so a change to
    one needs a new process. Other configuration edits are refreshed in place.
    """
    supported_file = _pyronaut_output_dir(project_dir) / "annotation-processor-options.properties"
    supported: set[str] = set()
    try:
        for line in supported_file.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("options="):
                supported = {option.strip() for option in line.split("=", 1)[1].split(",") if option.strip()}
    except OSError:
        return []
    if not supported:
        return []
    layout = _read_pyproject_sources(project_dir)
    config_dir = _resolve_layout_dir(project_dir, layout.resources_dir)
    values: dict[str, str] = {}
    entries: list[tuple[str, int, int]] = []
    toml_file = config_dir / "application.toml"
    if toml_file.is_file():
        try:
            try:
                import tomllib
            except ImportError:
                import tomli as tomllib  # type: ignore[no-redef]
            with toml_file.open("rb") as fp:
                _flatten_config(tomllib.load(fp), "", values)
        except Exception:
            entries.append(_file_stamp(project_dir, toml_file))
    properties_file = config_dir / "application.properties"
    if properties_file.is_file():
        try:
            for line in properties_file.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped[0] in "#!":
                    continue
                separator = min((index for index in (stripped.find("="), stripped.find(":")) if index > 0), default=-1)
                if separator > 0:
                    values[stripped[:separator].strip()] = stripped[separator + 1:].strip()
        except OSError:
            entries.append(_file_stamp(project_dir, properties_file))
    for name in ("application.yml", "application.yaml"):
        # YAML is not parsed here: any edit needs a new process
        yaml_file = config_dir / name
        if yaml_file.is_file():
            entries.append(_file_stamp(project_dir, yaml_file))
    for key in sorted(supported):
        if key in values:
            entries.append((f"option:{key}={values[key]}", 0, 0))
    return entries


def _flatten_config(data: dict[str, object], prefix: str, values: dict[str, str]) -> None:
    for key, value in data.items():
        name = f"{prefix}{key}"
        if isinstance(value, dict):
            _flatten_config(value, name + ".", values)
        else:
            values[name] = str(value)


def _file_stamp(project_dir: Path, file_path: Path) -> tuple[str, int, int]:
    stat = file_path.stat()
    try:
        name = file_path.relative_to(project_dir).as_posix()
    except ValueError:
        name = file_path.as_posix()
    return (name, stat.st_mtime_ns, stat.st_size)


def _read_dev_restart_excludes(project_dir: Path) -> tuple[str, ...]:
    """
    Paths dev mode watches but must not restart for.

    A directory a build tool writes into, whose contents the running application reloads by itself,
    should not cost a restart: micronaut-views-react watches its JavaScript bundle and swaps it in
    process, and restarting first makes that unreachable while discarding every connection pool and
    container the run had warmed up.
    """
    data = _read_pyproject_data(project_dir)
    if not isinstance(data, dict):
        return ()
    section = data.get("tool", {})
    for key in ("pyronaut", "dev"):
        if not isinstance(section, dict):
            return ()
        section = section.get(key, {})
    if not isinstance(section, dict):
        return ()
    configured = section.get("restart-excludes", section.get("restartExcludes", ()))
    if isinstance(configured, str):
        configured = (configured,)
    if not isinstance(configured, (list, tuple)):
        return ()
    excludes: list[str] = []
    for entry in configured:
        if not isinstance(entry, str):
            continue
        normalized = entry.strip().strip("/")
        if normalized:
            excludes.append(normalized)
    return tuple(excludes)


def _is_restart_excluded(relative_path: str, excludes: Sequence[str]) -> bool:
    for exclude in excludes:
        if relative_path == exclude or relative_path.startswith(exclude + "/"):
            return True
        if fnmatch.fnmatch(relative_path, exclude):
            return True
    return False


def _snapshot_watched_files(project_dir: Path) -> tuple[tuple[str, int, int], ...]:
    layout = _read_pyproject_sources(project_dir)
    restart_excludes = _read_dev_restart_excludes(project_dir)
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
                if _is_restart_excluded(relative, restart_excludes):
                    continue
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
        if token in {
            "test", "--port", "--property", "--config", "--setup", "--report",
            "--progress", "--color", "--local-repository", "--local-repo",
        }:
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
        if token in {"--offline", "--refresh", "--no-cache", "--native", "--jvm"}:
            index += 1
            continue
        if token.startswith("-D") or token.startswith("--port=") or token.startswith("--property=") or token.startswith("--config=") or token.startswith("--setup=") or token.startswith("--report=") or token.startswith("--progress=") or token.startswith("--color=") or token.startswith("--local-repository=") or token.startswith("--local-repo="):
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
    aot = _prepare_aot_launch(command_line, env)
    command_line, env = aot.command_line, aot.env
    launch = _launch_indicator(command_line)
    try:
        options = {"shell": True} if _uses_windows_batch_shell(command_line) else {}
        process = subprocess.Popen(command_line, env=launch.environment(env), pass_fds=launch.pass_fds, **options)
    except OSError:
        launch.abandon()
        raise
    launch.spawned()
    return _aot_cache.ManagedProcess(process, aot) if aot.training is not None else process


def _aot_cache_project(args: Sequence[str]) -> Path | None:
    """The project directory when its pyproject.toml enables JVM AOT caches."""
    try:
        project_dir = (
            Path.cwd() if _looks_like_direct_source_invocation(args) else Path(_extract_project_dir(args))
        ).resolve()
        pyronaut = _read_pyproject_pyronaut_table(project_dir)
    except Exception:
        return None
    toolchain = pyronaut.get("toolchain") if isinstance(pyronaut, dict) else None
    if not isinstance(toolchain, dict):
        return None
    enabled = toolchain.get("aot-cache", toolchain.get("aotCache", False))
    return project_dir if enabled is True else None


def _prepare_aot_launch(command_line: list[str], env: dict[str, str] | None) -> _aot_cache.Launch:
    if _aot_cache_project_dir is not None:
        try:
            return _aot_cache.prepare(command_line, env, project_dir=_aot_cache_project_dir)
        except Exception:
            # The cache is an optimization; never fail a launch over it.
            pass
    return _aot_cache.Launch(list(command_line), env)


# Launch indicator labels by tool. Tools missing here (pip, docker, a nested
# CLI) never take part in the handshake and get no indicator.
_LAUNCH_LABELS = {
    "pyronaut-install": "Starting dependency installer",
    "pyronaut-processor": "Starting processor",
    "pyronaut-validate-config": "Starting configuration validator",
    "pyronaut-test": "Starting test runner",
    "pyronaut-run": "Starting application launcher",
    "pyronaut-run-python": "Starting application launcher",
    "pyronaut-native-build": "Starting native image builder",
    "pyronaut-jar-build": "Starting JAR packager",
    "pyronaut-test-resources-server": "Starting test resources server",
}
_LAUNCH_LABELS_BY_COMMAND = {
    "install": _LAUNCH_LABELS["pyronaut-install"],
    "process": _LAUNCH_LABELS["pyronaut-processor"],
    "validate-config": _LAUNCH_LABELS["pyronaut-validate-config"],
    "test": _LAUNCH_LABELS["pyronaut-test"],
    "run": _LAUNCH_LABELS["pyronaut-run"],
    "dev": _LAUNCH_LABELS["pyronaut-run"],
    "test-resources-server": _LAUNCH_LABELS["pyronaut-test-resources-server"],
}


def _launch_indicator(command_line: Sequence[str]) -> _LaunchIndicator:
    return _LaunchIndicator(_progress_console(), _launch_label(command_line))


def _launch_label(command_line: Sequence[str]) -> str | None:
    """Describe what a delegated command line starts, or ``None`` for unknown tools."""
    if not command_line:
        return None
    executable = Path(command_line[0]).name
    for suffix in (".bat", ".exe"):
        if executable.lower().endswith(suffix):
            executable = executable[: -len(suffix)]
    arguments = list(command_line[1:])
    if executable in {"java", "javaw"}:
        # A JVM delegate: the tool is the main class after the JVM options.
        main_index = next(
            (
                index for index, value in enumerate(arguments)
                if value in JAVA_MAIN_BY_COMMAND.values() or value == TEST_RELOAD_MAIN
            ),
            None,
        )
        if main_index is None:
            return None
        command = "test" if arguments[main_index] == TEST_RELOAD_MAIN else next(
            name for name, main in JAVA_MAIN_BY_COMMAND.items() if main == arguments[main_index]
        )
        executable = COMMAND_TO_EXECUTABLE[command]
        arguments = arguments[main_index + 1:]
    if executable == DEV_NATIVE_EXECUTABLE:
        # The multi-tool launcher: the first non-option argument names the tool.
        command = next((value for value in arguments if not value.startswith("-")), None)
        # A bare source selector means a direct-source run of the application.
        return _LAUNCH_LABELS_BY_COMMAND.get(command, _LAUNCH_LABELS["pyronaut-run"])
    return _LAUNCH_LABELS.get(executable)


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
    if command not in {
        "dev",
        "run",
        "test",
        "build",
        "tui",
        "validate-config",
        "install",
        "process",
        "test-resources-server",
    }:
        return None
    env = _apply_configured_graalpy(_terminal_environment(dict(os.environ)))
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


def _build_delegate_jvm_args(debug_vm: bool, *, short_lived: bool = False) -> list[str]:
    jvm_args = list(_DELEGATE_JVM_FLAGS)
    if short_lived:
        jvm_args.extend(_SHORT_LIVED_JVM_FLAGS)
    if debug_vm:
        jvm_args.append(_JDWP_FLAGS)
    return jvm_args


def _with_launcher_jvm_options(
    env: dict[str, str] | None,
    executable_path: str,
    options: Sequence[str],
) -> dict[str, str]:
    """Pass JVM options to a bundled launcher script.

    The launcher scripts read JVM options from ``<TOOL>_OPTS`` (for example
    ``PYRONAUT_DEV_OPTS``); leading ``-D`` arguments on their command line are
    application arguments. Options the user already set there come last, so
    they win.
    """
    name = Path(executable_path).name
    for suffix in (".bat", ".cmd"):
        if name.lower().endswith(suffix):
            name = name[: -len(suffix)]
    variable = name.upper().replace("-", "_") + "_OPTS"
    updated = dict(os.environ if env is None else env)
    updated[variable] = " ".join([*options, updated.get(variable, "")]).strip()
    return updated


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


def _ensure_graalvm_java_home(
    project_dir: Path | None = None,
    *,
    offline: bool = False,
) -> str | None:
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

    if offline:
        return None
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
    # installation directory (for example, graalvm-community-25.4.4.1.1-dev).
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


def _native_image_distribution_version(version: str) -> str:
    return version[:-5] + "-SNAPSHOT" if version.endswith(".dev0") else version


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


def _native_image_configuration() -> tuple[str, str, str | None]:
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
    version = _native_image_distribution_version(version.strip())
    if any(character in version for character in "/\\"):
        raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].version must not contain path separators")

    release_tag = configured.get("release-tag")
    if release_tag is not None:
        if not isinstance(release_tag, str) or not release_tag.strip():
            raise RuntimeError(f"[{_NATIVE_IMAGE_SETTINGS_TABLE}].release-tag must be a non-empty string")
        release_tag = release_tag.strip()
    return (
        (base_url.rstrip("/") + "/") if parsed_base_url.scheme in {"http", "https"} else base_url,
        version,
        release_tag,
    )


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


def _github_release_asset(
    base_url: str,
    version: str,
    archive_name: str,
    *,
    allow_draft: bool = False,
    release_tag: str | None = None,
) -> tuple[str, dict[str, str]] | None:
    if not _is_github_release_url(base_url):
        return None

    tag = (
        release_tag.strip()
        if release_tag is not None
        else (version if version.startswith("v") else f"v{version}")
    )
    token = _github_token()
    headers = _github_api_headers()

    def get_json(url: str) -> object | None:
        return _github_api_json(url, headers)

    api_root = _github_api_root(base_url)
    release = get_json(f"{api_root}/releases/tags/{urllib.parse.quote(tag)}")
    if isinstance(release, dict) and release.get("draft") and not allow_draft:
        release = None
    if release is None:
        releases = get_json(f"{api_root}/releases?per_page=100")
        if isinstance(releases, list):
            release = next(
                (
                    candidate
                    for candidate in releases
                    if isinstance(candidate, dict)
                    and candidate.get("tag_name") == tag
                    and (allow_draft or not candidate.get("draft", False))
                ),
                None,
            )
    if isinstance(release, dict) and release.get("draft") and not allow_draft:
        release = None
    if not isinstance(release, dict):
        token_hint = (
            "; check GH_TOKEN, GITHUB_TOKEN, GITHUB_API_TOKEN, or PYRONAUT_RELEASE_TOKEN read access"
            if not token
            else ""
        )
        raise RuntimeError(f"GitHub release '{tag}' was not found at {base_url}{token_hint}")

    assets = release.get("assets", [])
    asset = next(
        (candidate for candidate in assets if isinstance(candidate, dict) and candidate.get("name") == archive_name),
        None,
    )
    if not isinstance(asset, dict) or not isinstance(asset.get("url"), str):
        raise RuntimeError(f"GitHub release '{tag}' has no asset named '{archive_name}'")
    download_headers = dict(headers)
    download_headers["Accept"] = "application/octet-stream"
    return asset["url"], download_headers


def _github_token() -> str | None:
    return next(
        (os.environ.get(name) for name in ("GH_TOKEN", "GITHUB_TOKEN", "GITHUB_API_TOKEN", "PYRONAUT_RELEASE_TOKEN") if os.environ.get(name)),
        None,
    )


def _github_api_headers() -> dict[str, str]:
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = _github_token()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _github_api_root(base_url: str) -> str:
    """Return the REST API root of the repository behind a ``.../releases`` URL."""
    owner, repository = [part for part in urllib.parse.urlparse(base_url).path.split("/") if part][:2]
    return f"https://api.github.com/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(repository)}"


def _github_api_json(url: str, headers: dict[str, str]) -> object | None:
    """Read a GitHub REST API document through the configured proxy; ``None`` on 404."""
    proxy, bypass = _download_proxy(url)
    parsed_url = urllib.parse.urlparse(url)
    host = parsed_url.hostname or ""
    host_with_port = parsed_url.netloc.rsplit("@", 1)[-1]
    bypassed = _proxy_bypasses_host(host, bypass) or _proxy_bypasses_host(host_with_port, bypass)
    proxies = {} if proxy is None or bypassed else {parsed_url.scheme: proxy}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))
    try:
        with opener.open(urllib.request.Request(url, headers=headers)) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise RuntimeError(f"Unable to read GitHub release metadata from {url}: HTTP {exc.code}") from exc
    except (OSError, ValueError, TypeError) as exc:
        raise RuntimeError(f"Unable to read GitHub release metadata from {url}: {exc}") from exc


def _is_github_release_url(base_url: str) -> bool:
    parsed = urllib.parse.urlparse(base_url)
    parts = [part for part in parsed.path.split("/") if part]
    return parsed.hostname in {"github.com", "www.github.com"} and len(parts) == 3 and parts[2] == "releases"


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


def _download_url_with_progress(
    url: str,
    destination: Path,
    label: str,
    headers: dict[str, str] | None = None,
) -> None:
    proxy, bypass = _download_proxy(url)
    parsed_url = urllib.parse.urlparse(url)
    host = parsed_url.hostname or ""
    host_with_port = parsed_url.netloc.rsplit("@", 1)[-1]
    bypassed = _proxy_bypasses_host(host, bypass) or _proxy_bypasses_host(host_with_port, bypass)
    proxies = {} if proxy is None or bypassed else {parsed_url.scheme: proxy}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))

    request = urllib.request.Request(url, headers=headers or {}) if headers else url
    progress = _progress_console()
    done_label = "Downloaded " + label[len("Downloading "):] if label.startswith("Downloading ") else label
    attempts = 3
    for attempt in range(1, attempts + 1):
        try:
            with opener.open(request) as response, destination.open("wb") as output:
                total = int(response.headers.get("Content-Length", "0") or "0")
                with progress.transfer(label, total, done=done_label) as transfer:
                    downloaded = 0
                    while chunk := response.read(256 * 1024):
                        output.write(chunk)
                        downloaded += len(chunk)
                        transfer.update(downloaded)
            return
        except OSError:
            if attempt == attempts:
                raise
            progress.warn(f"{label} download interrupted; retrying ({attempt}/{attempts - 1})...")
            time.sleep(attempt)


def _download_native_image_archive(
    url: str,
    destination: Path,
    image_name: str,
    headers: dict[str, str] | None = None,
) -> None:
    _download_url_with_progress(url, destination, f"Downloading {image_name} launcher", headers=headers)


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
    base_url, version, release_tag = _native_image_configuration()
    os_segment, arch = _native_image_platform(platform_name=platform_name, machine_name=machine_name)
    archive_name = f"{image_name}-{os_segment}-{arch}-{version}.tar.gz"
    local_archive = _native_image_local_archive(base_url, image_name, archive_name)
    github_release = _is_github_release_url(base_url)
    download_headers: dict[str, str] | None = None
    url = local_archive.resolve().as_uri() if local_archive is not None else None
    if url is None and not github_release:
        url = urllib.parse.urljoin(base_url, archive_name)
    executable = _native_image_cache_path(
        image_name,
        version=version,
        os_segment=os_segment,
        arch=arch,
    )
    metadata = executable.with_name(executable.name + ".json")
    lock = executable.with_name(executable.name + ".lock")
    expected_metadata: dict[str, object] = {
        "source": base_url,
        "version": version,
        "release-tag": release_tag,
        "platform": f"{os_segment}-{arch}",
        "bundle-format": _NATIVE_IMAGE_BUNDLE_FORMAT,
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
            cache_hit = (
                isinstance(cached_metadata, dict)
                and all(cached_metadata.get(key) == value for key, value in expected_metadata.items())
                and (github_release or cached_metadata.get("url") == url)
            )
            if cache_hit:
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
                if github_release:
                    github_asset = _github_release_asset(
                        base_url,
                        version,
                        archive_name,
                        allow_draft=_allow_draft_release,
                        release_tag=release_tag,
                    )
                    if github_asset is None:
                        raise RuntimeError(f"Unsupported GitHub release URL: {base_url}")
                    url, download_headers = github_asset
                assert url is not None
                expected_metadata["url"] = url
                if local_archive is not None:
                    shutil.copy2(local_archive, archive)
                else:
                    _download_native_image_archive(url, archive, image_name, headers=download_headers)
                with _progress_console().step(
                    f"Unpacking {image_name} launcher", done=f"Unpacked {image_name} launcher"
                ):
                    _extract_native_image_archive(archive, extracted, image_name)
            except Exception as exc:
                raise RuntimeError(f"Failed downloading {image_name} from {url}: {exc}") from exc
            bundle_files: list[str] = []
            for item in extracted.parent.iterdir():
                # Several native bundles contain identically named manifests
                # (native-compile-classpath.txt, native-provided-classpath.txt).
                # Keep those image-specific files separate; otherwise
                # provisioning pyronaut-run after pyronaut-dev silently replaces
                # the compiler manifest used by pyronaut-dev. Copied language
                # resources are deliberately not isolated: native-image resolves
                # them from "resources" beside the executable, and every bundle
                # of a given version ships the same GraalPy home.
                if item.name in {"native-compile-classpath.txt", "native-provided-classpath.txt"}:
                    target = executable.parent / "resources" / image_name / item.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(item, target)
                    bundle_files.append(str(Path("resources") / image_name / item.name))
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
                    bundle_files.extend(
                        str(path.relative_to(extracted.parent))
                        for path in item.rglob("*")
                        if path.is_file()
                    )
                else:
                    bundle_files.append(item.name)
                if item.is_dir():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copytree(item, target, dirs_exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(item, target)
            expected_metadata["bundle-files"] = sorted(set(bundle_files))
            metadata_tmp = temp_root / f"{image_name}.json"
            metadata_tmp.write_text(json.dumps(expected_metadata, sort_keys=True) + "\n", encoding="utf-8")
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
    _download_url_with_progress(url, destination, "Downloading GraalVM JDK")


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
            with _progress_console().step("Unpacking GraalVM JDK", done="Unpacked GraalVM JDK"):
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
            with _progress_console().step(
                f"Installing GraalVM JDK into {destination}", done=f"Installed GraalVM JDK into {destination}"
            ):
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
    for token in _orchestrator_args(args):
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
    option_args, application_args = _split_application_args(args)
    normalized: list[str] = []
    for token in option_args:
        if token == "--debug-vm" or token.startswith("--debug-vm="):
            continue
        normalized.append(token)
    return normalized + application_args


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
    for token in _orchestrator_args(args):
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
    option_args, application_args = _split_application_args(args)
    filtered: list[str] = []
    for token in option_args:
        if token == "--no-cache" or token.startswith("--no-cache="):
            continue
        filtered.append(token)
    return filtered + application_args


def _extract_local_repository(args: Sequence[str]) -> str | None:
    args = _orchestrator_args(args)
    for index, token in enumerate(args):
        if token in {"--local-repository", "--local-repo"}:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {token}")
            return args[index + 1]
        for prefix in ("--local-repository=", "--local-repo="):
            if token.startswith(prefix):
                return token.split("=", 1)[1]
    return None


def _extract_option_value(args: Sequence[str], option: str) -> str | None:
    for index, token in enumerate(args):
        if token == option:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {option}")
            return args[index + 1]
        if token.startswith(option + "="):
            return token.split("=", 1)[1]
    return None


def _strip_local_repository_args(args: Sequence[str]) -> list[str]:
    option_args, application_args = _split_application_args(args)
    filtered: list[str] = []
    skip_next = False
    for token in option_args:
        if skip_next:
            skip_next = False
            continue
        if token in {"--local-repository", "--local-repo"}:
            skip_next = True
            continue
        if token.startswith("--local-repository=") or token.startswith("--local-repo="):
            continue
        filtered.append(token)
    return filtered + application_args


def _strip_orchestrator_only_args(args: Sequence[str]) -> list[str]:
    """Remove CLI options consumed before invoking a native runner."""
    option_args, application_args = _split_application_args(args)
    filtered: list[str] = []
    skip_next = False
    value_options = {"--local-repository", "--local-repo", "--color", "--progress"}
    flag_options = {"--offline", "--no-validate", "--no-cache"}
    for token in option_args:
        if skip_next:
            skip_next = False
            continue
        if token in value_options:
            skip_next = True
            continue
        if token in flag_options or any(token.startswith(option + "=") for option in value_options | flag_options):
            continue
        filtered.append(token)
    return filtered + application_args


def _strip_disable_test_resources_flag(args: Sequence[str]) -> list[str]:
    option_args, application_args = _split_application_args(args)
    return [
        token
        for token in option_args
        if token != "--disable-test-resources" and not token.startswith("--disable-test-resources=")
    ] + application_args


def _split_application_args(args: Sequence[str]) -> tuple[list[str], list[str]]:
    """Split at the first ``--``; orchestrator options only precede it."""
    values = list(args)
    if "--" in values:
        index = values.index("--")
        return values[:index], values[index:]
    return values, []


def _orchestrator_args(args: Sequence[str]) -> list[str]:
    return _split_application_args(args)[0]


def _usage_error(message: str) -> int:
    print(message, file=sys.stderr)
    _print_usage(stream=sys.stderr)
    return USAGE_ERROR


def _extract_no_validate(args: Sequence[str]) -> bool:
    return any(token == "--no-validate" for token in _orchestrator_args(args))


def _remove_no_validate(args: Sequence[str]) -> list[str]:
    option_args, application_args = _split_application_args(args)
    return [token for token in option_args if token != "--no-validate"] + application_args


def _extract_continuous(args: Sequence[str]) -> bool:
    return any(token in {"-t", "--continuous"} for token in _orchestrator_args(args))


def _remove_continuous(args: Sequence[str]) -> list[str]:
    option_args, application_args = _split_application_args(args)
    return [token for token in option_args if token not in {"-t", "--continuous"}] + application_args


def _extract_project_dir(args: Sequence[str]) -> str:
    for index, token in enumerate(args):
        if token == "--project-dir" and index + 1 < len(args):
            return args[index + 1]
        if token.startswith("--project-dir="):
            return token.split("=", 1)[1]
    return "."


def _required_install_manifests(project_dir: Path) -> tuple[Path, ...]:
    """Files written by ``pyronaut install`` that every later command needs."""
    cache_dir = _pyronaut_output_dir(project_dir)
    if _is_external_build_project(project_dir):
        return (cache_dir / "project-layout.properties",)
    return (
        cache_dir / "resolved-build-dependencies",
        cache_dir / "resolved-runtime-dependencies",
        cache_dir / "resolved-test-dependencies",
    )


def _install_required(project_dir: Path) -> bool:
    return not all(path.exists() for path in _required_install_manifests(project_dir))


def _process_required(project_dir: Path, command: str) -> bool:
    output_dir = _pyronaut_output_dir(project_dir)
    classes_ready = (output_dir / "classes").is_dir()
    if command == "test":
        test_classes_ready = (output_dir / "test-classes").is_dir()
        return not (classes_ready and test_classes_ready)
    return not classes_ready


def _run_create(args: Sequence[str], runner: RunnerWithEnv, platform_name: str) -> int:
    try:
        create_args, show_help, list_features = _parse_create_args(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        _print_create_usage(stream=sys.stderr)
        return USAGE_ERROR

    if show_help:
        _print_create_usage()
        return SUCCESS

    version = _micronaut_platform_version()
    if version is None:
        print(
            "Unable to determine the Micronaut Platform version from the installed Pyronaut distribution.",
            file=sys.stderr,
        )
        return PRECONDITION_FAILED
    parsed_version = _parse_micronaut_version(version)
    if parsed_version is None or parsed_version < _MICRONAUT_CREATE_MIN_VERSION:
        minimum = ".".join(str(value) for value in _MICRONAUT_CREATE_MIN_VERSION)
        print(
            f"pyronaut create requires Micronaut Platform {minimum} or newer (configured: {version}).",
            file=sys.stderr,
        )
        return PRECONDITION_FAILED

    try:
        mn = _ensure_micronaut_launch(version, platform_name=platform_name)
    except _CreatePreconditionError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    except Exception as exc:
        print(f"Project creation tooling failed: {exc}", file=sys.stderr)
        return INTERNAL_ERROR

    command_line = [str(mn), "create-app", *create_args, *_MICRONAUT_CREATE_FIXED_ARGS]
    if list_features:
        exit_code, stdout, stderr = _capture_subprocess(command_line)
        if stderr:
            sys.stderr.write(stderr)
        if exit_code != SUCCESS:
            return exit_code
        sys.stdout.write(_filter_create_features(stdout, version))
        return SUCCESS
    return runner(command_line, None)


class _CreatePreconditionError(RuntimeError):
    """A create prerequisite is unavailable or does not match the request."""


def _parse_create_args(args: Sequence[str]) -> tuple[list[str], bool, bool]:
    forwarded: list[str] = []
    name_seen = False
    show_help = False
    list_features = False
    index = 0
    while index < len(args):
        token = args[index]
        if token in {"-h", "--help"}:
            show_help = True
            index += 1
            continue
        if token == "--list-features":
            list_features = True
            forwarded.append(token)
            index += 1
            continue
        if token in {"-i", "--inplace", "-v", "--verbose", "-x", "--stacktrace"}:
            forwarded.append(token)
            index += 1
            continue
        if len(token) > 2 and token.startswith("-") and not token.startswith("--"):
            short_flags = token[1:]
            if all(flag in "hivx" for flag in short_flags):
                show_help = show_help or "h" in short_flags
                forwarded.extend(f"-{flag}" for flag in short_flags if flag != "h")
                index += 1
                continue
        if token in {"-f", "--features"}:
            if (
                index + 1 >= len(args)
                or not args[index + 1].strip()
                or args[index + 1].startswith("-")
            ):
                raise ValueError(f"{token} requires a feature value")
            forwarded.extend((token, args[index + 1]))
            index += 2
            continue
        if token.startswith("-f=") or token.startswith("--features="):
            if not token.split("=", 1)[1].strip():
                raise ValueError(f"{token.split('=', 1)[0]} requires a feature value")
            forwarded.append(token)
            index += 1
            continue
        if token.startswith("-"):
            raise ValueError(f"Unknown pyronaut create option: {token}")
        if name_seen:
            raise ValueError("Only one application NAME may be supplied")
        name_seen = True
        forwarded.append(token)
        index += 1

    denied = _denied_create_features(_micronaut_platform_version() or "")
    for index, token in enumerate(forwarded):
        if token not in {"-f", "--features"} and not token.startswith("-f=") and not token.startswith("--features="):
            continue
        value = token.split("=", 1)[1] if "=" in token else forwarded[index + 1]
        for feature in (part.strip().lower() for part in value.split(",")):
            if feature and feature in denied:
                raise ValueError(
                    f"Feature {feature} is not supported for Python applications; remove it from --features"
                )
    return forwarded, show_help, list_features


def _micronaut_platform_version() -> str | None:
    version_file = Path(__file__).with_name("version.properties")
    if not version_file.is_file():
        return None
    try:
        for line in version_file.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "micronaut.platform" and value.strip():
                return value.strip()
    except OSError:
        return None
    return None


def _parse_micronaut_version(version: str) -> tuple[int, int, int] | None:
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$", version.strip())
    if match is None:
        return None
    return tuple(int(value) for value in match.groups())


def _denied_create_features(version: str) -> frozenset[str]:
    parsed = _parse_micronaut_version(version)
    if parsed is None:
        return frozenset()
    minor = (parsed[0], parsed[1])
    known = [key for key in _PYRONAUT_CREATE_DENYLIST_BY_MINOR if key <= minor]
    return _PYRONAUT_CREATE_DENYLIST_BY_MINOR[max(known)] if known else frozenset()


def _create_sdk_platform(platform_name: str, machine_name: str | None = None) -> tuple[str, str, str]:
    current = platform_name.lower()
    machine = (machine_name or platform.machine()).lower()
    if current == "darwin":
        if machine in {"x86_64", "amd64"}:
            return "darwin", "amd64", "mn"
        if machine in {"arm64", "aarch64"}:
            return "darwin", "aarch64", "mn"
    elif current.startswith("linux") and machine in {"x86_64", "amd64"}:
        return "linux", "amd64", "mn"
    elif current in {"win32", "windows", "cygwin", "msys"} and machine in {"x86_64", "amd64"}:
        return "win", "amd64", "mn.exe"
    raise _CreatePreconditionError(
        f"Micronaut Launch SDK is not available for platform '{platform_name}' and architecture '{machine}'. "
        "Supported combinations are macOS x64/arm64, Linux x64, and Windows x64."
    )


def _ensure_micronaut_launch(version: str, *, platform_name: str) -> Path:
    os_segment, arch, executable_name = _create_sdk_platform(platform_name)
    safe_version = re.sub(r"[^A-Za-z0-9._-]", "_", version)
    if safe_version != version or not version:
        raise _CreatePreconditionError(f"Invalid Micronaut Platform version: {version}")

    sdkman_root = Path(_read_env("SDKMAN_DIR") or (Path.home() / ".sdkman")) / "candidates" / "micronaut"
    candidates = [sdkman_root / version / "bin" / executable_name]
    current = sdkman_root / "current"
    try:
        if current.is_symlink() and current.resolve().name == version:
            candidates.append(current / "bin" / executable_name)
    except OSError:
        pass
    cache_root = Path.home() / ".pyronaut" / "sdks" / "micronaut"
    candidates.append(cache_root / version / "bin" / executable_name)
    for candidate in candidates:
        if _valid_micronaut_launch(candidate, version):
            return candidate

    if "-" in version or "+" in version:
        raise _CreatePreconditionError(
            f"Micronaut Launch SDK {version} is not a published release. Stage an exact 'mn' installation in "
            f"{sdkman_root / version / 'bin'} or {cache_root / version / 'bin'}."
        )

    cache_root.mkdir(parents=True, exist_ok=True)
    destination = cache_root / version
    lock = cache_root / f".{safe_version}.lock"
    with _native_image_cache_lock(lock):
        if _valid_micronaut_launch(destination / "bin" / executable_name, version):
            return destination / "bin" / executable_name
        print("Project Creation Tooling is being installed", file=sys.stderr, flush=True)
        archive_name = f"mn-{os_segment}-{arch}-v{version}.zip"
        url = f"{_MICRONAUT_STARTER_RELEASES_URL}/v{version}/{archive_name}"
        with tempfile.TemporaryDirectory(prefix=f".{safe_version}-", dir=cache_root) as temp_dir:
            temp_root = Path(temp_dir)
            archive = temp_root / archive_name
            _download_url_with_progress(url, archive, "Downloading Micronaut Launch SDK")
            staged = temp_root / "sdk"
            _extract_micronaut_launch_archive(archive, staged, executable_name)
            executable = staged / "bin" / executable_name
            if not _valid_micronaut_launch(executable, version):
                raise RuntimeError(f"Downloaded Micronaut Launch SDK does not report version {version}")
            if destination.exists():
                old_destination = temp_root / "old-sdk"
                os.replace(destination, old_destination)
                try:
                    os.replace(staged, destination)
                except OSError:
                    if not destination.exists() and old_destination.exists():
                        os.replace(old_destination, destination)
                    raise
                shutil.rmtree(old_destination, ignore_errors=True)
            else:
                os.replace(staged, destination)
    return destination / "bin" / executable_name


def _capture_subprocess(command_line: list[str]) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(command_line, check=False, capture_output=True, text=True)
        return int(completed.returncode), completed.stdout or "", completed.stderr or ""
    except OSError as exc:
        return INTERNAL_ERROR, "", str(exc)


def _valid_micronaut_launch(executable: Path, version: str) -> bool:
    if not executable.is_file() or not os.access(executable, os.X_OK):
        return False
    exit_code, stdout, _ = _capture_subprocess([str(executable), "--version"])
    if exit_code != SUCCESS:
        return False
    match = re.search(r"Micronaut Version:\s*([^\s]+)", stdout)
    return match is not None and match.group(1).strip() == version


def _extract_micronaut_launch_archive(archive: Path, destination: Path, executable_name: str) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(archive) as source:
            members = source.infolist()
            roots: set[str] = set()
            expected = []
            for member in members:
                member_path = Path(member.filename)
                if member_path.is_absolute() or ".." in member_path.parts:
                    raise RuntimeError(f"Micronaut Launch archive contains an unsafe path: {member.filename}")
                mode = (member.external_attr >> 16) & 0o170000
                if mode == 0o120000:
                    raise RuntimeError(f"Micronaut Launch archive contains a symlink: {member.filename}")
                if not member.is_dir():
                    if len(member_path.parts) < 2:
                        raise RuntimeError("Micronaut Launch archive contains a file outside its root directory")
                    roots.add(member_path.parts[0])
                if member.filename.rstrip("/").endswith(f"/bin/{executable_name}"):
                    expected.append(member)
            if len(roots) != 1 or len(expected) != 1:
                raise RuntimeError(f"Micronaut Launch archive must contain exactly one bin/{executable_name}")
            archive_root = next(iter(roots))
            for member in members:
                if member.is_dir():
                    continue
                relative = Path(member.filename)
                parts = relative.parts
                if parts[0] != archive_root:
                    raise RuntimeError("Micronaut Launch archive contains multiple roots")
                target = destination.joinpath(*parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(member) as input_file, target.open("wb") as output:
                    shutil.copyfileobj(input_file, output)
            executable = destination / "bin" / executable_name
            executable.chmod(0o755)
    except (OSError, zipfile.BadZipFile) as exc:
        raise RuntimeError(f"Unable to unpack Micronaut Launch archive: {exc}") from exc


def _filter_create_features(output: str, version: str) -> str:
    denied = _denied_create_features(version)
    if not denied:
        return output
    ansi = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
    filtered: list[str] = []
    for line in output.splitlines(keepends=True):
        clean = ansi.sub("", line)
        if any(re.match(rf"^\s*{re.escape(feature)}(?:\s|$|\(|\[)", clean) for feature in denied):
            continue
        filtered.append(line)
    return "".join(filtered)


def _run_subprocess(command_line: list[str], env: dict[str, str] | None = None) -> int:
    aot = _prepare_aot_launch(command_line, env)
    command_line, env = aot.command_line, aot.env
    launch = _launch_indicator(command_line)
    try:
        options = {"shell": True} if _uses_windows_batch_shell(command_line) else {}
        process = subprocess.Popen(command_line, env=launch.environment(env), pass_fds=launch.pass_fds, **options)
    except OSError as exception:
        launch.abandon()
        print(f"Failed executing delegated command: {exception}", file=sys.stderr)
        return INTERNAL_ERROR
    launch.spawned()
    with process:
        try:
            return int(process.wait())
        except KeyboardInterrupt:
            if aot.training is not None:
                # The child got the same SIGINT; let a training JVM finish
                # writing its AOT configuration before giving up on it.
                try:
                    process.wait(timeout=_aot_cache.TRAINING_STOP_TIMEOUT_SECONDS)
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    aot.abandon()
            else:
                aot.abandon()
            # wait() already gave the child a moment to act on its own SIGINT.
            process.kill()
            return 130
        finally:
            aot.finish()


def _run_subprocess_quiet(command_line: list[str], env: dict[str, str] | None = None) -> int:
    aot = _prepare_aot_launch(command_line, env)
    try:
        options = {"shell": True} if _uses_windows_batch_shell(aot.command_line) else {}
        completed = subprocess.run(
            aot.command_line,
            check=False,
            env=aot.env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            **options,
        )
        aot.finish()
        return int(completed.returncode)
    except KeyboardInterrupt:
        return 130
    except OSError as exception:
        print(f"Failed executing delegated command: {exception}", file=sys.stderr)
        return INTERNAL_ERROR


def _uses_windows_batch_shell(command_line: Sequence[str]) -> bool:
    return sys.platform == "win32" and bool(command_line) and command_line[0].lower().endswith((".bat", ".cmd"))


def _resolve_executable(command_name: str) -> str | None:
    env_key = command_name.upper().replace("-", "_") + "_EXECUTABLE"
    override = _read_env(env_key)
    if override:
        return override

    if command_name == COMMAND_TO_EXECUTABLE["install"]:
        bundled = _bundled_executable(command_name)
        if bundled is not None and bundled.exists():
            return str(bundled)

    resolved_tool = _resolved_tool_executable(command_name)
    if resolved_tool is not None:
        return str(resolved_tool)

    # Source checkouts and legacy/installDist layouts still contain complete
    # launchers. A wheel launcher carrying a descriptor is intentionally
    # incomplete until the installer has materialized its resolved cache.
    bundled = _bundled_executable(command_name)
    if bundled is not None and bundled.exists():
        descriptor = bundled.parent / "pyronaut-tool-classpath.tsv"
        if not descriptor.is_file():
            return str(bundled)

    discovered = shutil.which(command_name)
    if discovered:
        return discovered
    return None


def _resolved_tool_executable(command_name: str) -> Path | None:
    packaged_tools = Path(__file__).resolve().parent / "tools"
    if not (packaged_tools / "tool-runtime.properties").is_file():
        return None
    cache_root = _resolved_tool_cache_root()
    if cache_root is None:
        return None
    version = _installed_pyronaut_version()
    if version is None:
        return None
    if not _resolved_tools_cache_complete(cache_root, packaged_tools, version):
        return None
    executable = cache_root / "tools" / command_name / "bin" / _launcher_file_name(command_name)
    return executable if executable.is_file() else None


def _resolved_tool_cache_root() -> Path | None:
    version = _installed_pyronaut_version()
    if version is None:
        return None
    safe_version = re.sub(r"[^A-Za-z0-9._-]", "_", version)
    cache_root = Path.home() / ".pyronaut" / "tools" / safe_version / "current"
    return cache_root if cache_root.is_dir() else None


def _resolved_tool_distribution_dir(command_name: str) -> Path | None:
    cache_root = _resolved_tool_cache_root()
    if cache_root is None:
        return None
    tool_dir = cache_root / "tools" / command_name
    return tool_dir if tool_dir.is_dir() else None


def _resolved_tools_cache_complete(cache_root: Path, packaged_tools: Path, version: str) -> bool:
    metadata_file = cache_root / "tool-runtime.properties"
    if not metadata_file.is_file():
        return False
    try:
        metadata = _read_tool_runtime_properties(metadata_file)
        packaged_metadata = _read_tool_runtime_properties(packaged_tools / "tool-runtime.properties")
        descriptors = sorted(packaged_tools.glob("*/bin/pyronaut-tool-classpath.tsv"))
        bundled_artifacts = {
            fields[1]
            for descriptor in descriptors
            for fields in (
                line.split("\t")
                for line in descriptor.read_text(encoding="utf-8").splitlines()
            )
            if len(fields) == 2 and fields[0] == "bundled"
        }
        for descriptor in descriptors:
            command = descriptor.parent.parent.name
            if not (cache_root / "tools" / command / "bin" / _launcher_file_name(command)).is_file():
                return False
            for line in descriptor.read_text(encoding="utf-8").splitlines():
                fields = line.split("\t")
                if len(fields) == 2 and fields[0] == "bundled":
                    artifact = cache_root / "tools" / "shared" / "lib" / fields[1]
                    expected_source = packaged_tools / "shared" / "lib" / fields[1]
                elif len(fields) == 7 and fields[0] == "maven":
                    artifact = cache_root / "tools" / "shared" / "lib" / fields[6]
                    local_repository = metadata.get("local.repository")
                    if not local_repository:
                        return False
                    if fields[6] in bundled_artifacts:
                        expected_source = packaged_tools / "shared" / "lib" / fields[6]
                    else:
                        expected_source = (
                            Path(local_repository)
                            / Path(*fields[1].split("."))
                            / fields[2]
                            / fields[3]
                            / fields[6]
                        )
                elif len(fields) == 7 and fields[0] == "control-panel":
                    artifact = cache_root / "tools" / "pyronaut-dev" / "lib" / "control-panel" / fields[6]
                    local_repository = metadata.get("local.repository")
                    if not local_repository:
                        return False
                    expected_source = (
                        Path(local_repository)
                        / Path(*fields[1].split("."))
                        / fields[2]
                        / fields[3]
                        / fields[6]
                    )
                else:
                    return False
                if not artifact.is_file() or not expected_source.is_file():
                    return False
                if artifact.is_symlink():
                    if artifact.resolve() != expected_source.resolve():
                        return False
                else:
                    try:
                        same_file = os.path.samefile(artifact, expected_source)
                    except OSError:
                        same_file = False
                    if not same_file and not filecmp.cmp(artifact, expected_source, shallow=False):
                        return False
        return (
            bool(descriptors)
            and metadata.get("sdk.version") == version
            and metadata.get("descriptor.sha256") == packaged_metadata.get("descriptor.sha256")
        )
    except (OSError, UnicodeError, ValueError):
        return False


def _read_tool_runtime_properties(path: Path) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in path.read_text(encoding="iso-8859-1").splitlines()
        if line and not line.startswith(("#", "!")) and "=" in line
    )


def _bundled_executable(command_name: str) -> Path | None:
    if sys.platform.startswith("linux") or sys.platform == "darwin" or sys.platform == "win32":
        package_root = Path(__file__).resolve().parent
        return package_root / "tools" / command_name / "bin" / _launcher_file_name(command_name)
    return None


def _launcher_file_name(command_name: str) -> str:
    return f"{command_name}.bat" if sys.platform == "win32" else command_name


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
        try:
            return str(_cached_native_image(command_name))
        except RuntimeError:
            return None

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
    try:
        return str(_cached_native_image(DEV_NATIVE_EXECUTABLE))
    except RuntimeError:
        return None


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
            # Pass the containing directories to keep the argument short on
            # Windows; NativeProvidedJarResolver lists the JARs in each one.
            provided_jar_dirs = sorted({str(Path(jar).parent) for jar in provided_jars})
            jvm_args.append(f"-Dpyronaut.dev.native.provided.jars={os.pathsep.join(provided_jar_dirs)}")
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
        os.pathsep.join(_native_launcher_compile_classpath_entries(
            executable_path,
            _extract_local_repository(args),
        ))
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
        test_resources_client_classpath = _build_native_test_resources_client_classpath(project_dir, env_overrides)
        if test_resources_client_classpath:
            jvm_args.append(f"-Dpyronaut.dev.test.resources.client.classpath={test_resources_client_classpath}")
        jvm_args.extend(_build_test_resources_jvm_args(env_overrides))
        jvm_args.extend(_test_resources_disabled_jvm_args(project_dir, args, env_overrides))
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
            _native_launcher_compile_classpath_entries(
                executable_path,
                _extract_local_repository(args),
            )
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
    env_overrides: dict[str, str] | None = None,
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
        *_test_resources_disabled_jvm_args(project_dir, args, env_overrides),
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
        # External Maven/Gradle dependency resolution lives in the wheel's
        # current JVM installer. Using the released native pyronaut-dev image
        # here would execute its AOT copy of the installer, which can be older
        # than the CLI wheel (and cannot receive wheel-only fixes). The native
        # runner remains the default for the actual external project launch.
        toolchain_type = TOOLCHAIN_TYPE_JVM if command == "install" else TOOLCHAIN_TYPE_NATIVE
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
    direct_options = {
        "--port", "--property", "-D", "--config", "--setup", "--report",
        "--disable-test-resources", "--control-panel", "--verbose", "--test",
        "--smoke", "--non-interactive", "--trace-delegation", "--progress",
        "--color", "--local-repository", "--local-repo", "--offline", "--refresh",
        "--no-cache",
    }
    value_options = {
        "--port", "--property", "-D", "--config", "--setup", "--progress",
        "--color", "--local-repository", "--local-repo",
    }
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--":
            return len(argv) > index + 1
        if arg in {"--native", "--jvm"}:
            index += 1
            continue
        if arg == "--mode":
            index += 2
            continue
        if arg.startswith("--mode="):
            index += 1
            continue
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


def _test_resources_client_disabled(
    project_dir: Path,
    args: Sequence[str] = (),
    env_overrides: dict[str, str] | None = None,
) -> bool:
    """Whether the application must not activate the Test Resources client.

    The client otherwise falls back to settings files such as
    ~/.micronaut/test-resources/test-resources.properties, which may name a
    server that has long since exited.
    """
    if env_overrides and (env_overrides.get("MICRONAUT_TEST_RESOURCES_SERVER_URI") or "").strip():
        return False
    if "--disable-test-resources" in args:
        return True
    if env_overrides and (env_overrides.get(_TEST_RESOURCES_DISABLED_ENV) or "").lower() in {"1", "true", "yes", "on"}:
        return True
    if not (project_dir / "pyproject.toml").is_file() and not _is_external_build_project(project_dir):
        # Direct-source invocations infer Test Resources in the launcher.
        return False
    return not _test_resources_enabled(project_dir)


def _test_resources_disabled_jvm_args(
    project_dir: Path,
    args: Sequence[str] = (),
    env_overrides: dict[str, str] | None = None,
) -> list[str]:
    if not _test_resources_client_disabled(project_dir, args, env_overrides):
        return []
    return list(_TEST_RESOURCES_DISABLED_JVM_ARGS)


def _apply_test_resources_disabled_java_tool_options(
    env: dict[str, str] | None,
    project_dir: Path,
    args: Sequence[str],
    env_overrides: dict[str, str] | None,
) -> dict[str, str] | None:
    """Disable the client for delegates launched without explicit JVM arguments."""
    disabled_args = _test_resources_disabled_jvm_args(project_dir, args, env_overrides)
    if not disabled_args:
        return env
    updated = dict(env or os.environ)
    existing = updated.get("JAVA_TOOL_OPTIONS", "").strip()
    updated["JAVA_TOOL_OPTIONS"] = " ".join(value for value in (existing, *disabled_args) if value)
    return updated


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


def _is_supported_project_platform(platform_name: str) -> bool:
    return _is_supported_platform(platform_name) or (
        platform_name == "win32" and bool(_read_env("PYRONAUT_DEV_NATIVE_EXECUTABLE"))
    )


def _print_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut [--version] [--allow-draft-release] [--tui [--smoke|--non-interactive]] <setup|update|doctor|clean|install|process|dev|run|test|build|create|validate-config|test-resources-server> [args...]\n")


def _print_create_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut create [NAME] [-f=FEATURE[,FEATURE...]] [-ivx] [--list-features]\n"
    )
    stream.write("Creates a Python application using the Micronaut Launch CLI.\n")
    stream.write("  -f, --features=FEATURE[,FEATURE...]  Features to include (repeatable).\n")
    stream.write("  -i, --inplace                       Create using the current directory.\n")
    stream.write("      --list-features                  List Python-compatible features.\n")
    stream.write("  -v, --verbose                       Enable verbose generation output.\n")
    stream.write("  -x, --stacktrace                    Show full stack traces on failures.\n")
    stream.write("  -h, --help                          Show this help message and exit.\n")


def _print_setup_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut setup [--local-repository <dir>] [--offline] [--refresh] "
        "[--progress <auto|on|off>] [--allow-draft-release]\n"
    )
    stream.write("Provision the global Pyronaut SDK toolchain, GraalPy interpreter, launchers, and native compiler classpaths.\n")


def _setup_local_repository(args: Sequence[str]) -> Path:
    configured = _extract_local_repository(args) or _read_env(LOCAL_REPOSITORY_ENV)
    if configured is None:
        if _setup_is_required():
            try:
                recorded = json.loads(_setup_manifest_path().read_text(encoding="utf-8")).get(
                    "localRepository"
                )
                if isinstance(recorded, str) and Path(recorded).is_absolute():
                    return Path(recorded).resolve()
            except (AttributeError, OSError, TypeError, ValueError):
                pass
        return (Path.home() / ".m2" / "repository").resolve()
    repository = Path(configured).expanduser()
    if not repository.is_absolute():
        repository = Path.cwd() / repository
    return repository.resolve()


def _setup_is_required() -> bool:
    return (Path(__file__).resolve().parent / "tools" / "tool-runtime.properties").is_file()


def _setup_repositories() -> list[str]:
    settings = _read_pyronaut_user_settings()
    maven = settings.get("maven")
    if maven is not None and not isinstance(maven, dict):
        raise RuntimeError("[maven] in ~/.pyronaut/settings.toml must be a table")
    configured = maven.get("repositories") if isinstance(maven, dict) else None
    if configured is not None:
        if not isinstance(configured, list) or not configured:
            raise RuntimeError("[maven].repositories must be a non-empty string array")
        repositories = []
        for index, value in enumerate(configured):
            if not isinstance(value, str) or not value.strip():
                raise RuntimeError(f"[maven].repositories[{index}] must be a non-empty string")
            repositories.append(value.strip())
        return repositories
    repositories = ["mavenCentral"]
    version = _installed_pyronaut_version()
    if version.endswith("-SNAPSHOT") or ".dev" in version:
        repositories.append(_SONATYPE_SNAPSHOTS_REPOSITORY)
    return repositories


def _setup_platform() -> str:
    os_segment, arch = _native_image_platform()
    return f"{os_segment}-{arch}"


def _setup_manifest_path() -> Path:
    version = re.sub(r"[^A-Za-z0-9._-]", "_", _installed_pyronaut_version())
    return Path.home() / ".pyronaut" / "setup" / version / _setup_platform() / "setup.json"


def _packaged_tool_descriptor_hash() -> str:
    metadata_file = Path(__file__).resolve().parent / "tools" / "tool-runtime.properties"
    if not metadata_file.is_file():
        raise RuntimeError("Installed Pyronaut wheel is missing tool runtime metadata")
    value = _read_tool_runtime_properties(metadata_file).get("descriptor.sha256")
    if not value:
        raise RuntimeError("Installed Pyronaut wheel is missing its tool descriptor hash")
    return value


def _packaged_sdk_descriptor_hash() -> str:
    package_root = Path(__file__).resolve().parent
    digest = hashlib.sha256()
    for name in ("version.properties", _PACKAGED_TOOLCHAIN_DEFAULTS):
        path = package_root / name
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        if path.is_file():
            digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _is_executable_file(path: Path) -> bool:
    return path.is_file() and os.access(path, os.X_OK)


def _resolved_tool_executables(tool_root: Path) -> dict[str, str]:
    packaged_tools = Path(__file__).resolve().parent / "tools"
    commands = sorted(
        descriptor.parent.parent.name
        for descriptor in packaged_tools.glob("*/bin/pyronaut-tool-classpath.tsv")
    )
    executables: dict[str, str] = {}
    for command in commands:
        executable = tool_root / "tools" / command / "bin" / _launcher_file_name(command)
        if not _is_executable_file(executable):
            raise RuntimeError(f"Resolved SDK executable is missing or not executable: {command}")
        executables[command] = str(executable.resolve())
    if not executables:
        raise RuntimeError("Installed Pyronaut wheel contains no SDK tool descriptors")
    return executables


def _native_compile_descriptor_entries(executable: str | Path) -> list[str]:
    entries = _native_launcher_manifest_entries(str(executable), "native-compile-classpath.txt")
    if not entries:
        raise RuntimeError(f"Missing native compiler classpath descriptor for {Path(executable).name}")
    for entry in entries:
        fields = entry.split("\t")
        if len(fields) != 7 or fields[0] != "maven":
            raise RuntimeError(f"Invalid native compiler classpath descriptor for {Path(executable).name}")
        if any(not field or "/" in field or "\\" in field for field in fields[1:5]):
            raise RuntimeError(f"Invalid native compiler Maven coordinate for {Path(executable).name}")
        if "/" in fields[5] or "\\" in fields[5]:
            raise RuntimeError(f"Invalid native compiler Maven classifier for {Path(executable).name}")
        expected_filename = (
            f"{fields[2]}-{fields[3]}"
            f"{'-' + fields[5] if fields[5] else ''}.{fields[4]}"
        )
        if not fields[6] or Path(fields[6]).name != fields[6] or fields[6] != expected_filename:
            raise RuntimeError(f"Invalid native compiler artifact filename for {Path(executable).name}")
    return entries


def _native_descriptor_hash(entries: Sequence[str]) -> str:
    return hashlib.sha256(("\n".join(entries) + "\n").encode("utf-8")).hexdigest()


def _setup_expectation(args: Sequence[str]) -> dict[str, object]:
    base_url, native_version, release_tag = _native_image_configuration()
    return {
        "schemaVersion": _SETUP_SCHEMA_VERSION,
        "sdkVersion": _installed_pyronaut_version(),
        "platform": _setup_platform(),
        "localRepository": str(_setup_local_repository(args)),
        "repositories": _setup_repositories(),
        "sdkDescriptorSha256": _packaged_sdk_descriptor_hash(),
        "toolDescriptorSha256": _packaged_tool_descriptor_hash(),
        "nativeImages": {
            "source": base_url,
            "version": native_version,
            "releaseTag": release_tag,
        },
        "graalpyVersion": graalpy.name if (graalpy := _required_graalpy()) is not None else None,
    }


def _read_valid_setup_manifest(args: Sequence[str]) -> dict[str, object]:
    manifest_path = _setup_manifest_path()
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError) as exc:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE) from exc
    if not isinstance(manifest, dict):
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    expected = _setup_expectation(args)
    for key in (
        "schemaVersion",
        "sdkVersion",
        "platform",
        "localRepository",
        "repositories",
        "sdkDescriptorSha256",
        "toolDescriptorSha256",
        "nativeImages",
        "graalpyVersion",
    ):
        if manifest.get(key) != expected.get(key):
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    if expected.get("graalpyVersion") is not None:
        graalpy = manifest.get("graalpy")
        executable = graalpy.get("executable") if isinstance(graalpy, dict) else None
        if not isinstance(executable, str) or not _is_executable_file(Path(executable)):
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    java_home = manifest.get("javaHome")
    if not isinstance(java_home, str) or not _is_executable_file(Path(java_home) / "bin" / "java"):
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    metadata = _read_graalvm_metadata(Path(java_home))
    current_graalvm = {
        "distribution": metadata.distribution if metadata else None,
        "version": metadata.version if metadata else None,
        "javaVersion": metadata.java_version if metadata else None,
    }
    if metadata is None or manifest.get("graalvm") != current_graalvm:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    tool_root = manifest.get("toolRuntime")
    if not isinstance(tool_root, str) or not Path(tool_root).is_dir():
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    packaged_tools = Path(__file__).resolve().parent / "tools"
    if not _resolved_tools_cache_complete(
        Path(tool_root), packaged_tools, str(expected["sdkVersion"])
    ):
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    try:
        expected_executables = _resolved_tool_executables(Path(tool_root))
    except RuntimeError as exc:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE) from exc
    if manifest.get("executables") != expected_executables:
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    images = manifest.get("images")
    if not isinstance(images, dict):
        raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    for image_name in _SETUP_IMAGE_COMMANDS:
        image = images.get(image_name)
        if not isinstance(image, dict):
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
        executable = image.get("executable")
        classpath = image.get("classpath")
        descriptor_hash = image.get("descriptorSha256")
        if not isinstance(executable, str) or not _is_executable_file(Path(executable)):
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
        if not isinstance(classpath, list) or not classpath:
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
        if not all(isinstance(path, str) and Path(path).is_file() for path in classpath):
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
        try:
            current_hash = _native_descriptor_hash(_native_compile_descriptor_entries(executable))
        except RuntimeError as exc:
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE) from exc
        if descriptor_hash != current_hash:
            raise RuntimeError(_SETUP_REQUIRED_MESSAGE)
    return manifest


def _require_setup(args: Sequence[str]) -> bool:
    global _validated_setup_manifest
    try:
        _validated_setup_manifest = _read_valid_setup_manifest(args)
        return True
    except RuntimeError:
        _validated_setup_manifest = None
        print(_SETUP_REQUIRED_MESSAGE, file=sys.stderr)
        return False


def _cached_native_image(
    image_name: str,
    *,
    platform_name: str | None = None,
    machine_name: str | None = None,
) -> Path:
    base_url, version, release_tag = _native_image_configuration()
    os_segment, arch = _native_image_platform(
        platform_name=platform_name,
        machine_name=machine_name,
    )
    executable = _native_image_cache_path(image_name, version=version, os_segment=os_segment, arch=arch)
    metadata = executable.with_name(executable.name + ".json")
    if not _is_executable_file(executable) or not metadata.is_file():
        raise RuntimeError(f"Native image {image_name} is not cached")
    try:
        cached_metadata = json.loads(metadata.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError) as exc:
        raise RuntimeError(f"Native image {image_name} cache metadata is invalid") from exc
    expected = {
        "source": base_url,
        "version": version,
        "release-tag": release_tag,
        "platform": f"{os_segment}-{arch}",
        "bundle-format": _NATIVE_IMAGE_BUNDLE_FORMAT,
    }
    if not isinstance(cached_metadata, dict) or any(
        cached_metadata.get(key) != value for key, value in expected.items()
    ):
        raise RuntimeError(f"Native image {image_name} cache is stale")
    return executable


def _validate_setup_arguments(args: Sequence[str]) -> None:
    value_options = {"--local-repository", "--local-repo", "--progress"}
    flag_options = {"--offline", "--refresh"}
    index = 0
    while index < len(args):
        token = args[index]
        if token in value_options:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {token}")
            index += 2
            continue
        if any(token.startswith(option + "=") for option in value_options):
            index += 1
            continue
        if token in flag_options:
            index += 1
            continue
        raise ValueError(f"Unknown pyronaut setup option: {token}")
    progress = _extract_option_value(args, "--progress")
    if progress is not None and progress not in {"auto", "on", "off"}:
        raise ValueError("Invalid value for --progress. Use auto, on, or off")


def _run_setup(args: Sequence[str], runner: RunnerWithEnv) -> int:
    progress = _progress_console()
    try:
        _validate_setup_arguments(args)
        refresh = _extract_flag(args, "--refresh")
        offline = _extract_offline(args)
        progress.configure(_extract_option_value(args, "--progress"))
        manifest_path = _setup_manifest_path()
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = manifest_path.with_suffix(".lock")
        with _native_image_cache_lock(lock_path):
            if not refresh:
                try:
                    _read_valid_setup_manifest(args)
                except RuntimeError:
                    pass
                else:
                    print(f"Pyronaut setup is ready at {manifest_path}")
                    return SUCCESS

            expectation = _setup_expectation(args)
            with progress.step("Locating GraalVM JDK (25+)") as jdk_step:
                java_home = _ensure_graalvm_java_home(None, offline=offline)
                if java_home is None:
                    qualifier = " cached" if offline else ""
                    raise RuntimeError(
                        f"Unable to locate or provision a{qualifier} compatible GraalVM JDK (requires JDK 25+)"
                    )
                jdk_step.done_label = f"GraalVM JDK ready: {java_home}"
            graalpy_spec = _required_graalpy()
            graalpy: _GraalPyInstallation | None = None
            if graalpy_spec is not None:
                with progress.step(f"Locating GraalPy {graalpy_spec.name}") as graalpy_step:
                    graalpy = _ensure_graalpy(runner, offline=offline)
                    if graalpy is None:
                        qualifier = " an installed" if offline else ""
                        raise RuntimeError(
                            f"Unable to locate or provision{qualifier} GraalPy {graalpy_spec.name}. "
                            f"Install it with: pyenv install {graalpy_spec.name}"
                        )
                    graalpy_step.done_label = f"GraalPy ready: {graalpy.executable}"
            with progress.step("Provisioning native launchers", done="Native launchers ready"):
                images: dict[str, Path] = {}
                for image_name in _SETUP_IMAGE_COMMANDS:
                    images[image_name] = (
                        _cached_native_image(image_name)
                        if offline
                        else _ensure_native_image(image_name)
                    )

            installer = _bundled_executable(COMMAND_TO_EXECUTABLE["install"])
            if installer is None or not _is_executable_file(installer):
                raise RuntimeError("Installed Pyronaut wheel is missing executable pyronaut-install")
            local_repository = _setup_local_repository(args)
            with progress.step("Seeding bundled Pyronaut modules into the Maven repository", done="Bundled Pyronaut modules seeded"):
                _seed_bundled_pyronaut_maven_repository(
                    Path.cwd(), str(local_repository), str(installer)
                )

            with tempfile.TemporaryDirectory(prefix=".setup-", dir=manifest_path.parent) as temp_dir:
                request_dir = Path(temp_dir) / "native-classpaths"
                request_dir.mkdir()
                descriptor_entries: dict[str, list[str]] = {}
                for image_name, executable in images.items():
                    entries = _native_compile_descriptor_entries(executable)
                    descriptor_entries[image_name] = entries
                    (request_dir / f"{image_name}.tsv").write_text(
                        "\n".join(entries) + "\n", encoding="utf-8"
                    )

                command_line = [
                    str(installer),
                    "--resolve-tools-only",
                    "--native-classpaths-dir",
                    str(request_dir),
                    "--local-repository",
                    str(local_repository),
                ]
                for repository in expectation["repositories"]:
                    command_line.extend(["--repository", str(repository)])
                if offline:
                    command_line.append("--offline")
                if refresh:
                    command_line.append("--refresh")
                # The nested installer renders its own per-artifact progress
                # directly on the terminal, so the setup spinner is suspended
                # while it runs.
                command_line.extend(["--progress", _extract_option_value(args, "--progress") or "auto"])
                env = _terminal_environment(dict(os.environ))
                env["JAVA_HOME"] = java_home
                java_bin = str(Path(java_home) / "bin")
                env["PATH"] = java_bin + (os.pathsep + env.get("PATH", "") if env.get("PATH") else "")
                packaged_tools = Path(__file__).resolve().parent / "tools"
                env["PYRONAUT_PACKAGED_TOOLS_DIR"] = str(packaged_tools)
                env["PYRONAUT_TOOLS_CACHE_DIR"] = str(Path.home() / ".pyronaut" / "tools")
                with progress.step("Resolving SDK dependencies", done="SDK dependencies resolved"):
                    with progress.suspend():
                        install_code = runner(command_line, env)
                    if install_code != SUCCESS:
                        raise RuntimeError("pyronaut-install failed to resolve SDK classpaths")

                tool_root = _resolved_tool_cache_root()
                if tool_root is None:
                    raise RuntimeError("pyronaut-install did not produce a complete SDK tool runtime")
                packaged_tools = Path(__file__).resolve().parent / "tools"
                if not _resolved_tools_cache_complete(
                    tool_root, packaged_tools, str(expectation["sdkVersion"])
                ):
                    raise RuntimeError("pyronaut-install produced an incomplete SDK tool runtime")
                executables = _resolved_tool_executables(tool_root)
                image_state: dict[str, object] = {}
                for image_name, executable in images.items():
                    resolved_file = request_dir / "resolved" / f"{image_name}.txt"
                    resolved = [
                        line.strip()
                        for line in resolved_file.read_text(encoding="utf-8").splitlines()
                        if line.strip()
                    ]
                    if len(resolved) != len(descriptor_entries[image_name]):
                        raise RuntimeError(f"Incomplete resolved compiler classpath for {image_name}")
                    if not all(Path(path).is_file() for path in resolved):
                        raise RuntimeError(f"Resolved compiler classpath for {image_name} contains missing files")
                    image_state[image_name] = {
                        "executable": str(executable.resolve()),
                        "descriptorSha256": _native_descriptor_hash(descriptor_entries[image_name]),
                        "classpath": resolved,
                    }
                metadata = _read_graalvm_metadata(Path(java_home))
                state = {
                    **expectation,
                    "javaHome": str(Path(java_home).resolve()),
                    "graalvm": {
                        "distribution": metadata.distribution if metadata else None,
                        "version": metadata.version if metadata else None,
                        "javaVersion": metadata.java_version if metadata else None,
                    },
                    "toolRuntime": str(tool_root.resolve()),
                    "executables": executables,
                    "images": image_state,
                }
                if graalpy is not None:
                    state["graalpy"] = {
                        "executable": str(graalpy.executable),
                        "home": str(graalpy.home),
                        "sitePackages": graalpy.site_packages,
                        "version": graalpy.version_line,
                    }
                temporary_manifest = Path(temp_dir) / "setup.json"
                temporary_manifest.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                os.replace(temporary_manifest, manifest_path)
        print(f"Pyronaut setup completed at {manifest_path}")
        return SUCCESS
    except (OSError, RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED


# -- pyronaut update ---------------------------------------------------------
#
# Update replaces the installed SDK wheel with one attached to a GitHub
# release, then hands over to the new wheel's own `pyronaut setup` so the
# native launchers, the Maven cache, and the setup state are provisioned by
# the code that will use them. Caches of older SDK versions are removed only
# once that setup has succeeded.

_UPDATE_WHEEL_PATTERN = re.compile(r"^pyronaut-([^-]+)-py3-none-any\.whl$")
_SDK_VERSION_PATTERN = re.compile(r"^v?(\d+(?:\.\d+)*)(?:[-.]?([A-Za-z]+)[-.]?(\d*))?$")
# Pre-release qualifiers in the forms used by release tags (1.0.0-M1,
# 1.0.0-RC1), Maven snapshots (1.0.0-SNAPSHOT), and PEP 440 wheel versions
# (1.0.0rc1, 1.0.0.dev0), ordered by maturity. A final release ranks above all.
_SDK_PRE_RELEASE_RANKS = {
    "dev": 0, "snapshot": 0, "a": 1, "alpha": 1, "m": 2, "b": 3, "beta": 3, "rc": 4, "cr": 4,
}
_SDK_FINAL_RANK = 5
_UPDATE_RELEASE_PAGES = 10


class _SdkVersion(NamedTuple):
    release: tuple[int, ...]
    rank: int
    number: int

    @property
    def prerelease(self) -> bool:
        return self.rank != _SDK_FINAL_RANK


class _SdkRelease(NamedTuple):
    tag: str
    version: _SdkVersion
    prerelease: bool
    wheel_name: str | None
    wheel_url: str | None

    @property
    def display_version(self) -> str:
        match = _UPDATE_WHEEL_PATTERN.match(self.wheel_name or "")
        return match.group(1) if match else self.tag.removeprefix("v")


def _parse_sdk_version(value: str) -> _SdkVersion | None:
    """Parse a release tag, Maven version, or PEP 440 wheel version for ordering."""
    match = _SDK_VERSION_PATTERN.match(value.strip())
    if match is None:
        return None
    release = tuple(int(part) for part in match.group(1).split("."))
    # 1.0 and 1.0.0 name the same release.
    while len(release) > 1 and release[-1] == 0:
        release = release[:-1]
    qualifier = (match.group(2) or "").lower()
    if not qualifier:
        return _SdkVersion(release, _SDK_FINAL_RANK, 0)
    rank = _SDK_PRE_RELEASE_RANKS.get(qualifier)
    if rank is None:
        return None
    return _SdkVersion(release, rank, int(match.group(3) or 0))


def _print_update_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut update [<version>] [--check] [--keep-old] [--local-repository <dir>] "
        "[--progress <auto|on|off>] [--allow-draft-release]\n"
    )
    stream.write(
        "Replace the installed Pyronaut wheel with a GitHub release (the latest one when <version> is omitted), "
        "provision its launchers and Maven artifacts, and remove older versions.\n"
    )


def _parse_update_arguments(args: Sequence[str]) -> tuple[str | None, list[str]]:
    """Split the optional version from the options, rejecting anything unknown."""
    value_options = {"--local-repository", "--local-repo", "--progress"}
    flag_options = {"--check", "--keep-old"}
    requested: str | None = None
    options: list[str] = []
    index = 0
    while index < len(args):
        token = args[index]
        if token in value_options:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {token}")
            options.extend(args[index:index + 2])
            index += 2
            continue
        if any(token.startswith(option + "=") for option in value_options) or token in flag_options:
            options.append(token)
            index += 1
            continue
        if token.startswith("-"):
            raise ValueError(f"Unknown pyronaut update option: {token}")
        if requested is not None:
            raise ValueError(f"pyronaut update accepts a single version, got '{requested}' and '{token}'")
        if _parse_sdk_version(token) is None:
            raise ValueError(f"Invalid Pyronaut version: {token}")
        requested = token
        index += 1
    progress = _extract_option_value(options, "--progress")
    if progress is not None and progress not in {"auto", "on", "off"}:
        raise ValueError("Invalid value for --progress. Use auto, on, or off")
    return requested, options


def _update_release_url() -> str:
    """Return the GitHub releases page that supplies SDK updates.

    A ``[native-images].base-url`` that points at another GitHub repository
    (a fork) supplies the wheel as well as the launchers; a local directory
    only replaces the launchers, so updates still come from Pyronaut.
    """
    configured = _read_pyronaut_user_settings().get(_NATIVE_IMAGE_SETTINGS_TABLE)
    base_url = configured.get("base-url") if isinstance(configured, dict) else None
    if isinstance(base_url, str) and _is_github_release_url(base_url.strip()):
        return base_url.strip()
    return _NATIVE_IMAGE_BASE_URL


def _sdk_releases(*, allow_draft: bool = False) -> list[_SdkRelease]:
    """List the published releases whose tags are SDK versions."""
    base_url = _update_release_url()
    api_root = _github_api_root(base_url)
    headers = _github_api_headers()
    releases: list[_SdkRelease] = []
    for page in range(1, _UPDATE_RELEASE_PAGES + 1):
        data = _github_api_json(f"{api_root}/releases?per_page=100&page={page}", headers)
        if data is None and page == 1:
            token_hint = (
                "; check GH_TOKEN, GITHUB_TOKEN, GITHUB_API_TOKEN, or PYRONAUT_RELEASE_TOKEN read access"
                if _github_token() is None
                else ""
            )
            raise RuntimeError(f"Unable to list Pyronaut releases at {base_url}{token_hint}")
        if not isinstance(data, list) or not data:
            break
        for item in data:
            if not isinstance(item, dict) or (item.get("draft") and not allow_draft):
                continue
            tag = item.get("tag_name")
            version = _parse_sdk_version(tag) if isinstance(tag, str) else None
            if version is None:
                continue
            wheel = next(
                (
                    asset
                    for asset in item.get("assets") or []
                    if isinstance(asset, dict)
                    and isinstance(asset.get("name"), str)
                    and _UPDATE_WHEEL_PATTERN.match(asset["name"])
                    and isinstance(asset.get("url"), str)
                ),
                None,
            )
            releases.append(_SdkRelease(
                tag=tag,
                version=version,
                prerelease=bool(item.get("prerelease")) or version.prerelease,
                wheel_name=wheel["name"] if wheel else None,
                wheel_url=wheel["url"] if wheel else None,
            ))
        if len(data) < 100:
            break
    return releases


def _installed_sdk_is_prerelease(installed: _SdkVersion, releases: Sequence[_SdkRelease]) -> bool:
    """Whether the installed SDK is a pre-release by its version or its GitHub release."""
    return installed.prerelease or any(
        release.prerelease for release in releases if release.version == installed
    )


def _select_update_release(
    releases: Sequence[_SdkRelease],
    *,
    installed_prerelease: bool,
    requested: str | None,
) -> _SdkRelease | None:
    """Pick the requested release, or the newest one the installed channel accepts.

    A stable installation only ever moves to stable releases; a pre-release
    installation may move to a newer pre-release as well.
    """
    if requested is not None:
        wanted = _parse_sdk_version(requested)
        release = next(
            (candidate for candidate in releases if candidate.tag in {requested, f"v{requested}"}),
            None,
        ) or next((candidate for candidate in releases if candidate.version == wanted), None)
        if release is None:
            raise RuntimeError(f"Pyronaut release {requested} was not found")
        if release.wheel_url is None:
            raise RuntimeError(f"Pyronaut release {release.tag} has no pyronaut wheel attached")
        return release
    candidates = [
        release
        for release in releases
        if release.wheel_url is not None and (installed_prerelease or not release.prerelease)
    ]
    return max(candidates, key=lambda release: release.version, default=None)


def _run_update(args: Sequence[str], runner: RunnerWithEnv) -> int:
    progress = _progress_console()
    try:
        requested, options = _parse_update_arguments(args)
        progress.configure(_extract_option_value(options, "--progress"))
        if not _setup_is_required():
            raise RuntimeError(
                "pyronaut update replaces an installed Pyronaut wheel, but this CLI runs from a source checkout"
            )
        installed = _installed_pyronaut_version()
        installed_version = _parse_sdk_version(installed)
        if installed_version is None:
            raise RuntimeError(f"Unable to determine the installed Pyronaut version (found '{installed}')")

        with progress.step("Checking for Pyronaut releases", done="Checked Pyronaut releases") as check_step:
            releases = _sdk_releases(allow_draft=_allow_draft_release)
            release = _select_update_release(
                releases,
                installed_prerelease=_installed_sdk_is_prerelease(installed_version, releases),
                requested=requested,
            )
            if release is not None and (release.version == installed_version or (
                requested is None and release.version < installed_version
            )):
                release = None
            if release is not None:
                check_step.done_label = f"Found Pyronaut {release.display_version} (installed {installed})"
        if release is None:
            print(f"Pyronaut {installed} is up to date")
            return SUCCESS
        target = release.display_version
        if _extract_flag(options, "--check"):
            print(f"Pyronaut {target} is available. Run pyronaut update to install it.")
            return SUCCESS

        # Resolve the Maven repository from the current setup state before the
        # wheel changes: the new version has no state of its own yet, and
        # would otherwise fall back to ~/.m2/repository.
        local_repository = _setup_local_repository(options)
        update_root = Path.home() / ".pyronaut" / "update"
        update_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".update-", dir=update_root) as temp_dir:
            assert release.wheel_name is not None and release.wheel_url is not None
            wheel = Path(temp_dir) / release.wheel_name
            download_headers = _github_api_headers()
            download_headers["Accept"] = "application/octet-stream"
            _download_url_with_progress(
                release.wheel_url, wheel, f"Downloading pyronaut {target} wheel", headers=download_headers
            )
            with progress.step(f"Installing pyronaut {target} wheel", done=f"Installed pyronaut {target} wheel"):
                pip_code = _run_showing_output_on_failure(
                    runner,
                    [
                        sys.executable, "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-input", "--no-deps",
                        str(wheel),
                    ],
                    None,
                )
                if pip_code != SUCCESS:
                    raise RuntimeError(f"pip failed to install {wheel.name} with {sys.executable}")

        # The new wheel provisions its own launchers, Maven artifacts, and
        # setup state. It renders its own progress on the shared timeline.
        setup_command = [sys.executable, "-m", "pyronaut_cli_v2"]
        if _allow_draft_release:
            setup_command.append("--allow-draft-release")
        setup_command.extend(["setup", "--local-repository", str(local_repository)])
        setup_command.extend(["--progress", _extract_option_value(options, "--progress") or "auto"])
        if runner(setup_command, _terminal_environment(dict(os.environ))) != SUCCESS:
            raise RuntimeError(
                f"Pyronaut {target} is installed, but its setup failed. Resolve the problem above and run pyronaut setup."
            )

        summary = ""
        if not _extract_flag(options, "--keep-old"):
            with progress.step("Removing older Pyronaut versions") as cleanup_step:
                removed, freed = _remove_older_sdk_versions(release.version, local_repository)
                if removed:
                    summary = f"; removed {', '.join(removed)} and freed {_format_bytes(freed)}"
                    cleanup_step.done_label = f"Removed Pyronaut {', '.join(removed)}"
                else:
                    cleanup_step.done_label = "No older Pyronaut versions to remove"
        print(f"Pyronaut updated from {installed} to {target}{summary}")
        return SUCCESS
    except (OSError, RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED


def _older_sdk_version_dirs(parent: Path, keep: _SdkVersion, pinned: frozenset[str] = frozenset()) -> list[Path]:
    """Version-named directories below ``parent`` that are older than ``keep``."""
    if not parent.is_dir():
        return []
    older = []
    for candidate in parent.iterdir():
        if not candidate.is_dir() or candidate.is_symlink() or candidate.name in pinned:
            continue
        version = _parse_sdk_version(candidate.name)
        if version is not None and version < keep:
            older.append(candidate)
    return older


def _directory_size(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += os.lstat(os.path.join(root, name)).st_size
            except OSError:
                pass
    return total


def _remove_older_sdk_versions(keep: _SdkVersion, local_repository: Path) -> tuple[list[str], int]:
    """Delete the launchers, tool runtimes, setup state, and seeded Maven
    artifacts of SDK versions older than ``keep``.

    Newer versions are never touched, so a downgrade keeps them, and neither
    is a launcher version pinned by ``[native-images].version``.
    """
    configured = _read_pyronaut_user_settings().get(_NATIVE_IMAGE_SETTINGS_TABLE)
    pinned_images = (
        frozenset({_native_image_distribution_version(configured["version"].strip())})
        if isinstance(configured, dict) and isinstance(configured.get("version"), str)
        else frozenset()
    )
    pyronaut_home = Path.home() / ".pyronaut"
    candidates = _older_sdk_version_dirs(pyronaut_home / "bin", keep, pinned_images)
    for parent in (pyronaut_home / "tools", pyronaut_home / "setup"):
        candidates.extend(_older_sdk_version_dirs(parent, keep))
    maven_group = local_repository / "io" / "micronaut" / "pyronaut"
    if maven_group.is_dir():
        for artifact in sorted(maven_group.iterdir()):
            if artifact.is_dir() and artifact.name.startswith("micronaut-pyronaut"):
                candidates.extend(_older_sdk_version_dirs(artifact, keep))
    removed: set[_SdkVersion] = set()
    names: dict[_SdkVersion, str] = {}
    freed = 0
    for directory in candidates:
        size = _directory_size(directory)
        shutil.rmtree(directory, ignore_errors=True)
        if directory.exists():
            continue
        freed += size
        version = _parse_sdk_version(directory.name)
        assert version is not None
        removed.add(version)
        # Prefer the plain release spelling (0.0.4) over a Maven or PEP 440 one.
        names[version] = min(names.get(version, directory.name), directory.name, key=len)
    return [names[version] for version in sorted(removed)], freed


# -- GraalPy interpreter -----------------------------------------------------
#
# Python application code and tests run on the GraalPy runtime embedded in the
# SDK, and packages come from a project virtualenv created by the matching
# standalone GraalPy. Setup locates that interpreter (the running Python, the
# Pyronaut SDK cache, or pyenv) and otherwise installs it through pyenv or
# downloads the release into ~/.pyronaut/sdks, the same place as the JDK.

_GRAALPY_RELEASES_URL = "https://github.com/oracle/graalpython/releases/download"
_GRAALPY_PROBE_TIMEOUT = 60.0
_GRAALPY_PROBE = (
    "import json, sys, sysconfig; print(json.dumps({"
    "'implementation': sys.implementation.name, "
    "'basePrefix': sys.base_prefix, "
    "'prefix': sys.prefix, "
    "'sitePackages': sysconfig.get_paths().get('purelib')}))"
)


class _GraalPySpec(NamedTuple):
    # Standalone distribution name, which is also the pyenv version name,
    # for example graalpy3.13-25.4.4.
    name: str
    # Exact runtime version reported by `graalpy --version`, for example
    # 25.4.4.1.1. This is the GraalPy Maven version the SDK is built with.
    version: str | None
    release_tag: str


class _GraalPyInstallation(NamedTuple):
    executable: Path
    home: Path
    site_packages: str | None
    version_line: str


def _required_graalpy() -> _GraalPySpec | None:
    """Return the GraalPy interpreter this Pyronaut build requires.

    A source checkout has no ``version.properties`` and no requirement.
    """
    values = _read_version_properties()
    name = values.get("graalpy.pyenv", "").strip()
    if not name:
        return None
    version = values.get("graalpy", "").strip() or None
    release_tag = values.get("graalpy.release-tag", "").strip() or _graalpy_default_release_tag(name)
    return _GraalPySpec(name, version, release_tag)


def _graalpy_default_release_tag(name: str) -> str:
    # GraalPy releases are tagged by the first three version components:
    # graalpy3.13-25.3.4.1 is published under graal-25.3.4.
    runtime_version = name.rsplit("-", 1)[-1]
    return "graal-" + ".".join(runtime_version.split(".")[:3])


def _graalpy_display_version(spec: _GraalPySpec | None = None) -> str:
    spec = spec or _required_graalpy()
    if spec is None:
        return "unknown"
    return f"{spec.name} ({spec.version})" if spec.version else spec.name


def _graalpy_sdk_home(spec: _GraalPySpec) -> Path:
    return _graalvm_jdks_root() / spec.name


def _graalpy_executable_in(home: Path) -> Path | None:
    for name in ("graalpy", "graalpy.exe", "python3", "python3.exe", "python", "python.exe"):
        candidate = home / "bin" / name
        if _is_executable_file(candidate):
            return candidate
    return None


def _probe_graalpy(executable: Path, spec: _GraalPySpec) -> _GraalPyInstallation | None:
    """Return the installation when ``executable`` is the required GraalPy."""
    try:
        version = subprocess.run(
            [str(executable), "--version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=_GRAALPY_PROBE_TIMEOUT,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    version_line = _last_line(version.stdout) or _last_line(version.stderr)
    if version.returncode != 0 or "graalpy" not in version_line.lower():
        return None
    if not _graalpy_versions_match(version_line, spec.version, spec.name):
        return None
    try:
        probe = subprocess.run(
            [str(executable), "-c", _GRAALPY_PROBE],
            check=False,
            capture_output=True,
            text=True,
            timeout=_GRAALPY_PROBE_TIMEOUT,
        )
        details = json.loads(_last_line(probe.stdout)) if probe.returncode == 0 else None
    except (OSError, subprocess.SubprocessError, ValueError):
        details = None
    if not isinstance(details, dict) or details.get("implementation") != "graalpy":
        return None
    home = Path(str(details.get("basePrefix") or executable.parent.parent))
    # A virtualenv interpreter is not a base installation: use the base one
    # so project environments are not created from another environment.
    base_executable = _graalpy_executable_in(home) if details.get("prefix") != details.get("basePrefix") else executable
    if base_executable is None:
        return None
    site_packages = details.get("sitePackages")
    if base_executable != executable:
        return _probe_graalpy(base_executable, spec)
    return _GraalPyInstallation(
        executable=executable.absolute(),
        home=home,
        site_packages=site_packages if isinstance(site_packages, str) else None,
        version_line=version_line,
    )


def _running_graalpy() -> Path | None:
    """The base GraalPy running the CLI, when the CLI was installed into GraalPy."""
    if getattr(sys.implementation, "name", "") != "graalpy":
        return None
    return _graalpy_executable_in(Path(sys.base_prefix))


def _graalpy_candidates(spec: _GraalPySpec) -> list[Path]:
    """Interpreters that may already be the required GraalPy, in priority order."""
    candidates: list[Path] = []
    running = _running_graalpy()
    if running is not None:
        candidates.append(running)
    sdk_executable = _graalpy_executable_in(_graalpy_sdk_home(spec))
    if sdk_executable is not None:
        candidates.append(sdk_executable)
    pyenv_executable = _graalpy_executable_in(_pyenv_root() / "versions" / spec.name)
    if pyenv_executable is not None:
        candidates.append(pyenv_executable)
    on_path = shutil.which("graalpy")
    if on_path:
        candidates.append(Path(on_path))
    unique: list[Path] = []
    for candidate in candidates:
        if candidate not in unique:
            unique.append(candidate)
    return unique


def _find_graalpy(spec: _GraalPySpec) -> _GraalPyInstallation | None:
    for candidate in _graalpy_candidates(spec):
        installation = _probe_graalpy(candidate, spec)
        if installation is not None:
            return installation
    return None


def _pyenv_executable() -> str | None:
    root_executable = _pyenv_root() / "bin" / "pyenv"
    if _is_executable_file(root_executable):
        return str(root_executable)
    return shutil.which("pyenv")


def _install_graalpy_with_pyenv(spec: _GraalPySpec, runner: RunnerWithEnv) -> _GraalPyInstallation | None:
    pyenv = _pyenv_executable()
    if pyenv is None:
        return None
    progress = _progress_console()
    with progress.step(f"Installing {spec.name} with pyenv", done=f"Installed {spec.name} with pyenv") as step:
        # pyenv renders its own download output.
        with progress.suspend():
            exit_code = runner([pyenv, "install", "--skip-existing", spec.name], None)
        step.failed = exit_code != SUCCESS
    if exit_code != SUCCESS:
        progress.warn(f"pyenv could not install {spec.name}; downloading it into {_graalvm_jdks_root()} instead")
        return None
    executable = _graalpy_executable_in(_pyenv_root() / "versions" / spec.name)
    return _probe_graalpy(executable, spec) if executable is not None else None


def _graalpy_archive_name(spec: _GraalPySpec) -> str:
    os_segment, arch = _native_image_platform()
    if os_segment == "macos" and arch != "aarch64":
        raise RuntimeError(f"GraalPy {spec.name} is not published for Intel macOS")
    return f"{spec.name}-{os_segment}-{arch}.tar.gz"


def _download_and_install_graalpy(spec: _GraalPySpec) -> _GraalPyInstallation | None:
    archive_name = _graalpy_archive_name(spec)
    archive_url = f"{_GRAALPY_RELEASES_URL}/{spec.release_tag}/{archive_name}"
    destination = _graalpy_sdk_home(spec)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".graalpy-", dir=destination.parent) as temp_dir:
        temp_path = Path(temp_dir)
        archive = temp_path / archive_name
        checksum_file = temp_path / (archive_name + ".sha256")
        _download_url_with_progress(archive_url, archive, f"Downloading GraalPy {spec.name}")
        _download_url_with_progress(archive_url + ".sha256", checksum_file, f"Downloading GraalPy {spec.name} checksum")
        expected_checksum = checksum_file.read_text(encoding="utf-8").split()[0].strip().lower()
        digest = hashlib.sha256()
        with archive.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() != expected_checksum:
            raise RuntimeError(f"Checksum mismatch for {archive_url}")
        extract_dir = temp_path / "extract"
        with _progress_console().step("Unpacking GraalPy", done="Unpacked GraalPy"):
            with tarfile.open(archive, "r:gz") as archive_file:
                try:
                    archive_file.extractall(extract_dir, filter="tar")
                except TypeError:
                    # Python without extraction filters (before 3.10.12).
                    archive_file.extractall(extract_dir)
        homes = [child for child in extract_dir.iterdir() if _graalpy_executable_in(child) is not None]
        if len(homes) != 1:
            raise RuntimeError(f"Unexpected GraalPy archive layout in {archive_name}")
        if destination.exists():
            shutil.rmtree(destination)
        os.replace(homes[0], destination)
    executable = _graalpy_executable_in(destination)
    return _probe_graalpy(executable, spec) if executable is not None else None


def _ensure_graalpy(runner: RunnerWithEnv, *, offline: bool = False) -> _GraalPyInstallation | None:
    """Locate or provision the GraalPy interpreter this Pyronaut requires."""
    spec = _required_graalpy()
    if spec is None:
        return None
    found = _find_graalpy(spec)
    if found is not None or offline:
        return found
    installed = _install_graalpy_with_pyenv(spec, runner)
    if installed is not None:
        return installed
    return _download_and_install_graalpy(spec)


def _configured_graalpy() -> dict[str, str] | None:
    """The GraalPy recorded by the validated setup manifest, if any."""
    manifest = _validated_setup_manifest
    graalpy = manifest.get("graalpy") if isinstance(manifest, dict) else None
    if not isinstance(graalpy, dict) or not isinstance(graalpy.get("executable"), str):
        return None
    return graalpy


def _apply_configured_graalpy(env: dict[str, str]) -> dict[str, str]:
    """Point delegated commands at the configured GraalPy interpreter.

    An explicit ``PYRONAUT_PYTHON_EXECUTABLE`` wins, and a project ``.venv``
    is applied afterwards by :func:`_apply_project_virtualenv`.
    """
    graalpy = _configured_graalpy()
    if graalpy is None or env.get("PYRONAUT_PYTHON_EXECUTABLE"):
        return env
    executable = Path(graalpy["executable"])
    env["PYRONAUT_PYTHON_EXECUTABLE"] = str(executable)
    site_packages = graalpy.get("sitePackages")
    if isinstance(site_packages, str) and site_packages:
        env["PYRONAUT_PYTHON_SITE_PACKAGES"] = site_packages
    env["PATH"] = _prepend_path_entry(env.get("PATH", ""), str(executable.parent))
    return env


def _graalpy_for_project_environment() -> Path | None:
    """The interpreter used to create a project ``.venv``."""
    graalpy = _configured_graalpy()
    if graalpy is not None:
        return Path(graalpy["executable"])
    spec = _required_graalpy()
    if spec is not None:
        found = _find_graalpy(spec)
        return found.executable if found is not None else None
    # Source checkout: no pinned version, so accept the running GraalPy or
    # the one selected by pyenv / PATH.
    running = _running_graalpy()
    if running is not None:
        return running
    selected = _find_global_graalpy()
    if selected is not None and _is_executable_file(selected[0]):
        return selected[0]
    return None


# -- Project Python environment -----------------------------------------------
#
# `pyronaut install` creates the project `.venv` with the configured GraalPy and
# installs the Python dependencies declared in pyproject.toml and
# requirements.txt, so the embedded runtime and pytest can import them.

_PROJECT_VENV_STATE = ".pyronaut-requirements.json"


def _project_python_requirements(project_dir: Path) -> tuple[list[str], list[Path]]:
    """Return declared requirement specifiers and requirements files."""
    requirements: list[str] = []
    data = _read_pyproject_data(project_dir)
    project = data.get("project") if isinstance(data, dict) else None
    declared = project.get("dependencies") if isinstance(project, dict) else None
    if isinstance(declared, list):
        requirements.extend(value.strip() for value in declared if isinstance(value, str) and value.strip())
    groups = data.get("dependency-groups") if isinstance(data, dict) else None
    if isinstance(groups, dict):
        requirements.extend(_expand_dependency_groups(groups))
    requirement_files = [project_dir / "requirements.txt"] if (project_dir / "requirements.txt").is_file() else []
    declared_names = {
        _canonical_package_name(match.group(1))
        for value in requirements
        if (match := re.match(r"\s*([A-Za-z0-9][A-Za-z0-9._-]*)", value)) is not None
    }
    declares_pytest = "pytest" in declared_names or any(
        re.search(r"(?im)^\s*pytest\b", path.read_text(encoding="utf-8")) for path in requirement_files
    )
    has_tests = (project_dir / _read_pyproject_sources(project_dir).python_test_dir).is_dir()
    if has_tests and not declares_pytest:
        # `pyronaut test` imports pytest from the project environment.
        requirements.append("pytest")
    unique: list[str] = []
    for requirement in requirements:
        if requirement not in unique:
            unique.append(requirement)
    return unique, requirement_files


def _expand_dependency_groups(groups: dict[str, object]) -> list[str]:
    """Flatten PEP 735 ``[dependency-groups]``, following ``include-group``."""
    requirements: list[str] = []

    def visit(name: str, seen: tuple[str, ...]) -> None:
        if name in seen:
            return
        entries = groups.get(name)
        if not isinstance(entries, list):
            return
        for entry in entries:
            if isinstance(entry, str) and entry.strip():
                requirements.append(entry.strip())
            elif isinstance(entry, dict) and isinstance(entry.get("include-group"), str):
                visit(entry["include-group"], (*seen, name))

    for group_name in groups:
        visit(group_name, ())
    return requirements


def _project_venv_state(graalpy: Path, requirements: Sequence[str], requirement_files: Sequence[Path]) -> dict[str, object]:
    digest = hashlib.sha256()
    for path in requirement_files:
        digest.update(path.name.encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return {
        "graalpy": str(graalpy),
        "requirements": list(requirements),
        "requirementFilesSha256": digest.hexdigest(),
    }


def _ensure_project_virtualenv(
    project_dir: Path,
    runner: RunnerWithEnv,
    *,
    refresh: bool = False,
    offline: bool = False,
) -> int:
    """Create ``.venv`` with GraalPy and install declared Python dependencies."""
    requirements, requirement_files = _project_python_requirements(project_dir)
    if not requirements and not requirement_files:
        return SUCCESS
    progress = _progress_console()
    venv_dir = project_dir / ".venv"
    venv_bin = _virtualenv_bin(venv_dir)
    venv_python = _resolve_virtualenv_python(venv_bin) if venv_dir.is_dir() else None
    if venv_dir.is_dir():
        if venv_python is None or not _virtualenv_is_graalpy(venv_python):
            print(
                f"{venv_dir} was not created with GraalPy, so the embedded runtime cannot use its packages. "
                f"Remove {venv_dir} and run pyronaut install again.",
                file=sys.stderr,
            )
            return PRECONDITION_FAILED
        base_home = _virtualenv_base_home(venv_dir)
        if base_home is not None and not base_home.is_dir():
            print(
                f"{venv_dir} was created from {base_home}, which no longer exists. "
                f"Remove {venv_dir} and run pyronaut install again.",
                file=sys.stderr,
            )
            return PRECONDITION_FAILED
        graalpy = venv_python
    else:
        graalpy = _graalpy_for_project_environment()
        if graalpy is None:
            spec = _required_graalpy()
            wanted = f"GraalPy {spec.name}" if spec is not None else "a GraalPy interpreter"
            print(f"Unable to locate {wanted} to create {venv_dir}. Run pyronaut setup.", file=sys.stderr)
            return PRECONDITION_FAILED
        with progress.step(f"Creating {_display_path(venv_dir)} with GraalPy", done=f"Created {_display_path(venv_dir)} with GraalPy"):
            venv_env = dict(os.environ)
            venv_env.pop("VIRTUAL_ENV", None)
            venv_env.pop("PYTHONHOME", None)
            exit_code = _run_showing_output_on_failure(
                runner, [str(graalpy), "-m", "venv", str(venv_dir)], venv_env
            )
        if exit_code != SUCCESS:
            return exit_code
        venv_bin = _virtualenv_bin(venv_dir)
        venv_python = _resolve_virtualenv_python(venv_bin)
        if venv_python is None:
            print(f"GraalPy did not create an interpreter in {venv_dir}", file=sys.stderr)
            return PRECONDITION_FAILED

    state_file = venv_dir / _PROJECT_VENV_STATE
    state = _project_venv_state(graalpy, requirements, requirement_files)
    if not refresh:
        try:
            recorded = json.loads(state_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            recorded = None
        if isinstance(recorded, dict) and recorded.get("requirements") == state["requirements"] and recorded.get(
            "requirementFilesSha256"
        ) == state["requirementFilesSha256"]:
            return SUCCESS
    if offline:
        progress.warn("Python dependencies are not installed in .venv; skipped pip install (--offline)")
        return SUCCESS

    command_line = [str(venv_python), "-m", "pip", "install", "--disable-pip-version-check"]
    for path in requirement_files:
        command_line.extend(["-r", str(path)])
    command_line.extend(requirements)
    if _delegation_trace_enabled():
        print(shlex.join(command_line), file=sys.stderr)
    env = dict(os.environ)
    env.pop("VIRTUAL_ENV", None)
    env.pop("PYTHONHOME", None)
    env["PATH"] = _prepend_path_entry(env.get("PATH", ""), str(venv_bin))
    with progress.step("Installing Python dependencies into .venv", done="Python dependencies installed into .venv") as step:
        exit_code = _run_showing_output_on_failure(runner, command_line, env)
        step.failed = exit_code != SUCCESS
    if exit_code != SUCCESS:
        return exit_code
    state_file.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    return SUCCESS


# -- pyronaut doctor ---------------------------------------------------------
#
# Read-only environment checks. Every check reuses the discovery and
# validation helpers that the real commands run, so a passing doctor row means
# the corresponding command would get past that precondition.

_DOCTOR_MIN_PYTHON = (3, 10)
_DOCTOR_SUBPROCESS_TIMEOUT = 30.0
_DOCTOR_PROXY_ENV = ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
_DOCTOR_NO_PROXY_ENV = ("NO_PROXY", "no_proxy")
_GRAALVM_DISTRIBUTION_LABELS = {"ee": "Oracle GraalVM", "ce": "GraalVM Community", "dev": "GraalVM dev build"}


def _print_doctor_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut doctor [--project-dir <dir>] [--json] [--offline] [--progress <auto|on|off>]\n")
    stream.write("Check the local Pyronaut environment and suggest a fix for anything that is not ready.\n")
    stream.write("  --project-dir <dir>       Project to inspect (default: the current directory).\n")
    stream.write("      --json                Print the report as JSON on stdout.\n")
    stream.write("      --offline             Use only cached GraalPy compatibility data; never download.\n")
    stream.write("      --progress <mode>     Live progress rendering: auto, on, or off.\n")
    stream.write("  -h, --help                Show this help message and exit.\n")


def _validate_doctor_arguments(args: Sequence[str]) -> None:
    value_options = {"--project-dir", "--progress"}
    flag_options = {"--json", "--offline"}
    index = 0
    while index < len(args):
        token = args[index]
        if token in value_options:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {token}")
            index += 2
            continue
        if any(token.startswith(option + "=") for option in value_options):
            index += 1
            continue
        if token in flag_options:
            index += 1
            continue
        raise ValueError(f"Unknown pyronaut doctor option: {token}")
    progress = _extract_option_value(args, "--progress")
    if progress is not None and progress not in {"auto", "on", "off"}:
        raise ValueError("Invalid value for --progress. Use auto, on, or off")


def _run_doctor(args: Sequence[str]) -> int:
    args = _normalize_project_flag(list(args))
    try:
        _validate_doctor_arguments(args)
    except ValueError as exc:
        return _usage_error(str(exc))
    json_output = _extract_flag(args, "--json")
    offline = _extract_offline(args)
    progress = _progress_console()
    progress.configure("off" if json_output else _extract_option_value(args, "--progress"))

    requested_dir = _extract_project_dir(args)
    project_dir = Path(requested_dir).resolve()
    # An explicit --project-dir is always inspected as a project so a missing
    # pyproject.toml is reported instead of silently skipping those checks.
    in_project = requested_dir != "." or (project_dir / "pyproject.toml").is_file()
    checks = _doctor_checks(args, project_dir if in_project else None, offline=offline)

    if json_output:
        results = _doctor.run_checks(checks)
        sys.stdout.write(
            _doctor.to_json(
                results,
                version=_installed_pyronaut_version(),
                platform=_doctor_platform(),
                project_dir=str(project_dir) if in_project else None,
            )
        )
        sys.stdout.flush()
    else:
        with progress.step("Pyronaut doctor", persist=False) as task:
            def on_start(title: str) -> None:
                task.label = f"Checking {title}"

            results = _doctor.run_checks(
                checks,
                on_start=on_start,
                on_result=lambda result: _doctor.render_row(progress, result),
            )
        if not in_project:
            progress.hint(f"No pyproject.toml in {project_dir}: project checks skipped (use --project-dir)")
        print(_doctor.summary_line(results))
    return PRECONDITION_FAILED if _doctor.overall_status(results) == _doctor.FAIL else SUCCESS


# Outputs written by ``pyronaut process`` under the project's generated-output
# directory. Dependency manifests, the project-local Maven repository, schemas,
# IDE stubs and the compiler daemon's endpoint belong to ``pyronaut install`` or
# other commands and survive a default ``pyronaut clean``.
_PROCESS_OUTPUTS = (
    "classes",
    "test-classes",
    "test-sources",
    "incremental",
    "processor.inputs",
    "external-python",
    "external-test-python",
    "external-main-sources",
    "external-test-sources",
)
_PROCESS_OUTPUT_PATTERNS = ("classes-native-runtime-*",)


def _print_clean_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write("Usage: pyronaut clean [--project-dir <dir>] [-f|--full]\n")
    stream.write("Remove the output of pyronaut process (processed classes and incremental state).\n")
    stream.write("  --project-dir <dir>       Project to clean (default: the current directory).\n")
    stream.write("  -f, --full                Also remove all generated Pyronaut state and caches and the\n")
    stream.write("                            project .venv; run pyronaut install again afterwards.\n")
    stream.write("  -h, --help                Show this help message and exit.\n")


def _clean_targets(project_dir: Path, full: bool) -> list[Path]:
    output_dir = _pyronaut_output_dir(project_dir)
    if full:
        candidates = [output_dir, project_dir / "__pyronaut__", project_dir / ".venv"]
    else:
        candidates = [output_dir / name for name in _PROCESS_OUTPUTS]
        for pattern in _PROCESS_OUTPUT_PATTERNS:
            candidates.extend(sorted(output_dir.glob(pattern)))
    targets: list[Path] = []
    for candidate in candidates:
        if (candidate.exists() or candidate.is_symlink()) and candidate not in targets:
            targets.append(candidate)
    return targets


def _remove_path(path: Path) -> None:
    # Never follow a symlinked .venv or output directory into its target.
    if path.is_symlink() or not path.is_dir():
        path.unlink()
    else:
        shutil.rmtree(path)


def _run_clean(args: Sequence[str]) -> int:
    args = _normalize_project_flag(list(args))
    full = False
    index = 0
    while index < len(args):
        token = args[index]
        if token in {"-f", "--full"}:
            full = True
        elif token == "--project-dir":
            if index + 1 >= len(args):
                return _usage_error("Missing value for --project-dir")
            index += 1
        elif not token.startswith("--project-dir="):
            return _usage_error(f"Unknown pyronaut clean option: {token}")
        index += 1

    project_dir = Path(_extract_project_dir(args)).resolve()
    if not project_dir.is_dir():
        print(f"Project directory does not exist: {project_dir}", file=sys.stderr)
        return PRECONDITION_FAILED
    if not (project_dir / "pyproject.toml").is_file() and not _is_external_build_project(project_dir):
        # Guard --full in particular: never delete a .venv outside a project.
        print(f"No pyproject.toml in {project_dir}: not a Pyronaut project (use --project-dir)", file=sys.stderr)
        return PRECONDITION_FAILED

    progress = _progress_console()
    targets = _clean_targets(project_dir, full)
    if not targets:
        progress.note("Nothing to clean")
        return SUCCESS
    for target in targets:
        try:
            _remove_path(target)
        except OSError as exc:
            progress.fail(f"Unable to remove {target}: {exc}")
            return INTERNAL_ERROR
        try:
            shown = target.relative_to(project_dir)
        except ValueError:
            shown = target
        progress.success(f"Removed {shown}")
    if full:
        progress.hint("Run pyronaut install to recreate .venv and the dependency manifests")
    return SUCCESS


def _doctor_platform() -> str:
    try:
        return _setup_platform()
    except RuntimeError:
        return f"{sys.platform}-{platform.machine().lower()}"


def _doctor_checks(args: Sequence[str], project_dir: Path | None, *, offline: bool = False) -> list[tuple[str, str, _doctor.Check]]:
    checks: list[tuple[str, str, _doctor.Check]] = [
        ("python", "Python", _doctor_check_python),
        ("pyronaut", "Pyronaut SDK", lambda: _doctor_check_setup_state(args)),
        ("graalvm", "GraalVM JDK", lambda: _doctor_check_graalvm(project_dir)),
        ("graalpy", "GraalPy", lambda: _doctor_check_graalpy(project_dir)),
        ("interpreter", "Interpreter", lambda: _doctor_check_interpreter(project_dir)),
        ("launchers", "Native launchers", _doctor_check_native_launchers),
    ]
    if project_dir is not None:
        # The runtime configuration is read once and shared by the checks
        # that must mirror how the application actually runs.
        runtime = _lazy(lambda: _doctor_runtime_config(project_dir))
        checks.extend(
            [
                ("pyproject", "pyproject.toml", lambda: _doctor_check_pyproject(project_dir)),
                ("install", "Classpath manifests", lambda: _doctor_check_install_manifests(project_dir)),
                ("state", "Generated state", lambda: _doctor_check_generated_state(project_dir)),
                ("threading", "Threading", lambda: _doctor_check_threading(project_dir, runtime())),
                ("packages", "Python packages", lambda: _doctor_check_packages(project_dir, runtime())),
                ("compat", "GraalPy compatibility", lambda: _doctor_check_graalpy_compatibility(project_dir, offline=offline)),
                ("pytest", "pytest", lambda: _doctor_check_pytest(project_dir)),
            ]
        )
    checks.extend(
        [
            ("proxy", "Proxy", _doctor_check_proxy),
            ("docker", "Docker", _doctor_check_docker),
        ]
    )
    return checks


def _lazy(factory: Callable[[], object]) -> Callable[[], object]:
    """Memoize ``factory`` so sibling checks share one computed value."""
    cache: list[object] = []

    def value() -> object:
        if not cache:
            cache.append(factory())
        return cache[0]

    return value


def _doctor_capture(command_line: list[str], timeout: float = _DOCTOR_SUBPROCESS_TIMEOUT) -> tuple[int, str, str]:
    """Run a probe command; never raises so a hung tool becomes a failed row."""
    try:
        completed = subprocess.run(command_line, check=False, capture_output=True, text=True, timeout=timeout)
        return int(completed.returncode), completed.stdout or "", completed.stderr or ""
    except subprocess.TimeoutExpired:
        return INTERNAL_ERROR, "", f"timed out after {timeout:.0f}s"
    except OSError as exc:
        return INTERNAL_ERROR, "", str(exc)


def _last_line(text: str) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else ""


def _doctor_check_python() -> _doctor.CheckResult:
    info = sys.version_info
    version = f"{info.major}.{info.minor}.{info.micro}"
    implementation = getattr(sys.implementation, "name", "unknown")
    data: dict[str, object] = {"version": version, "implementation": implementation, "executable": sys.executable}
    minimum = ".".join(str(part) for part in _DOCTOR_MIN_PYTHON)
    if (info.major, info.minor) < _DOCTOR_MIN_PYTHON:
        return _doctor.CheckResult(
            "python",
            "Python",
            _doctor.FAIL,
            f"{version} at {sys.executable} is older than the required {minimum}",
            f"Install Python {minimum} or newer and install the CLI there: python3 -m pip install pyronaut",
            data,
        )
    return _doctor.CheckResult("python", "Python", _doctor.PASS, f"{version} ({implementation}) at {sys.executable}", data=data)


def _doctor_check_setup_state(args: Sequence[str]) -> _doctor.CheckResult:
    version = _installed_pyronaut_version()
    data: dict[str, object] = {"version": version}
    if not _setup_is_required():
        return _doctor.CheckResult(
            "pyronaut", "Pyronaut SDK", _doctor.PASS, f"{version} (source checkout, no setup state required)", data=data
        )
    manifest_path = _setup_manifest_path()
    data["manifest"] = str(manifest_path)
    try:
        manifest = _read_valid_setup_manifest(args)
    except (OSError, RuntimeError, ValueError) as exc:
        if str(exc) != _SETUP_REQUIRED_MESSAGE:
            return _doctor.CheckResult(
                "pyronaut", "Pyronaut SDK", _doctor.FAIL, f"{version}: {exc}",
                "Fix the reported setting in ~/.pyronaut/settings.toml and run pyronaut setup", data,
            )
        if manifest_path.is_file():
            recorded = None
            try:
                recorded = json.loads(manifest_path.read_text(encoding="utf-8")).get("sdkVersion")
            except (OSError, ValueError, TypeError, AttributeError):
                pass
            data["recordedVersion"] = recorded
            qualifier = f" (recorded for {recorded})" if recorded and recorded != version else ""
            return _doctor.CheckResult(
                "pyronaut", "Pyronaut SDK", _doctor.FAIL,
                f"{version}: cached setup state at {manifest_path} is stale{qualifier}",
                "Run pyronaut setup --refresh", data,
            )
        return _doctor.CheckResult(
            "pyronaut", "Pyronaut SDK", _doctor.FAIL, f"{version}: no setup state at {manifest_path}",
            "Run pyronaut setup", data,
        )
    data["javaHome"] = manifest.get("javaHome")
    data["toolRuntime"] = manifest.get("toolRuntime")
    return _doctor.CheckResult(
        "pyronaut", "Pyronaut SDK", _doctor.PASS, f"{version}: setup state valid at {manifest_path}", data=data
    )


def _describe_graalvm_home_source(java_home: Path) -> str:
    sdkman_dir = _read_env("SDKMAN_DIR") or str(Path.home() / ".sdkman")
    candidates: list[tuple[str, Path]] = []
    env_java_home = _read_env("JAVA_HOME")
    if env_java_home:
        candidates.append(("JAVA_HOME", Path(env_java_home)))
    candidates.extend(
        [
            ("~/.pyronaut/sdks", _graalvm_jdks_root()),
            ("~/.pyronaut/jdks", _legacy_graalvm_jdks_root()),
            ("SDKMAN", Path(sdkman_dir) / "candidates" / "java"),
            ("jenv", Path.home() / ".jenv" / "versions"),
            ("Gradle JDKs", Path.home() / ".gradle" / "jdks"),
        ]
    )
    for label, root in candidates:
        try:
            if java_home.resolve().is_relative_to(root.resolve()):
                return label
        except OSError:
            continue
    return "discovered"


def _doctor_check_graalvm(project_dir: Path | None) -> _doctor.CheckResult:
    try:
        toolchain = _read_pyproject_toolchain_spec(project_dir)
    except (ValueError, RuntimeError) as exc:
        return _doctor.CheckResult(
            "graalvm", "GraalVM JDK", _doctor.FAIL, str(exc), "Fix [tool.pyronaut.toolchain] in pyproject.toml"
        )
    required = toolchain.java_version
    data: dict[str, object] = {"requiredJavaVersion": required}
    env_java_home = _read_env("JAVA_HOME")
    ignored_java_home = bool(env_java_home) and not _matches_requested_graalvm_home(Path(env_java_home), toolchain)
    data["ignoredJavaHome"] = env_java_home if ignored_java_home else None

    java_home = _ensure_graalvm_java_home(project_dir, offline=True)
    if java_home is None:
        detail = f"no GraalVM JDK {required}+ found (checked JAVA_HOME, ~/.pyronaut/sdks, SDKMAN, jenv, Gradle JDKs)"
        if ignored_java_home:
            detail += f"; JAVA_HOME={env_java_home} is not a compatible GraalVM"
        return _doctor.CheckResult(
            "graalvm", "GraalVM JDK", _doctor.FAIL, detail,
            f"Run pyronaut setup to download GraalVM JDK {required}, or point JAVA_HOME at a GraalVM JDK {required}+ installation",
            data,
        )
    home = Path(java_home)
    metadata = _read_graalvm_metadata(home)
    source = _describe_graalvm_home_source(home)
    label = _GRAALVM_DISTRIBUTION_LABELS.get(metadata.distribution if metadata else None, "GraalVM")
    version = metadata.version if metadata and metadata.version else "unknown version"
    data.update(
        {
            "javaHome": str(home),
            "source": source,
            "distribution": metadata.distribution if metadata else None,
            "version": metadata.version if metadata else None,
            "javaVersion": metadata.java_version if metadata else None,
        }
    )
    detail = f"{label} {version} from {source} at {home}"
    if ignored_java_home:
        return _doctor.CheckResult(
            "graalvm", "GraalVM JDK", _doctor.WARN,
            f"{detail}; JAVA_HOME={env_java_home} is ignored because it is not a compatible GraalVM",
            f"Unset JAVA_HOME or point it at {home}", data,
        )
    return _doctor.CheckResult("graalvm", "GraalVM JDK", _doctor.PASS, detail, data=data)


def _pyenv_root() -> Path:
    configured = _read_env("PYENV_ROOT")
    return Path(configured) if configured else Path.home() / ".pyenv"


def _pyenv_selected_versions() -> tuple[list[str], str | None]:
    """Return the pyenv versions in effect and where they were selected.

    Mirrors pyenv's own precedence for the global selection: ``PYENV_VERSION``
    wins, otherwise the ``version`` file below the pyenv root (see issue #62).
    """
    configured = _read_env("PYENV_VERSION")
    if configured:
        return [name.strip() for name in configured.split(":") if name.strip()], "PYENV_VERSION"
    version_file = _pyenv_root() / "version"
    try:
        names = [line.strip() for line in version_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    except OSError:
        return [], None
    return names, str(version_file)


def _setup_recorded_graalpy() -> Path | None:
    """The GraalPy executable recorded by ``pyronaut setup``, if any."""
    try:
        manifest = json.loads(_setup_manifest_path().read_text(encoding="utf-8"))
    except (OSError, RuntimeError, TypeError, ValueError):
        return None
    graalpy = manifest.get("graalpy") if isinstance(manifest, dict) else None
    executable = graalpy.get("executable") if isinstance(graalpy, dict) else None
    return Path(executable) if isinstance(executable, str) and executable else None


def _find_global_graalpy() -> tuple[Path, str] | None:
    """Locate a GraalPy interpreter outside any project virtualenv."""
    recorded = _setup_recorded_graalpy()
    if recorded is not None:
        return recorded, "pyronaut setup"
    names, source = _pyenv_selected_versions()
    for name in names:
        if not name.lower().startswith("graalpy"):
            continue
        version_dir = _pyenv_root() / "versions" / name
        for executable in ("graalpy", "python"):
            candidate = version_dir / "bin" / executable
            if _is_executable_file(candidate):
                return candidate, f"pyenv version {name} selected by {source}"
        return version_dir / "bin" / "graalpy", f"pyenv version {name} selected by {source} (not installed)"
    discovered = shutil.which("graalpy")
    if discovered:
        return Path(discovered), "PATH"
    return None


def _virtualenv_is_graalpy(venv_python: Path) -> bool:
    """Whether a project ``.venv`` was created by GraalPy (see prerequisites)."""
    try:
        if "graalpy" in str(venv_python.resolve(strict=True)).lower():
            return True
    except OSError:
        return False
    config = venv_python.parent.parent / "pyvenv.cfg"
    try:
        return "graalpy" in config.read_text(encoding="utf-8").lower()
    except OSError:
        return False


def _probe_python_version(python: Path) -> str | None:
    code, out, err = _doctor_capture([str(python), "--version"])
    if code != 0:
        return None
    return _last_line(out) or _last_line(err) or None


def _graalpy_versions_match(version_line: str, expected: str | None, expected_pyenv: str | None) -> bool:
    if expected and expected in version_line:
        return True
    if expected_pyenv:
        runtime_version = expected_pyenv.rsplit("-", 1)[-1]
        return bool(runtime_version and runtime_version in version_line)
    return False


def _doctor_check_graalpy(project_dir: Path | None) -> _doctor.CheckResult:
    version_properties = _read_version_properties()
    expected = version_properties.get("graalpy")
    expected_pyenv = version_properties.get("graalpy.pyenv")
    data: dict[str, object] = {"expectedVersion": expected, "expectedPyenvVersion": expected_pyenv}
    suggested = expected_pyenv or (f"graalpy3.13-{expected}" if expected else "graalpy3.13-<version>")
    install_fix = (
        f"Run pyronaut setup to provision GraalPy {suggested}, "
        f"or install it with pyenv: pyenv install {suggested} && pyenv global {suggested}"
    )
    venv_fix = "Run pyronaut install to create .venv with GraalPy and install the project's Python dependencies"

    if project_dir is not None:
        venv_dir = project_dir / ".venv"
        if venv_dir.is_dir():
            venv_python = _resolve_virtualenv_python(_virtualenv_bin(venv_dir))
            data["virtualenv"] = str(venv_dir)
            if venv_python is None:
                return _doctor.CheckResult(
                    "graalpy", "GraalPy", _doctor.FAIL, f"{venv_dir} has no Python interpreter (broken virtualenv)",
                    f"Remove {venv_dir} and run pyronaut install", data,
                )
            if not _virtualenv_is_graalpy(venv_python):
                return _doctor.CheckResult(
                    "graalpy", "GraalPy", _doctor.FAIL,
                    f"{venv_dir} was not created with GraalPy, so its packages cannot be used by the embedded runtime",
                    f"Remove {venv_dir} and run pyronaut install", data,
                )
            version_line = _probe_python_version(venv_python)
            data["executable"] = str(venv_python)
            data["version"] = version_line
            if version_line is None:
                return _doctor.CheckResult(
                    "graalpy", "GraalPy", _doctor.FAIL, f"{venv_python} does not start",
                    f"Remove {venv_dir} and run pyronaut install", data,
                )
            if expected and not _graalpy_versions_match(version_line, expected, expected_pyenv):
                return _doctor.CheckResult(
                    "graalpy", "GraalPy", _doctor.WARN,
                    f"project .venv uses {version_line} but this Pyronaut bundles GraalPy {expected}",
                    f"Recreate .venv with GraalPy {expected} ({install_fix})", data,
                )
            return _doctor.CheckResult(
                "graalpy", "GraalPy", _doctor.PASS, f"project .venv uses {version_line} ({venv_python})", data=data
            )

    found = _find_global_graalpy()
    if found is None:
        detail = "no GraalPy found on PATH, PYENV_VERSION or ~/.pyenv/version"
        if project_dir is not None:
            return _doctor.CheckResult(
                "graalpy", "GraalPy", _doctor.FAIL, f"no project .venv and {detail}", f"{install_fix}; then {venv_fix}", data
            )
        return _doctor.CheckResult(
            "graalpy", "GraalPy", _doctor.WARN,
            f"{detail} (needed to create project virtualenvs and run pytest)", install_fix, data,
        )
    executable, source = found
    data["executable"] = str(executable)
    data["source"] = source
    if not _is_executable_file(executable):
        return _doctor.CheckResult(
            "graalpy", "GraalPy", _doctor.FAIL, f"{source}: {executable} is missing", install_fix, data
        )
    version_line = _probe_python_version(executable)
    data["version"] = version_line
    if version_line is None:
        return _doctor.CheckResult(
            "graalpy", "GraalPy", _doctor.FAIL, f"{executable} ({source}) does not start", install_fix, data
        )
    detail = f"{version_line} via {source} ({executable})"
    if project_dir is not None:
        return _doctor.CheckResult(
            "graalpy", "GraalPy", _doctor.WARN, f"no project .venv; {detail}", venv_fix, data
        )
    if expected and not _graalpy_versions_match(version_line, expected, expected_pyenv):
        return _doctor.CheckResult(
            "graalpy", "GraalPy", _doctor.WARN, f"{detail}; this Pyronaut bundles GraalPy {expected}", install_fix, data
        )
    return _doctor.CheckResult("graalpy", "GraalPy", _doctor.PASS, detail, data=data)


def _doctor_check_native_launchers() -> _doctor.CheckResult:
    try:
        base_url, version, release_tag = _native_image_configuration()
        os_segment, arch = _native_image_platform()
    except RuntimeError as exc:
        message = str(exc)
        fix = (
            "Use macOS or Linux on x86_64 or arm64"
            if "not available for" in message
            else "Fix [native-images] in ~/.pyronaut/settings.toml"
        )
        return _doctor.CheckResult("launchers", "Native launchers", _doctor.FAIL, message, fix)
    cache_dir = _native_image_cache_path("x", version=version, os_segment=os_segment, arch=arch).parent
    images: dict[str, str | None] = {}
    missing: dict[str, str] = {}
    for image_name in _SETUP_IMAGE_COMMANDS:
        try:
            images[image_name] = str(_cached_native_image(image_name))
        except RuntimeError as exc:
            missing[image_name] = str(exc)
            images[image_name] = None
    data: dict[str, object] = {
        "source": base_url,
        "version": version,
        "releaseTag": release_tag,
        "platform": f"{os_segment}-{arch}",
        "directory": str(cache_dir),
        "images": images,
    }
    if not missing:
        return _doctor.CheckResult(
            "launchers", "Native launchers", _doctor.PASS,
            f"{', '.join(_SETUP_IMAGE_COMMANDS)} cached in {cache_dir}", data=data,
        )
    stale = any("not cached" not in reason for reason in missing.values())
    status = _doctor.FAIL if _setup_is_required() else _doctor.WARN
    return _doctor.CheckResult(
        "launchers", "Native launchers", status,
        f"{len(missing)} of {len(_SETUP_IMAGE_COMMANDS)} launchers {'stale' if stale else 'missing'} in {cache_dir}: "
        + ", ".join(sorted(missing)),
        "Run pyronaut setup --refresh" if stale else "Run pyronaut setup",
        data,
    )


def _doctor_check_pyproject(project_dir: Path) -> _doctor.CheckResult:
    path = project_dir / "pyproject.toml"
    data: dict[str, object] = {"path": str(path)}
    if not path.is_file():
        return _doctor.CheckResult(
            "pyproject", "pyproject.toml", _doctor.FAIL, f"no pyproject.toml in {project_dir}",
            "Run from a Pyronaut project directory, pass --project-dir, or create one with pyronaut create <name>", data,
        )
    try:
        import tomllib
    except ImportError:  # pragma: no cover - Python 3.10 without tomli
        try:
            import tomli as tomllib  # type: ignore[no-redef]
        except ImportError:
            return _doctor.CheckResult(
                "pyproject", "pyproject.toml", _doctor.FAIL, "no TOML parser available on this Python",
                "Use Python 3.11+ or install tomli", data,
            )
    try:
        with path.open("rb") as handle:
            parsed = tomllib.load(handle)
    except (OSError, ValueError) as exc:
        return _doctor.CheckResult(
            "pyproject", "pyproject.toml", _doctor.FAIL, f"{path} does not parse: {exc}",
            "Fix the TOML syntax error reported above", data,
        )
    if not isinstance(parsed, dict):
        return _doctor.CheckResult("pyproject", "pyproject.toml", _doctor.FAIL, f"{path} is not a TOML table", None, data)
    try:
        _read_pyproject_toolchain_spec(project_dir)
        _read_pyproject_sources(project_dir)
        _read_pyproject_packaging_format(project_dir)
    except (RuntimeError, ValueError, TypeError) as exc:
        return _doctor.CheckResult(
            "pyproject", "pyproject.toml", _doctor.FAIL, f"{path}: {exc}", "Fix the [tool.pyronaut] setting reported above", data
        )
    name, version = _read_pyproject_project_metadata(project_dir)
    tool = parsed.get("tool")
    has_table = isinstance(tool, dict) and isinstance(tool.get("pyronaut"), dict)
    data.update({"name": name, "version": version, "pyronautTable": has_table})
    detail = f"{name} {version} parses ({path})"
    if not has_table:
        detail += "; no [tool.pyronaut] table, defaults apply"
    return _doctor.CheckResult("pyproject", "pyproject.toml", _doctor.PASS, detail, data=data)


def _doctor_check_install_manifests(project_dir: Path) -> _doctor.CheckResult:
    required = _required_install_manifests(project_dir)
    cache_dir = _pyronaut_output_dir(project_dir)
    data: dict[str, object] = {"directory": str(cache_dir), "manifests": [str(path) for path in required]}
    if _install_required(project_dir):
        missing = [path.name for path in required if not path.exists()]
        data["missing"] = missing
        return _doctor.CheckResult(
            "install", "Classpath manifests", _doctor.FAIL,
            f"{len(missing)} of {len(required)} missing in {cache_dir}: {', '.join(missing)}",
            "Run pyronaut install", data,
        )
    return _doctor.CheckResult(
        "install", "Classpath manifests", _doctor.PASS,
        f"{', '.join(path.name for path in required)} present in {cache_dir}", data=data,
    )


def _doctor_check_pytest(project_dir: Path) -> _doctor.CheckResult:
    venv_dir = project_dir / ".venv"
    venv_bin = _virtualenv_bin(venv_dir)
    venv_python = _resolve_virtualenv_python(venv_bin) if venv_bin.is_dir() else None
    data: dict[str, object] = {"virtualenv": str(venv_dir) if venv_dir.is_dir() else None}
    if venv_python is None:
        return _doctor.CheckResult(
            "pytest", "pytest", _doctor.FAIL, f"no project virtualenv at {venv_dir} to import pytest from",
            "Run pyronaut install to create .venv with GraalPy and install pytest", data,
        )
    data["executable"] = str(venv_python)
    code, out, err = _doctor_capture([str(venv_python), "-c", "import pytest; print(pytest.__version__)"])
    if code != 0 or not out.strip():
        reason = _last_line(err)
        return _doctor.CheckResult(
            "pytest", "pytest", _doctor.FAIL,
            f"not importable from {venv_python}" + (f": {reason}" if reason else ""),
            f"{venv_python} -m pip install pytest", data,
        )
    version = out.strip()
    data["version"] = version
    return _doctor.CheckResult("pytest", "pytest", _doctor.PASS, f"{version} importable from {venv_python}", data=data)


def _redact_proxy_url(value: str) -> str:
    try:
        parts = urllib.parse.urlsplit(value)
    except ValueError:
        return value
    if parts.username is None and parts.password is None:
        return value
    host = parts.hostname or ""
    if parts.port is not None:
        host += f":{parts.port}"
    return urllib.parse.urlunsplit((parts.scheme, f"***@{host}", parts.path, parts.query, parts.fragment))


def _doctor_check_proxy() -> _doctor.CheckResult:
    environment = os.environ
    env_proxy = {name: environment[name] for name in (*_DOCTOR_PROXY_ENV, *_DOCTOR_NO_PROXY_ENV) if environment.get(name)}
    data: dict[str, object] = {"environment": sorted(env_proxy)}
    try:
        settings = _read_pyronaut_user_settings()
    except RuntimeError as exc:
        return _doctor.CheckResult(
            "proxy", "Proxy", _doctor.FAIL, str(exc), "Fix the TOML syntax in ~/.pyronaut/settings.toml", data
        )
    configured = settings.get("proxy")
    if configured is not None and not isinstance(configured, dict):
        return _doctor.CheckResult(
            "proxy", "Proxy", _doctor.FAIL, "[proxy] in ~/.pyronaut/settings.toml must be a table",
            "Declare the proxy as [proxy] with url, or host and port", data,
        )
    settings_proxy = False
    warnings: list[str] = []
    if isinstance(configured, dict):
        url = configured.get("url")
        host = configured.get("host")
        settings_proxy = (isinstance(url, str) and bool(url.strip())) or (
            isinstance(host, str) and bool(host.strip()) and configured.get("port") is not None
        )
        if not settings_proxy:
            warnings.append("[proxy] in ~/.pyronaut/settings.toml is ignored because it sets neither url nor host and port")
    for upper, lower in (("HTTPS_PROXY", "https_proxy"), ("HTTP_PROXY", "http_proxy"), ("NO_PROXY", "no_proxy")):
        if env_proxy.get(upper) and env_proxy.get(lower) and env_proxy[upper] != env_proxy[lower]:
            warnings.append(f"{upper} and {lower} differ ({upper} wins)")

    proxy, bypass = _download_proxy(_NATIVE_IMAGE_BASE_URL)
    data["bypass"] = bypass
    if proxy is None:
        data["proxy"] = None
        data["source"] = None
        if warnings:
            return _doctor.CheckResult(
                "proxy", "Proxy", _doctor.WARN, "no proxy in effect; " + "; ".join(warnings),
                "Set [proxy].url (or host and port) in ~/.pyronaut/settings.toml, or remove the table", data,
            )
        return _doctor.CheckResult("proxy", "Proxy", _doctor.PASS, "no proxy configured", data=data)

    if any(env_proxy.get(name) for name in _DOCTOR_PROXY_ENV):
        winner = next(name for name in _DOCTOR_PROXY_ENV if env_proxy.get(name))
        source = f"environment ({winner})"
    elif settings_proxy:
        source = "~/.pyronaut/settings.toml"
    else:
        source = "~/.m2/settings.xml"
    redacted = _redact_proxy_url(proxy)
    data["proxy"] = redacted
    data["source"] = source
    try:
        parts = urllib.parse.urlsplit(proxy)
        port = parts.port
    except ValueError as exc:
        return _doctor.CheckResult(
            "proxy", "Proxy", _doctor.FAIL, f"{redacted} from {source} is malformed: {exc}",
            "Use the form http://[user:password@]host:port", data,
        )
    if parts.scheme not in {"http", "https"} or not parts.hostname or port is None:
        return _doctor.CheckResult(
            "proxy", "Proxy", _doctor.FAIL, f"{redacted} from {source} is not an http(s)://host:port URL",
            "Use the form http://[user:password@]host:port", data,
        )
    detail = f"{redacted} from {source}"
    if bypass:
        detail += f", bypass {bypass}"
    if source.startswith("environment") and settings_proxy:
        detail += "; overrides [proxy] in ~/.pyronaut/settings.toml"
    if warnings:
        return _doctor.CheckResult(
            "proxy", "Proxy", _doctor.WARN, detail + "; " + "; ".join(warnings),
            "Make the proxy environment variables agree, or unset the unused variant", data,
        )
    return _doctor.CheckResult("proxy", "Proxy", _doctor.PASS, detail, data=data)


def _doctor_check_docker() -> _doctor.CheckResult:
    docker = shutil.which("docker")
    data: dict[str, object] = {"executable": docker, "dockerHost": _read_env("DOCKER_HOST")}
    only_needed = "needed only for test resources and pyronaut build --docker"
    if docker is None:
        return _doctor.CheckResult(
            "docker", "Docker", _doctor.WARN, f"docker CLI not found on PATH ({only_needed})",
            "Install Docker Desktop or Docker Engine, or set PYRONAUT_TEST_RESOURCES_DISABLED=true if no test resources are used",
            data,
        )
    code, out, err = _doctor_capture([docker, "version", "--format", "{{.Server.Version}}"])
    server = out.strip()
    if code != 0 or not server:
        reason = _last_line(err)
        return _doctor.CheckResult(
            "docker", "Docker", _doctor.WARN,
            f"daemon not reachable via {docker} ({only_needed})" + (f": {reason}" if reason else ""),
            "Start Docker Desktop (macOS) or the docker service (Linux); set DOCKER_HOST if the runtime uses a custom socket",
            data,
        )
    data["serverVersion"] = server
    return _doctor.CheckResult("docker", "Docker", _doctor.PASS, f"daemon reachable, server {server} ({docker})", data=data)


# -- pyronaut doctor: project runtime diagnostics ---------------------------
#
# These checks look at the project the way the run/test commands do: the
# Python packages come from the project ``.venv`` (never from ``pyronaut
# install``), threading comes from ``micronaut.python.pool`` and
# ``micronaut.executors`` in the application configuration, and the
# deployment mode from ``[tool.pyronaut.packaging]``/``[tool.pyronaut.toolchain]``.

_DOCTOR_PROBE_TIMEOUT = 180.0
_DOCTOR_MAX_PROBE_THREADS = 16
_GRAALPY_COMPATIBILITY_URL = "https://graalpy.org/module_results/python-module-testing-{release}.csv"
_GRAALPY_COMPATIBILITY_PAGE = "https://graalpy.org/python-developers/compatibility/"
_GRAALPY_COMPATIBILITY_MAX_AGE = 7 * 24 * 60 * 60
_GRAALPY_COMPATIBLE_PERCENT = 90.0
_NATIVE_PACKAGING_FORMATS = {"wheel-native", "wheel-crema", "docker-native", "docker-crema"}
_PYTHON_EXECUTORS = ("io", "blocking")
_NATIVE_EXTENSION_SUFFIXES = (".so", ".pyd", ".dylib")

# Runs inside the project's GraalPy virtualenv. It resolves every declared
# distribution, derives its importable modules, imports them concurrently
# with as many threads as the context pool would use, and prints one JSON
# document so the CLI never has to parse tracebacks.
_DOCTOR_PACKAGE_PROBE = r'''
import importlib, json, re, sys, threading, traceback
import importlib.metadata as metadata

spec = json.loads(sys.argv[1])
threads = max(1, int(spec.get("threads", 1)))
NATIVE = (".so", ".pyd", ".dylib")


def canonical(name):
    return re.sub(r"[-_.]+", "-", name).lower()


distributions = {}
for distribution in metadata.distributions():
    name = distribution.metadata["Name"] if distribution.metadata else None
    if name:
        distributions.setdefault(canonical(name), distribution)


def modules_of(distribution):
    files = [str(path) for path in (distribution.files or [])]
    top_level = distribution.read_text("top_level.txt") or ""
    modules = [line.strip() for line in top_level.splitlines() if line.strip()]
    if not modules:
        found = set()
        for path in files:
            head = path.split("/", 1)[0]
            if head.startswith(("..", "__pycache__")) or head.endswith((".dist-info", ".data", ".pth")):
                continue
            if "/" in path:
                found.add(head)
            elif path.endswith(".py"):
                found.add(path[:-3])
            elif path.endswith(NATIVE):
                found.add(path.split(".", 1)[0])
        modules = sorted(found)
    public = [module for module in modules if module.isidentifier() and not module.startswith("_")]
    return (public or [module for module in modules if module.isidentifier()]), files


results = {}
for requested in spec["packages"]:
    distribution = distributions.get(canonical(requested))
    if distribution is None:
        results[requested] = {"installed": False}
        continue
    modules, files = modules_of(distribution)
    results[requested] = {
        "installed": True,
        "version": distribution.version,
        "modules": modules,
        "native": any(path.endswith(NATIVE) for path in files),
        "errors": {},
    }


def import_all(requested, errors, lock):
    for module in results[requested]["modules"]:
        try:
            importlib.import_module(module)
        except BaseException as exc:  # noqa: BLE001 - report, never crash the probe
            with lock:
                errors.setdefault(module, "".join(traceback.format_exception_only(type(exc), exc)).strip().splitlines()[-1])


for requested, result in results.items():
    if not result["installed"]:
        continue
    lock = threading.Lock()
    workers = [threading.Thread(target=import_all, args=(requested, result["errors"], lock), daemon=True) for _ in range(threads)]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    import_all(requested, result["errors"], lock)

print(json.dumps({"python": sys.version.splitlines()[0], "threads": threads, "packages": results}))
'''


class _DoctorRuntimeConfig(NamedTuple):
    packaging_format: str
    toolchain_type: str
    native: bool
    config_file: Path | None
    pool_enabled: bool
    pool_size: int | None
    probe_threads: int
    executors: dict[str, dict[str, object]]
    problems: tuple[str, ...]
    unparsed: tuple[str, ...]


def _canonical_package_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _read_pyproject_python_dependencies(project_dir: Path) -> list[str]:
    """Return the distribution names declared in ``[project].dependencies``.

    Requirements guarded by an ``extra`` marker are optional and skipped.
    """
    data = _read_pyproject_data(project_dir)
    project = data.get("project") if isinstance(data, dict) else None
    declared = project.get("dependencies") if isinstance(project, dict) else None
    if not isinstance(declared, list):
        return []
    names: list[str] = []
    for requirement in declared:
        if not isinstance(requirement, str):
            continue
        match = re.match(r"\s*([A-Za-z0-9][A-Za-z0-9._-]*)", requirement)
        if match is None:
            continue
        marker = requirement.split(";", 1)[1] if ";" in requirement else ""
        if re.search(r"\bextra\b", marker):
            continue
        name = match.group(1)
        if _canonical_package_name(name) not in {_canonical_package_name(seen) for seen in names}:
            names.append(name)
    return names


def _config_value(data: dict[str, object], *keys: str) -> object:
    """Walk nested tables; TOML gives nested dicts, properties give dotted keys."""
    current: object = data
    for index, key in enumerate(keys):
        if not isinstance(current, dict):
            return None
        if key in current:
            current = current[key]
            continue
        dotted = ".".join(keys[index:])
        if dotted in current:
            return current[dotted]
        return None
    return current


def _properties_to_nested(values: dict[str, str]) -> dict[str, object]:
    nested: dict[str, object] = {}
    for key, raw in values.items():
        value: object = raw
        lowered = raw.strip().lower()
        if lowered in {"true", "false"}:
            value = lowered == "true"
        elif re.fullmatch(r"-?\d+", raw.strip()):
            value = int(raw.strip())
        target = nested
        parts = key.split(".")
        for part in parts[:-1]:
            child = target.get(part)
            if not isinstance(child, dict):
                child = {}
                target[part] = child
            target = child
        target[parts[-1]] = value
    return nested


def _read_application_config(project_dir: Path) -> tuple[dict[str, object], Path | None, list[str]]:
    """Return the parsed ``application.toml``/``.properties`` plus unparsed files."""
    try:
        layout = _read_pyproject_sources(project_dir)
        resources_dir = _resolve_layout_dir(project_dir, layout.resources_dir)
    except (RuntimeError, ValueError, TypeError):
        resources_dir = project_dir / _DEFAULT_RESOURCES_DIR
    unparsed: list[str] = []
    toml_file = resources_dir / "application.toml"
    if toml_file.is_file():
        try:
            import tomllib
        except ImportError:  # pragma: no cover - Python 3.10 without tomli
            import tomli as tomllib  # type: ignore[no-redef]
        with toml_file.open("rb") as handle:
            data = tomllib.load(handle)
        return (data if isinstance(data, dict) else {}), toml_file, unparsed
    properties_file = resources_dir / "application.properties"
    if properties_file.is_file():
        return _properties_to_nested(_parse_properties_file(properties_file)), properties_file, unparsed
    for name in ("application.yml", "application.yaml", "application.json"):
        if (resources_dir / name).is_file():
            unparsed.append(str(resources_dir / name))
    return {}, None, unparsed


def _doctor_runtime_config(project_dir: Path) -> _DoctorRuntimeConfig:
    problems: list[str] = []
    packaging_format = DEFAULT_PACKAGING_FORMAT
    toolchain_type = TOOLCHAIN_TYPE_JVM
    try:
        packaging_format = _read_pyproject_packaging_format(project_dir)
    except (ValueError, RuntimeError, TypeError) as exc:
        problems.append(str(exc))
    try:
        toolchain_type = _read_pyproject_toolchain_type(project_dir)
    except (ValueError, RuntimeError, TypeError) as exc:
        problems.append(str(exc))
    native = packaging_format in _NATIVE_PACKAGING_FORMATS or toolchain_type == TOOLCHAIN_TYPE_NATIVE

    config: dict[str, object] = {}
    config_file: Path | None = None
    unparsed: list[str] = []
    try:
        config, config_file, unparsed = _read_application_config(project_dir)
    except (OSError, ValueError, TypeError) as exc:
        problems.append(f"application configuration does not parse: {exc}")

    pool_enabled = True
    enabled_raw = _config_value(config, "micronaut", "python", "pool", "enabled")
    if enabled_raw is not None:
        if isinstance(enabled_raw, bool):
            pool_enabled = enabled_raw
        else:
            problems.append("micronaut.python.pool.enabled must be true or false")
    pool_size: int | None = None
    size_raw = _config_value(config, "micronaut", "python", "pool", "size")
    if size_raw is not None:
        if isinstance(size_raw, bool) or not isinstance(size_raw, int) or size_raw < 0:
            problems.append("micronaut.python.pool.size must be a non-negative integer (0 selects the default)")
        else:
            pool_size = size_raw

    executors: dict[str, dict[str, object]] = {}
    executors_raw = _config_value(config, "micronaut", "executors")
    if isinstance(executors_raw, dict):
        for name, settings in executors_raw.items():
            if isinstance(settings, dict):
                executors[str(name)] = dict(settings)

    if not pool_enabled:
        probe_threads = 1
    elif pool_size:
        probe_threads = pool_size
    else:
        probe_threads = 2 * (os.cpu_count() or 1)
    probe_threads = max(1, min(_DOCTOR_MAX_PROBE_THREADS, probe_threads))
    return _DoctorRuntimeConfig(
        packaging_format,
        toolchain_type,
        native,
        config_file,
        pool_enabled,
        pool_size,
        probe_threads,
        executors,
        tuple(problems),
        tuple(unparsed),
    )


def _deployment_label(config: _DoctorRuntimeConfig) -> str:
    mode = "native" if config.native else "JVM"
    return f"{config.packaging_format} packaging, {mode} toolchain"


def _doctor_check_threading(project_dir: Path, config: _DoctorRuntimeConfig) -> _doctor.CheckResult:
    data: dict[str, object] = {
        "configFile": str(config.config_file) if config.config_file else None,
        "poolEnabled": config.pool_enabled,
        "poolSize": config.pool_size,
        "probeThreads": config.probe_threads,
        "executors": config.executors,
        "packagingFormat": config.packaging_format,
        "toolchainType": config.toolchain_type,
    }
    config_problems = [problem for problem in config.problems if "pyproject" not in problem.lower()]
    if config_problems:
        return _doctor.CheckResult(
            "threading", "Threading", _doctor.FAIL, "; ".join(config_problems),
            f"Fix the setting in {config.config_file or 'the application configuration'}", data,
        )
    virtual = sorted(
        name for name, settings in config.executors.items()
        if settings.get("virtual") is True and (name in _PYTHON_EXECUTORS or settings.get("type") is not None)
    )
    if virtual:
        return _doctor.CheckResult(
            "threading", "Threading", _doctor.FAIL,
            f"executor{'s' if len(virtual) != 1 else ''} {', '.join(virtual)} set virtual = true; GraalPy cannot run Python code on virtual threads",
            f"Set virtual = false under [micronaut.executors.<name>] in {config.config_file}", data,
        )
    if config.pool_enabled:
        size = f"size {config.pool_size}" if config.pool_size else "default size (2 x CPUs)"
        detail = f"context pool enabled, {size}"
    else:
        detail = "context pool disabled (single GraalPy context)"
    overrides = [
        f"{name}: {settings.get('type', 'cached')}" + (f", {settings['n-threads']} threads" if "n-threads" in settings else "")
        for name, settings in sorted(config.executors.items())
    ]
    detail += "; executors " + ("Pyronaut defaults (cached platform threads)" if not overrides else ", ".join(overrides))
    detail += f"; {_deployment_label(config)}"
    if config.config_file is None:
        detail += "; no application.toml/properties found"
    if config.unparsed:
        return _doctor.CheckResult(
            "threading", "Threading", _doctor.WARN,
            detail + f"; {', '.join(Path(path).name for path in config.unparsed)} not inspected (only TOML and properties are parsed)",
            "Move threading settings to config/application.toml so pyronaut doctor and pyronaut validate-config can verify them", data,
        )
    return _doctor.CheckResult("threading", "Threading", _doctor.PASS, detail, data=data)


def _virtualenv_base_home(venv_dir: Path) -> Path | None:
    config = venv_dir / "pyvenv.cfg"
    try:
        for line in config.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "home" and value.strip():
                return Path(value.strip())
    except OSError:
        return None
    return None


def _doctor_check_interpreter(project_dir: Path | None) -> _doctor.CheckResult:
    data: dict[str, object] = {}
    problems: list[tuple[str, str, str]] = []  # (status, detail, fix)

    override = _read_env("PYRONAUT_PYTHON_EXECUTABLE")
    data["pythonExecutableOverride"] = override
    if override:
        override_path = Path(override)
        if not _is_executable_file(override_path):
            problems.append((_doctor.FAIL, f"PYRONAUT_PYTHON_EXECUTABLE={override} is not an executable file", "Unset PYRONAUT_PYTHON_EXECUTABLE or point it at a GraalPy interpreter"))
        elif "graalpy" not in str(override_path.resolve()).lower():
            problems.append((_doctor.WARN, f"PYRONAUT_PYTHON_EXECUTABLE={override} is not a GraalPy interpreter; its packages cannot be used by the embedded runtime", "Unset PYRONAUT_PYTHON_EXECUTABLE or point it at a GraalPy interpreter"))

    venv_dir = project_dir / ".venv" if project_dir is not None else None
    active = _read_env("VIRTUAL_ENV")
    data["activeVirtualenv"] = active
    if active and venv_dir is not None and venv_dir.is_dir():
        try:
            same = Path(active).resolve() == venv_dir.resolve()
        except OSError:
            same = False
        if not same:
            problems.append((_doctor.WARN, f"activated virtualenv {active} is not the project .venv; Pyronaut commands use {venv_dir}", f"Deactivate the current virtualenv; Pyronaut automatically uses {venv_dir}"))

    base_home: Path | None = None
    if venv_dir is not None and venv_dir.is_dir():
        base_home = _virtualenv_base_home(venv_dir)
        data["virtualenvBase"] = str(base_home) if base_home else None
        if base_home is None:
            problems.append((_doctor.WARN, f"{venv_dir}/pyvenv.cfg has no home entry", "Remove .venv and run pyronaut install"))
        elif not base_home.is_dir():
            problems.append((_doctor.FAIL, f"{venv_dir} was created from {base_home}, which no longer exists (the base interpreter was removed)", "Remove .venv and run pyronaut install"))
        else:
            selected = _find_global_graalpy()
            if selected is not None and _is_executable_file(selected[0]):
                selected_home = selected[0].resolve().parent
                data["selectedGraalPy"] = str(selected[0])
                try:
                    matches = base_home.resolve() == selected_home
                except OSError:
                    matches = False
                if not matches:
                    problems.append((_doctor.WARN, f"{venv_dir} was created from {base_home} but the selected GraalPy is {selected[0]} ({selected[1]})", "Recreate .venv with the selected GraalPy, or select the version that created it (pyenv global <version>)"))

    if problems:
        status = _doctor.FAIL if any(status == _doctor.FAIL for status, _, _ in problems) else _doctor.WARN
        return _doctor.CheckResult("interpreter", "Interpreter", status, "; ".join(detail for _, detail, _ in problems), problems[0][2], data)
    if base_home is not None:
        return _doctor.CheckResult("interpreter", "Interpreter", _doctor.PASS, f"project .venv is based on {base_home} and matches the selected GraalPy; no overrides", data=data)
    return _doctor.CheckResult("interpreter", "Interpreter", _doctor.PASS, "no PYRONAUT_PYTHON_EXECUTABLE or VIRTUAL_ENV overrides", data=data)


def _newest_mtime(root: Path) -> float | None:
    newest: float | None = None
    if root.is_file():
        return root.stat().st_mtime
    for current, dirs, files in os.walk(root):
        for file_name in files:
            try:
                mtime = (Path(current) / file_name).stat().st_mtime
            except OSError:
                continue
            if newest is None or mtime > newest:
                newest = mtime
    return newest


def _doctor_check_generated_state(project_dir: Path) -> _doctor.CheckResult:
    output_dir = _pyronaut_output_dir(project_dir)
    manifests = [path for path in _required_install_manifests(project_dir) if path.is_file()]
    data: dict[str, object] = {"directory": str(output_dir), "staleManifests": {}, "pyprojectNewerThanInstall": False, "sourcesNewerThanClasses": False}
    if not manifests:
        return _doctor.CheckResult("state", "Generated state", _doctor.PASS, f"nothing generated in {output_dir} yet", data=data)

    stale: dict[str, int] = {}
    for manifest in manifests:
        if manifest.name.startswith("resolved-"):
            try:
                entries = _read_manifest_entries(manifest)
            except (OSError, RuntimeError):
                continue
            missing = sum(1 for entry in entries if not Path(entry).exists())
            if missing:
                stale[manifest.name] = missing
    data["staleManifests"] = stale
    if stale:
        summary = ", ".join(f"{name} ({count} missing)" for name, count in sorted(stale.items()))
        return _doctor.CheckResult(
            "state", "Generated state", _doctor.FAIL,
            f"classpath manifests reference files that no longer exist: {summary} (Maven repository changed since the last install)",
            "Run pyronaut install", data,
        )

    pyproject = project_dir / "pyproject.toml"
    try:
        oldest_manifest = min(path.stat().st_mtime for path in manifests)
        pyproject_newer = pyproject.is_file() and pyproject.stat().st_mtime > oldest_manifest
    except OSError:
        pyproject_newer = False
    data["pyprojectNewerThanInstall"] = pyproject_newer
    if pyproject_newer:
        return _doctor.CheckResult(
            "state", "Generated state", _doctor.WARN,
            "pyproject.toml changed after the last pyronaut install; the resolved classpath may not match its dependencies",
            "Run pyronaut install", data,
        )

    classes_dir = output_dir / "classes"
    detail = f"install manifests in {output_dir} are current"
    if classes_dir.is_dir():
        try:
            newest_source = max((mtime for _, mtime, _ in _snapshot_watched_files(project_dir)), default=None)
        except (RuntimeError, ValueError, TypeError, OSError):
            newest_source = None
        newest_class = _newest_mtime(classes_dir)
        if newest_source is not None and newest_class is not None and newest_source / 1e9 > newest_class:
            data["sourcesNewerThanClasses"] = True
            detail += "; sources changed after the last pyronaut process (dev, run and test reprocess automatically)"
        else:
            detail += "; processed classes are current"
    else:
        detail += "; not processed yet (dev, run and test process automatically)"
    return _doctor.CheckResult("state", "Generated state", _doctor.PASS, detail, data=data)


def _doctor_probe_packages(venv_python: Path, packages: Sequence[str], threads: int) -> tuple[dict[str, object] | None, str]:
    request = json.dumps({"packages": list(packages), "threads": threads})
    code, out, err = _doctor_capture([str(venv_python), "-c", _DOCTOR_PACKAGE_PROBE, request], timeout=_DOCTOR_PROBE_TIMEOUT)
    if code != 0:
        return None, _last_line(err) or f"exit code {code}"
    for line in reversed(out.splitlines()):
        if line.startswith("{"):
            try:
                parsed = json.loads(line)
            except ValueError:
                break
            if isinstance(parsed, dict):
                return parsed, ""
    return None, "probe produced no JSON report"


def _doctor_check_packages(project_dir: Path, config: _DoctorRuntimeConfig) -> _doctor.CheckResult:
    packages = _read_pyproject_python_dependencies(project_dir)
    data: dict[str, object] = {"declared": packages, "threads": config.probe_threads, "deployment": _deployment_label(config), "packages": {}}
    if not packages:
        return _doctor.CheckResult("packages", "Python packages", _doctor.PASS, "no [project].dependencies declared in pyproject.toml", data=data)
    venv_dir = project_dir / ".venv"
    venv_bin = _virtualenv_bin(venv_dir)
    venv_python = _resolve_virtualenv_python(venv_bin) if venv_bin.is_dir() else None
    if venv_python is None:
        return _doctor.CheckResult(
            "packages", "Python packages", _doctor.FAIL,
            f"{len(packages)} declared package{'s' if len(packages) != 1 else ''} but no project virtualenv at {venv_dir}",
            "Run pyronaut install to create .venv with GraalPy and install the project's Python dependencies", data,
        )
    data["executable"] = str(venv_python)
    report, error = _doctor_probe_packages(venv_python, packages, config.probe_threads)
    if report is None:
        return _doctor.CheckResult(
            "packages", "Python packages", _doctor.FAIL, f"package probe failed in {venv_python}: {error}",
            "Remove .venv and run pyronaut install", data,
        )
    results = report.get("packages") if isinstance(report.get("packages"), dict) else {}
    data["packages"] = results
    missing = [name for name in packages if not (isinstance(results.get(name), dict) and results[name].get("installed"))]
    broken: dict[str, str] = {}
    native: list[str] = []
    for name in packages:
        result = results.get(name)
        if not isinstance(result, dict) or not result.get("installed"):
            continue
        errors = result.get("errors") if isinstance(result.get("errors"), dict) else {}
        if errors:
            module, message = next(iter(errors.items()))
            broken[name] = f"{module}: {message}"
        if result.get("native"):
            native.append(name)
    threads_note = f"{config.probe_threads} concurrent thread{'s' if config.probe_threads != 1 else ''}"
    if missing:
        return _doctor.CheckResult(
            "packages", "Python packages", _doctor.FAIL,
            f"{len(missing)} of {len(packages)} declared packages not installed in {venv_dir}: {', '.join(missing)}",
            f"{venv_python} -m pip install {' '.join(shlex.quote(name) for name in missing)}", data,
        )
    if broken:
        summary = "; ".join(f"{name} ({message})" for name, message in broken.items())
        return _doctor.CheckResult(
            "packages", "Python packages", _doctor.FAIL,
            f"{len(broken)} of {len(packages)} packages fail to import under {threads_note}: {summary}",
            f"Reinstall the package with GraalPy ({venv_python} -m pip install --force-reinstall <name>) and check {_GRAALPY_COMPATIBILITY_PAGE}", data,
        )
    detail = f"{len(packages)} declared package{'s' if len(packages) != 1 else ''} import cleanly under {threads_note} ({_deployment_label(config)})"
    if native:
        mode = "the native image" if config.native else "GraalPy"
        return _doctor.CheckResult(
            "packages", "Python packages", _doctor.WARN,
            detail + f"; {', '.join(native)} contain native extensions, which are experimental on {mode}",
            "Verify these packages under load and at build time; prefer pure-Python alternatives or GraalPy-specific wheels", data,
        )
    return _doctor.CheckResult("packages", "Python packages", _doctor.PASS, detail, data=data)


def _graalpy_release_tag(version_line: str | None, expected: str | None) -> str | None:
    for candidate in (version_line or "", expected or ""):
        match = re.search(r"(\d+)\.(\d+)", candidate.rsplit("GraalVM", 1)[-1] if "GraalVM" in candidate else candidate)
        if match and int(match.group(1)) >= 23:
            return f"v{match.group(1)}{match.group(2)}"
    return None


def _graalpy_compatibility_cache(release: str) -> Path:
    return Path.home() / ".pyronaut" / "graalpy-compatibility" / f"python-module-testing-{release}.csv"


def _load_graalpy_compatibility(release: str, *, offline: bool) -> tuple[dict[str, tuple[str, int, float]] | None, str]:
    """Return ``{package: (version, status, percent)}`` from graalpy.org, cached for a week."""
    cache = _graalpy_compatibility_cache(release)
    url = _GRAALPY_COMPATIBILITY_URL.format(release=release)
    fresh = cache.is_file() and time.time() - cache.stat().st_mtime < _GRAALPY_COMPATIBILITY_MAX_AGE
    note = ""
    if not fresh and not offline:
        try:
            cache.parent.mkdir(parents=True, exist_ok=True)
            temporary = cache.with_suffix(".csv.part")
            _download_url_with_progress(url, temporary, f"Downloading GraalPy {release} compatibility data")
            os.replace(temporary, cache)
        except (OSError, ValueError) as exc:
            note = f"could not refresh {url}: {_last_line(str(exc)) or exc.__class__.__name__}"
    if not cache.is_file():
        return None, note or f"{url} not cached (offline)"
    table: dict[str, tuple[str, int, float]] = {}
    try:
        for line in cache.read_text(encoding="utf-8").splitlines():
            fields = line.strip().split(",")
            if len(fields) < 3 or not fields[0]:
                continue
            try:
                status = int(fields[2])
                percent = float(fields[3]) if len(fields) > 3 and re.fullmatch(r"\d+(\.\d+)?", fields[3]) else 0.0
            except ValueError:
                continue
            table[_canonical_package_name(fields[0])] = (fields[1], status, percent)
    except OSError as exc:
        return None, str(exc)
    if not table:
        return None, note or f"{cache} is empty"
    return table, note


def _doctor_check_graalpy_compatibility(project_dir: Path, *, offline: bool) -> _doctor.CheckResult:
    packages = _read_pyproject_python_dependencies(project_dir)
    data: dict[str, object] = {"declared": packages, "source": _GRAALPY_COMPATIBILITY_PAGE, "packages": {}}
    if not packages:
        return _doctor.CheckResult("compat", "GraalPy compatibility", _doctor.PASS, "no [project].dependencies to look up", data=data)
    venv_dir = project_dir / ".venv"
    venv_bin = _virtualenv_bin(venv_dir)
    venv_python = _resolve_virtualenv_python(venv_bin) if venv_bin.is_dir() else None
    version_line = _probe_python_version(venv_python) if venv_python is not None else None
    release = _graalpy_release_tag(version_line, _read_version_properties().get("graalpy"))
    data["release"] = release
    if release is None:
        return _doctor.CheckResult(
            "compat", "GraalPy compatibility", _doctor.WARN, "cannot determine the GraalPy release to look up (no .venv and no bundled version)",
            "Run pyronaut install to create the project .venv with GraalPy", data,
        )
    table, note = _load_graalpy_compatibility(release, offline=offline)
    data["dataFile"] = str(_graalpy_compatibility_cache(release))
    if table is None:
        return _doctor.CheckResult(
            "compat", "GraalPy compatibility", _doctor.WARN, f"GraalPy {release[1:3]}.{release[3:]} package data unavailable: {note}",
            f"Check {_GRAALPY_COMPATIBILITY_PAGE} manually, or re-run without --offline", data,
        )
    fails: list[str] = []
    partial: list[str] = []
    untested: list[str] = []
    for name in packages:
        entry = table.get(_canonical_package_name(name))
        if entry is None:
            untested.append(name)
            data["packages"][name] = {"status": "untested"}  # type: ignore[index]
            continue
        version, status, percent = entry
        if status >= 2:
            fails.append(f"{name} {version}")
            verdict = "fails-to-install"
        elif percent < _GRAALPY_COMPATIBLE_PERCENT:
            partial.append(f"{name} {version} ({percent:.0f}% tests pass)")
            verdict = "partial"
        else:
            verdict = "compatible"
        data["packages"][name] = {"status": verdict, "version": version, "testsPassed": percent}  # type: ignore[index]
    label = f"GraalPy {release[1:3]}.{release[3:]}"
    compatible = len(packages) - len(fails) - len(partial) - len(untested)
    detail = f"{label}: {compatible} of {len(packages)} declared packages compatible per graalpy.org"
    if untested:
        detail += f"; untested: {', '.join(untested)}"
    if note:
        detail += f"; {note}"
    if fails or partial:
        if fails:
            detail += f"; fail to install: {', '.join(fails)}"
        if partial:
            detail += f"; partially compatible: {', '.join(partial)}"
        return _doctor.CheckResult(
            "compat", "GraalPy compatibility", _doctor.WARN, detail,
            f"Test these packages under pyronaut test and review {_GRAALPY_COMPATIBILITY_PAGE}; consider pure-Python alternatives", data,
        )
    return _doctor.CheckResult("compat", "GraalPy compatibility", _doctor.PASS, detail, data=data)


def _terminal_environment(env: dict[str, str]) -> dict[str, str]:
    """Tell delegated tools how wide the terminal is and when the command began.

    Java cannot query the terminal size, so the delegated tools keep their
    live progress rows within ``COLUMNS``; without it the rows are clamped to
    80 columns. The shared epoch keeps every tool's ``[elapsed]`` stamps on the
    same timeline as the CLI's own steps.
    """
    env.setdefault(_PROGRESS_EPOCH_ENV, str(_progress_epoch_ms()))
    if "COLUMNS" not in env:
        try:
            if sys.stderr.isatty():
                env["COLUMNS"] = str(shutil.get_terminal_size((80, 24)).columns)
        except (AttributeError, OSError, ValueError):
            pass
    return env


def _print_build_usage(stream=None) -> None:
    if stream is None:
        stream = sys.stdout
    stream.write(
        "Usage: pyronaut build [--project-dir <dir>] [--jar|--native|--jvm|--mode=<native|jvm>] [--docker] [--static] [--native-base[=<default|path|image|url>]] [--pgo=<profile>[,<profile>...]] [--include-native-binary <name|path>]... [--name <name>] [--version <version>] [<source.java|source.py|source-dir>...] [--main-class <fqcn>] [--verbose] [--no-cache] [--no-validate]\n"
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

    mode_token, args = _split_tui_mode_token([a for a in argv if a != "--tui"])

    if _extract_flag(args, "--help") or _extract_flag(args, "-h"):
        return _delegate_to_tui_binary(["--help"], runner_with_env, resolver)
    if _extract_flag(args, "--version") or _extract_flag(args, "-V"):
        return _delegate_to_tui_binary(["--version"], runner_with_env, resolver)

    direct_source = _looks_like_direct_source_invocation(args)
    project_dir = Path.cwd().resolve() if direct_source else Path(_extract_project_dir(args)).resolve()
    try:
        tui_toolchain_type = "jvm" if direct_source else _read_pyproject_toolchain_type(project_dir)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return PRECONDITION_FAILED
    smoke = _extract_flag(args, "--smoke")
    non_interactive = _extract_flag(args, "--non-interactive")
    initial_mode = "test" if mode_token == "test" or _extract_flag(args, "--test") else "run"
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
        if not self._started:
            return

        if self._shared_server:
            self._emit_status("[test-resources] stop skipped (shared server mode)")
            self._remove_session_file()
            self._started = False
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
            self._started = False
        else:
            self._emit_status(f"[test-resources] stop failed (exit code {exit_code}); preserving session state for retry")

    def client_env_overrides(self) -> dict[str, str] | None:
        if self._client_env_overrides is None:
            return None
        overrides = dict(self._client_env_overrides)
        # The delegated launcher blocks on the server while it owns the
        # terminal, so it is the one that reports the containers being pulled.
        overrides[_TEST_RESOURCES_LOGS_DIR_ENV] = str(
            _resolve_test_resources_logs_dir(self._project_dir, self._settings_file)
        )
        return overrides

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
        _progress_console().note(line)

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
            self._emit_status("[test-resources] stale external settings detected; starting owned server instead")
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
            # The server launcher is a JVM script: without JAVA_HOME from the
            # provider it cannot find a runtime on hosts where only the
            # provisioned GraalVM under ~/.pyronaut/sdks exists.
            env = _build_non_test_resources_env("test-resources-server", java_home_provider)
            effective_runner = _run_subprocess_quiet if self._quiet and runner is _run_subprocess else runner
            return int(effective_runner(command_line, env))
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


def _split_tui_mode_token(args: Sequence[str]) -> tuple[str | None, list[str]]:
    """Consume a leading ``run``/``test`` command token for the TUI."""
    values = list(args)
    if values and values[0] in {"run", "test"}:
        return values[0], values[1:]
    return None, values


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
        try:
            delegated = {
                "validate-config": _resolve_delegate_executable_path("validate-config", ["--project-dir", str(project_dir)], resolver),
                "install": _resolve_delegate_executable_path("install", ["--project-dir", str(project_dir)], resolver),
                "process": _resolve_delegate_executable_path("process", ["--project-dir", str(project_dir)], resolver),
                "run": _resolve_delegate_executable_path("run", ["--project-dir", str(project_dir)], resolver),
                "test": _resolve_delegate_executable_path("test", ["--project-dir", str(project_dir)], resolver),
            }
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            if tr_session is not None:
                tr_session.stop_if_owned(runner=runner, resolver=resolver)
            return PRECONDITION_FAILED
    missing = [name for (name, path) in delegated.items() if path is None]
    if missing:
        print("Missing delegated executable(s): " + ", ".join(f"pyronaut-{name}" for name in missing), file=sys.stderr)
        return PRECONDITION_FAILED
    pyronaut_table = _read_pyproject_pyronaut_table(project_dir)
    if direct_dev_executable is None:
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
    selected_java_home = base_env.get("JAVA_HOME") if base_env is not None else None
    if direct_dev_executable is not None and selected_java_home:
        command_line.extend(["--java-home", selected_java_home])
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
    return any(token == name or token.startswith(name + "=") for token in _orchestrator_args(argv))


def _extract_path_flag(argv: Sequence[str], name: str) -> Path | None:
    for i, token in enumerate(argv):
        if token == name and i + 1 < len(argv):
            value = argv[i + 1].strip()
            return Path(value) if value else None
        if token.startswith(name + "="):
            value = token.split("=", 1)[1].strip()
            return Path(value) if value else None
    return None
