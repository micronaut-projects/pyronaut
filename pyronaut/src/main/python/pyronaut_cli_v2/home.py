"""Location of the Pyronaut home directory (``~/.pyronaut`` by default).

``PYRONAUT_HOME`` relocates it; otherwise it lives under ``$HOME``. The Java
tools the CLI starts resolve it the same way (``PyronautHome`` in
pyronaut-config-model), and the CLI exports the resolved directory to them as
``PYRONAUT_HOME`` so that both sides always agree, even where the JVM's
``user.home`` (taken from the passwd entry) differs from ``$HOME``.

Imports only the standard library.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import MutableMapping

PYRONAUT_HOME_ENV = "PYRONAUT_HOME"


def pyronaut_home(environment: MutableMapping[str, str] | None = None) -> Path:
    env = os.environ if environment is None else environment
    configured = (env.get(PYRONAUT_HOME_ENV) or "").strip()
    if configured:
        path = Path(configured).expanduser()
        return path if path.is_absolute() else (Path.cwd() / path)
    return Path.home() / ".pyronaut"


def export_pyronaut_home(environment: MutableMapping[str, str] | None = None) -> Path:
    """Pin ``PYRONAUT_HOME`` in ``environment`` to the resolved, absolute home.

    Child processes inherit it, so every Java tool and launcher uses the same
    directory as the CLI.
    """
    env = os.environ if environment is None else environment
    home = pyronaut_home(env)
    env[PYRONAUT_HOME_ENV] = str(home)
    return home
