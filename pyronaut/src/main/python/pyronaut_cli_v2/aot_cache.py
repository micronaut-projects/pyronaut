"""Opt-in Leyden AOT caches for the JVMs the CLI launches.

With ``tool.pyronaut.toolchain.aot-cache = true``, the first launch of a JVM
tool is a training run (``-XX:AOTMode=record``). Once it exits, the cache is
created in the background, and later launches with the same JVM, options and
class path start from it (``-XX:AOTCache``). Caches live in
``~/.pyronaut/caches/aot``, one slot per tool (and per project for the
application JVMs of ``dev``, ``run`` and ``test``); a slot keeps only the cache
for its current key.

Requires JDK 25 or later on macOS or Linux; other launches run unchanged.
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

# GraalVM logs a module mismatch at error level whenever it opens an AOT cache,
# even one it then uses, and a stale cache is reported the same way. A cache
# that cannot be used only costs the startup it would have saved.
QUIET_FLAGS = ("-Xlog:aot*=off,cds*=off",)
# A training JVM writes its AOT configuration as it exits, which takes seconds
# for an application JVM; stopping it sooner discards the training run.
TRAINING_STOP_TIMEOUT_SECONDS = 30.0
_MINIMUM_JAVA_MAJOR = 25
_CLASSPATH_OPTIONS = ("-cp", "-classpath", "--class-path")
_CREATION_IN_PROGRESS_SECONDS = 600
_STALE_FILE_SECONDS = 86400
_CLASSPATH_LINE = re.compile(r"^CLASSPATH=(.*)$", re.MULTILINE)
_JAVA_VERSION_LINE = re.compile(r'^JAVA_VERSION="(\d+)', re.MULTILINE)


def cache_root() -> Path:
    return Path.home() / ".pyronaut" / "caches" / "aot"


@dataclass
class Training:
    """Creates the cache from a training run's configuration once it exits."""

    configuration: Path
    cache: Path
    create_command: list[str]
    create_env: dict[str, str] | None
    _finished: bool = field(default=False, init=False)

    def abandon(self) -> None:
        """Discard a configuration the JVM may not have finished writing."""
        if self._finished:
            return
        self._finished = True
        self.configuration.unlink(missing_ok=True)

    def finish(self) -> None:
        if self._finished:
            return
        self._finished = True
        try:
            if not self.configuration.is_file() or self.configuration.stat().st_size == 0:
                self.configuration.unlink(missing_ok=True)
                return
        except OSError:
            return
        temporary = self.cache.with_name(f"{self.cache.name}.tmp-{os.getpid()}")
        failed = self.cache.with_suffix(".failed")
        # The cache appears under its final name only once complete; a failure
        # is recorded so that later launches do not train again for nothing.
        script = (
            'tmp=$1 final=$2 conf=$3 failed=$4; shift 4; '
            '"$@" >/dev/null 2>&1 && mv -f "$tmp" "$final" || touch "$failed"; '
            'rm -f "$tmp" "$conf"'
        )
        command = [
            "/bin/sh", "-c", script, "sh",
            str(temporary), str(self.cache), str(self.configuration), str(failed),
            *[value.replace("{cache}", str(temporary)) for value in self.create_command],
        ]
        env = None
        if self.create_env is not None:
            env = {key: value.replace("{cache}", str(temporary)) for key, value in self.create_env.items()}
        try:
            subprocess.Popen(
                command,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
        except OSError:
            self.configuration.unlink(missing_ok=True)


@dataclass
class Launch:
    command_line: list[str]
    env: dict[str, str] | None
    training: Training | None = None

    def finish(self) -> None:
        if self.training is not None:
            self.training.finish()

    def abandon(self) -> None:
        if self.training is not None:
            self.training.abandon()


class ManagedProcess:
    """Wraps a training process: allows it time to exit and creates the cache afterwards."""

    stop_timeout = TRAINING_STOP_TIMEOUT_SECONDS

    def __init__(self, process, launch: Launch) -> None:
        self._process = process
        self._launch = launch

    def poll(self) -> int | None:
        code = self._process.poll()
        if code is not None:
            self._launch.finish()
        return code

    def wait(self, timeout: float | None = None) -> int:
        code = self._process.wait(timeout)
        self._launch.finish()
        return code

    def terminate(self) -> None:
        self._process.terminate()

    def kill(self) -> None:
        self._launch.abandon()
        self._process.kill()

    def __getattr__(self, name: str):
        return getattr(self._process, name)


def prepare(
    command_line: Sequence[str],
    env: dict[str, str] | None,
    *,
    project_dir: Path,
    root: Path | None = None,
) -> Launch:
    """Add AOT cache options to a JVM launch, or return it unchanged."""
    unchanged = Launch(list(command_line), env)
    if os.name != "posix" or not command_line:
        return unchanged
    effective_env = dict(os.environ if env is None else env)
    if any("AOTCache" in value or "AOTMode" in value for value in _jvm_option_sources(command_line, effective_env)):
        # The user manages the AOT cache of this launch.
        return unchanged
    target = _direct_java(command_line, env, project_dir) or _launcher_script(command_line, effective_env)
    if target is None:
        return unchanged
    release = target.java_home / "release"
    try:
        release_text = release.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return unchanged
    major = _JAVA_VERSION_LINE.search(release_text)
    if major is None or int(major.group(1)) < _MINIMUM_JAVA_MAJOR:
        return unchanged

    digest = hashlib.sha256()
    for part in (str(target.java_home), release_text, *target.key_parts):
        digest.update(part.encode("utf-8", errors="surrogateescape"))
        digest.update(b"\0")
    key = digest.hexdigest()[:32]
    slot = (root or cache_root()) / hashlib.sha256(target.slot.encode("utf-8", errors="surrogateescape")).hexdigest()[:16]
    cache = slot / f"{key}.aot"

    if cache.is_file():
        return target.with_options([*QUIET_FLAGS, f"-XX:AOTCache={cache}"])
    if _recently_failed(cache.with_suffix(".failed")) or _creation_in_progress(slot, key):
        return unchanged
    try:
        slot.mkdir(parents=True, exist_ok=True)
    except OSError:
        return unchanged
    _remove_superseded(slot, key)
    configuration = slot / f"{key}.{os.getpid()}.aotconf"
    launch = target.with_options([*QUIET_FLAGS, "-XX:AOTMode=record", f"-XX:AOTConfiguration={configuration}"])
    create_command, create_env = target.create([
        *QUIET_FLAGS,
        "-XX:AOTMode=create",
        f"-XX:AOTConfiguration={configuration}",
        "-XX:AOTCache={cache}",
    ])
    launch.training = Training(configuration, cache, create_command, create_env)
    return launch


@dataclass
class _Target:
    java_home: Path
    slot: str
    key_parts: list[str]
    command_line: list[str]
    env: dict[str, str]
    original_env: dict[str, str] | None
    # Direct java launches: index after the java executable, and the class path.
    classpath: str | None = None
    # Launcher scripts: the variable the script reads JVM options from.
    options_variable: str | None = None

    def with_options(self, options: Sequence[str]) -> Launch:
        if self.options_variable is None:
            return Launch([self.command_line[0], *options, *self.command_line[1:]], self.original_env)
        env = dict(self.env)
        env[self.options_variable] = " ".join([*options, env.get(self.options_variable, "")]).strip()
        return Launch(list(self.command_line), env)

    def create(self, options: Sequence[str]) -> tuple[list[str], dict[str, str] | None]:
        if self.options_variable is None:
            jvm_options = self.command_line[1:_classpath_index(self.command_line)]
            return [self.command_line[0], *jvm_options, *options, "-cp", self.classpath or ""], self.original_env
        # In create mode the JVM writes the cache and exits without running the
        # main class, so the launcher script needs no arguments.
        env = dict(self.env)
        env[self.options_variable] = " ".join([*options, env.get(self.options_variable, "")]).strip()
        return [self.command_line[0]], env


def _direct_java(command_line: Sequence[str], env: dict[str, str] | None, project_dir: Path) -> _Target | None:
    executable = Path(command_line[0])
    if executable.name != "java":
        return None
    index = _classpath_index(command_line)
    if index < 0 or index + 2 >= len(command_line):
        return None
    classpath = command_line[index + 1]
    main_class = command_line[index + 2]
    jvm_options = [value for value in command_line[1:index] if not value.startswith("-D")]
    try:
        java_home = executable.resolve().parent.parent
    except OSError:
        return None
    return _Target(
        java_home=java_home,
        slot=f"java\0{main_class}\0{project_dir}",
        key_parts=[main_class, *jvm_options, *_classpath_state(classpath.split(os.pathsep))],
        command_line=list(command_line),
        env=dict(os.environ if env is None else env),
        original_env=env,
        classpath=classpath,
    )


def _launcher_script(command_line: Sequence[str], env: dict[str, str]) -> _Target | None:
    script = Path(command_line[0])
    if not script.name.startswith("pyronaut-"):
        return None
    try:
        script = script.resolve()
        with script.open("rb") as stream:
            if stream.read(2) != b"#!":
                # A native image: there is no JVM to cache.
                return None
        text = script.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = _CLASSPATH_LINE.search(text)
    if match is None:
        return None
    java_home = _script_java_home(env)
    if java_home is None:
        return None
    app_home = str(script.parent.parent)
    entries = [entry.replace("$APP_HOME", app_home) for entry in match.group(1).strip().split(":") if entry]
    options_variable = script.name.upper().replace("-", "_") + "_OPTS"
    options = [
        value
        for variable in ("JAVA_OPTS", options_variable, "JAVA_TOOL_OPTIONS")
        for value in env.get(variable, "").split()
        if not value.startswith("-D")
    ]
    return _Target(
        java_home=java_home,
        slot=f"script\0{script}",
        key_parts=[str(script), text, *options, *_classpath_state(entries)],
        command_line=list(command_line),
        env=env,
        original_env=env,
        options_variable=options_variable,
    )


def _script_java_home(env: dict[str, str]) -> Path | None:
    java_home = env.get("JAVA_HOME")
    if java_home:
        return Path(java_home)
    java = shutil.which("java", path=env.get("PATH"))
    if java is None:
        return None
    try:
        return Path(java).resolve().parent.parent
    except OSError:
        return None


def _classpath_index(command_line: Sequence[str]) -> int:
    for index, value in enumerate(command_line):
        if value in _CLASSPATH_OPTIONS:
            return index
    return -1


def _classpath_state(entries: Sequence[str]) -> list[str]:
    # The JVM rejects a cache whose class path entries changed. Key on their
    # size and modification time so that such a cache is replaced, not kept.
    state = []
    for entry in entries:
        try:
            stat = Path(entry).stat()
            state.append(f"{entry}\0{stat.st_size}\0{stat.st_mtime_ns}")
        except OSError:
            state.append(f"{entry}\0missing")
    return state


def _jvm_option_sources(command_line: Sequence[str], env: dict[str, str]) -> list[str]:
    index = _classpath_index(command_line)
    options = list(command_line[1:index]) if index > 0 else []
    options.extend(value for key, value in env.items() if key == "JAVA_TOOL_OPTIONS" or key.endswith("_OPTS"))
    return options


def _recently_failed(marker: Path) -> bool:
    try:
        return time.time() - marker.stat().st_mtime < _STALE_FILE_SECONDS
    except OSError:
        return False


def _creation_in_progress(slot: Path, key: str) -> bool:
    now = time.time()
    for temporary in slot.glob(f"{key}.aot.tmp-*"):
        try:
            if now - temporary.stat().st_mtime < _CREATION_IN_PROGRESS_SECONDS:
                return True
        except OSError:
            continue
    return False


def _remove_superseded(slot: Path, key: str) -> None:
    now = time.time()
    for entry in slot.iterdir():
        if entry.name.startswith(f"{key}."):
            continue
        try:
            if entry.suffix in {".aot", ".failed"} or now - entry.stat().st_mtime > _STALE_FILE_SECONDS:
                entry.unlink()
        except OSError:
            continue
