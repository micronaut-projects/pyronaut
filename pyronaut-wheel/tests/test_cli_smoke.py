from __future__ import annotations

# pyright: reportMissingImports=false, reportUnknownVariableType=false


def test_cli_main_returns_zero() -> None:
    # Ensure local sources are importable even without editable install.
    import pathlib
    import sys

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

    import pyronaut.cli as cli  # type: ignore[unknown-variable-type]

    def fake_bootstrap_and_exec(argv: list[str]) -> int:
        assert argv == []
        return 0

    old = cli.bootstrap_and_exec
    cli.bootstrap_and_exec = fake_bootstrap_and_exec  # type: ignore[method-assign]
    try:
        assert cli.main([]) == 0
    finally:
        cli.bootstrap_and_exec = old  # type: ignore[method-assign]


def test_cli_main_delegates_and_propagates_exit_code(monkeypatch) -> None:
    import pathlib
    import sys

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src"))

    import pyronaut.cli as cli  # type: ignore[unknown-variable-type]

    captured: list[list[str]] = []

    def fake_bootstrap_and_exec(argv: list[str]) -> int:
        captured.append(list(argv))
        return 42

    monkeypatch.setattr(cli, "bootstrap_and_exec", fake_bootstrap_and_exec)

    assert cli.main(["a", "b"]) == 42
    assert captured == [["a", "b"]]
