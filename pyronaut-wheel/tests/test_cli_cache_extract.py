from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false

import os
import stat
import time
import zipfile
from pathlib import Path
import pathlib
import sys

from contextlib import redirect_stderr
from io import StringIO


sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from pyronaut.cli import ensure_cli_home  # noqa: E402


def _make_cli_zip(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, mode="w") as zf:
        zf.writestr("bin/pyronaut", "#!/bin/sh\necho ok\n")


def test_extracts_cli_zip_to_cache_idempotently(tmp_path: Path) -> None:
    cache_root = tmp_path / "cache"
    zip_path = tmp_path / "cli.zip"
    _make_cli_zip(zip_path)

    buf1 = StringIO()
    with redirect_stderr(buf1):
        home1 = ensure_cli_home(
            cache_root=cache_root,
            zip_path=zip_path,
            cli_platform="linux-x64",
        )
    assert buf1.getvalue() != ""
    launcher = home1 / "bin" / "pyronaut"
    assert launcher.exists()
    if os.name != "nt":
        mode = launcher.stat().st_mode
        assert mode & stat.S_IXUSR

    marker_path = home1.parent / ".installed.json"
    assert marker_path.exists()
    marker_mtime_1 = marker_path.stat().st_mtime

    time.sleep(0.02)
    buf2 = StringIO()
    with redirect_stderr(buf2):
        home2 = ensure_cli_home(
            cache_root=cache_root,
            zip_path=zip_path,
            cli_platform="linux-x64",
        )
    assert home2 == home1
    assert (home2 / "bin" / "pyronaut").exists()
    assert marker_path.stat().st_mtime == marker_mtime_1
    assert buf2.getvalue() == ""
