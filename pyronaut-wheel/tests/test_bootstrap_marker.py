from __future__ import annotations

import pathlib
import sys


sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

from pyronaut.bootstrap import read_install_marker, write_install_marker  # noqa: E402


def test_marker_roundtrip(tmp_path: pathlib.Path) -> None:
    marker_path = tmp_path / ".installed.json"

    written = write_install_marker(marker_path, kind="wheel")
    read_back = read_install_marker(marker_path)

    assert read_back == written
    assert read_back is not None
    assert read_back.kind == "wheel"
    assert read_back.createdAt
    assert read_back.platform
