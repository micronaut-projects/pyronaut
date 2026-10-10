"""Locations of Pyronaut state: configuration, caches and data.

``PYRONAUT_HOME`` keeps all of them in one directory. Otherwise the XDG base
directory layout splits state into ``$XDG_CONFIG_HOME/pyronaut``
(``settings.toml``), ``$XDG_CACHE_HOME/pyronaut`` (re-creatable caches) and
``$XDG_DATA_HOME/pyronaut`` (provisioned SDKs, launchers and tool runtimes),
or everything lives in ``~/.pyronaut``. See ``xdg_enabled`` for how the
layout is chosen.

The Java tools the CLI starts resolve the directories the same way
(``PyronautHome`` in pyronaut-config-model). The CLI exports the resolved
directories to them as ``PYRONAUT_CONFIG_DIR``, ``PYRONAUT_CACHE_DIR`` and
``PYRONAUT_DATA_DIR`` so that both sides always agree, even where the JVM's
``user.home`` (taken from the passwd entry) differs from ``$HOME``.

Imports only the standard library.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import MutableMapping, NamedTuple

PYRONAUT_HOME_ENV = "PYRONAUT_HOME"
PYRONAUT_XDG_ENV = "PYRONAUT_XDG"
CONFIG_DIR_ENV = "PYRONAUT_CONFIG_DIR"
CACHE_DIR_ENV = "PYRONAUT_CACHE_DIR"
DATA_DIR_ENV = "PYRONAUT_DATA_DIR"
XDG_DIR_NAME = "pyronaut"

LAYOUT_HOME = "home"
LAYOUT_CUSTOM = "PYRONAUT_HOME"
LAYOUT_XDG = "xdg"

_XDG_BASE_VARIABLES = ("XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME")
_TRUE_VALUES = {"1", "true", "yes", "on"}
_FALSE_VALUES = {"0", "false", "no", "off"}


class PyronautDirs(NamedTuple):
    layout: str
    config: Path
    cache: Path
    data: Path


def _environment(environment: MutableMapping[str, str] | None) -> MutableMapping[str, str]:
    return os.environ if environment is None else environment


def _explicit_home(env: MutableMapping[str, str]) -> Path | None:
    configured = (env.get(PYRONAUT_HOME_ENV) or "").strip()
    if not configured:
        return None
    path = Path(configured).expanduser()
    return path if path.is_absolute() else (Path.cwd() / path)


def pyronaut_home(environment: MutableMapping[str, str] | None = None) -> Path:
    """The single-root Pyronaut home: ``PYRONAUT_HOME`` or ``~/.pyronaut``."""
    explicit = _explicit_home(_environment(environment))
    return explicit if explicit is not None else Path.home() / ".pyronaut"


def _xdg_base(env: MutableMapping[str, str], variable: str, *default: str) -> Path:
    # The XDG specification ignores relative values.
    configured = (env.get(variable) or "").strip()
    if configured and Path(configured).is_absolute():
        return Path(configured)
    return Path.home().joinpath(*default)


def xdg_config_dir(environment: MutableMapping[str, str] | None = None) -> Path:
    """``$XDG_CONFIG_HOME/pyronaut``; its existence enables the XDG layout."""
    return _xdg_base(_environment(environment), "XDG_CONFIG_HOME", ".config") / XDG_DIR_NAME


def xdg_flag(environment: MutableMapping[str, str] | None = None) -> bool | None:
    """The explicit ``PYRONAUT_XDG`` choice, or None when it is unset or unrecognised."""
    value = (_environment(environment).get(PYRONAUT_XDG_ENV) or "").strip().lower()
    if value in _TRUE_VALUES:
        return True
    if value in _FALSE_VALUES:
        return False
    return None


def _is_linux() -> bool:
    return sys.platform.startswith("linux")


def xdg_by_default(environment: MutableMapping[str, str] | None = None) -> bool:
    """Whether a fresh installation uses the XDG layout: on Linux, or when an
    ``XDG_*_HOME`` base directory is set."""
    env = _environment(environment)
    for variable in _XDG_BASE_VARIABLES:
        value = (env.get(variable) or "").strip()
        if value and Path(value).is_absolute():
            return True
    return _is_linux()


def xdg_enabled(environment: MutableMapping[str, str] | None = None) -> bool:
    """Whether configuration, caches and data follow the XDG base directories.

    Never with an explicit ``PYRONAUT_HOME``. Otherwise ``PYRONAUT_XDG``
    decides when set; then an existing ``$XDG_CONFIG_HOME/pyronaut`` enables
    the layout, an existing ``~/.pyronaut`` keeps that installation where it
    is, and a fresh installation follows ``xdg_by_default``.
    """
    env = _environment(environment)
    if _explicit_home(env) is not None:
        return False
    flag = xdg_flag(env)
    if flag is not None:
        return flag
    if xdg_config_dir(env).is_dir():
        return True
    if (Path.home() / ".pyronaut").is_dir():
        return False
    return xdg_by_default(env)


def pyronaut_dirs(environment: MutableMapping[str, str] | None = None) -> PyronautDirs:
    env = _environment(environment)
    explicit = _explicit_home(env)
    if explicit is not None:
        return PyronautDirs(LAYOUT_CUSTOM, explicit, explicit, explicit)
    if xdg_enabled(env):
        return PyronautDirs(
            LAYOUT_XDG,
            xdg_config_dir(env),
            _xdg_base(env, "XDG_CACHE_HOME", ".cache") / XDG_DIR_NAME,
            _xdg_base(env, "XDG_DATA_HOME", ".local", "share") / XDG_DIR_NAME,
        )
    home = Path.home() / ".pyronaut"
    return PyronautDirs(LAYOUT_HOME, home, home, home)


def config_home(environment: MutableMapping[str, str] | None = None) -> Path:
    """User configuration such as ``settings.toml``."""
    return pyronaut_dirs(environment).config


def cache_home(environment: MutableMapping[str, str] | None = None) -> Path:
    """Re-creatable caches: AOT caches, IDE stubs, compatibility data, update downloads."""
    return pyronaut_dirs(environment).cache


def data_home(environment: MutableMapping[str, str] | None = None) -> Path:
    """Provisioned state: SDKs, JDKs, native launchers, setup manifests and tool runtimes."""
    return pyronaut_dirs(environment).data


def export_pyronaut_home(environment: MutableMapping[str, str] | None = None) -> PyronautDirs:
    """Pin the resolved, absolute Pyronaut directories in ``environment``.

    Child processes inherit them, so every Java tool and launcher uses the
    same directories as the CLI. ``PYRONAUT_HOME`` is only rewritten (to an
    absolute path) when the user set it, so that it never masks the XDG
    layout in a nested CLI.
    """
    env = _environment(environment)
    dirs = pyronaut_dirs(env)
    if dirs.layout == LAYOUT_CUSTOM:
        env[PYRONAUT_HOME_ENV] = str(dirs.data)
    env[CONFIG_DIR_ENV] = str(dirs.config)
    env[CACHE_DIR_ENV] = str(dirs.cache)
    env[DATA_DIR_ENV] = str(dirs.data)
    return dirs
