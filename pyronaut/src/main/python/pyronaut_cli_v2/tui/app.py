from __future__ import annotations

import os
import shlex
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


@dataclass(frozen=True)
class DelegationTrace:
    command_line: tuple[str, ...]
    env_overrides: tuple[tuple[str, str], ...] = ()

    def render(self) -> str:
        rendered = shlex.join(list(self.command_line))
        if not self.env_overrides:
            return rendered
        env_part = " ".join(f"{k}={shlex.quote(v)}" for (k, v) in self.env_overrides)
        return f"{env_part} {rendered}"


@dataclass(frozen=True)
class TuiOptions:
    project_dir: Path
    report_dir: Path | None
    initial_mode: str
    smoke: bool
    non_interactive: bool
    trace_delegation: bool


class TuiApp:
    def __init__(
        self,
        *,
        options: TuiOptions,
        delegate: Callable[[str, Sequence[str]], int],
        report_summary: Callable[[Path], str | None],
    ) -> None:
        self._options = options
        self._delegate = delegate
        self._report_summary = report_summary

    def run(self) -> int:
        if self._options.smoke or self._options.non_interactive:
            return self._run_smoke()
        return self._run_interactive_placeholder()

    def _run_smoke(self) -> int:
        self._log(f"[tui] init project_dir={self._options.project_dir}")
        if self._options.report_dir is not None:
            self._log(f"[tui] report_dir={self._options.report_dir}")

        if self._options.initial_mode == "test":
            code = self._delegate("test", ["--project-dir", str(self._options.project_dir)])
            if self._options.report_dir is not None:
                summary = self._report_summary(self._options.report_dir)
                if summary is not None:
                    self._log(summary)
            return 0 if self._options.smoke else code

        code = self._delegate("install", ["--project-dir", str(self._options.project_dir)])
        if code != 0:
            return code
        code = self._delegate("process", ["--project-dir", str(self._options.project_dir)])
        if code != 0:
            return code
        if self._options.smoke:
            self._log("[tui] smoke: run delegation verified")
            return 0
        return self._delegate("run", ["--project-dir", str(self._options.project_dir)])

    def _run_interactive_placeholder(self) -> int:
        self._log("[tui] interactive mode is not available in this Python runtime")
        self._log("[tui] use --smoke or --non-interactive for automation")
        return 2

    @staticmethod
    def _log(message: str) -> None:
        sys.stderr.write(message + os.linesep)
