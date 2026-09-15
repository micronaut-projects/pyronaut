"""Terminal progress rendering for the Pyronaut CLI.

On a terminal the console keeps a live region at the bottom of stderr: a
spinner row per running step and a byte-level bar per active download. A
background ticker repaints the region so the spinner and elapsed time keep
moving even while a step performs silent work such as unpacking an archive.
Finished steps collapse into permanent ``[elapsed] ✓ ...`` lines above the
region, matching the delegated ``pyronaut-install`` reporter.

Without a terminal (CI, pipes, ``--progress off``) the console prints
deterministic plain lines and never emits control sequences.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Iterator

_FRAME_INTERVAL = 0.08
# Delegated tools stamp their progress lines relative to this epoch so the
# whole command reads as one timeline. An outer CLI invocation wins.
PROGRESS_EPOCH_ENV = "PYRONAUT_PROGRESS_EPOCH_MS"
_MAX_TRANSFER_ROWS = 6
_MIN_TRANSFER_NAME_WIDTH = 12
_BAR_WIDTH = 14
_DEFAULT_WIDTH = 80

_RESET = "\033[0m"
_BOLD = "\033[1m"
_DIM = "\033[2m"
_RED = "\033[31m"
_GREEN = "\033[32m"
_YELLOW = "\033[33m"
_CYAN = "\033[36m"


@dataclass(frozen=True)
class _Glyphs:
    spinner: tuple[str, ...]
    bar_filled: str
    bar_empty: str
    check: str
    cross: str
    warning: str
    bullet: str
    branch: str
    separator: str
    ellipsis: str


_UNICODE = _Glyphs(("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"), "█", "░", "✓", "✗", "!", "•", "└", "·", "…")
_ASCII = _Glyphs(("-", "\\", "|", "/"), "#", "-", "+", "x", "!", "*", "L", "-", "...")


def format_duration(seconds: float) -> str:
    if seconds < 60:
        return f"{seconds:.1f}s"
    whole = int(seconds)
    return f"{whole // 60}m {whole % 60:02d}s"


def format_bytes(count: int) -> str:
    if count < 1000:
        return f"{count}B"
    value = count / 1000.0
    for unit in ("KB", "MB", "GB"):
        if value < 1000:
            return f"{value:.1f}{unit}"
        value /= 1000.0
    return f"{value:.1f}TB"


@dataclass
class Task:
    """A unit of work shown in the live region."""

    label: str
    done_label: str | None = None
    parent: Task | None = None
    total: int | None = None
    current: int = 0
    started: float = field(default_factory=time.monotonic)
    finished: float | None = None
    failed: bool = False
    persist: bool = True
    interactive: bool = False
    quiet: bool = False
    _last_reported_percent: int = field(default=-1, repr=False)

    @property
    def elapsed(self) -> float:
        end = self.finished if self.finished is not None else time.monotonic()
        return end - self.started

    @property
    def is_transfer(self) -> bool:
        return self.total is not None

    @property
    def percent(self) -> int:
        if not self.total:
            return 0
        return min(100, self.current * 100 // self.total)

    def update(self, current: int, total: int | None = None) -> None:
        """Record transfer progress; non-interactive output reports every 10%."""
        if total is not None:
            self.total = total
        self.current = current
        if self.interactive or self.quiet or not self.total:
            return
        percent = self.percent
        if percent == self._last_reported_percent:
            return
        first = self._last_reported_percent < 0
        if first or percent == 100 or percent - self._last_reported_percent >= 10:
            self._last_reported_percent = percent
            _plain(f"{self.label}... {percent}%")

    def advance(self, amount: int) -> None:
        self.update(self.current + amount)


def progress_epoch_ms() -> int:
    """Return the epoch (wall clock, milliseconds) shared with delegated tools."""
    return _EPOCH_MS


def _seconds_since_epoch() -> float:
    return max(0.0, time.time() - _EPOCH_MS / 1000.0)


def _resolve_epoch_ms() -> int:
    configured = os.environ.get(PROGRESS_EPOCH_ENV)
    if configured:
        try:
            value = int(configured)
            if 0 <= time.time() * 1000 - value < 24 * 60 * 60 * 1000:
                return value
        except ValueError:
            pass
    return int(time.time() * 1000)


_EPOCH_MS = _resolve_epoch_ms()


def _plain(line: str) -> None:
    print(line, file=sys.stderr, flush=True)


class Console:
    """Owner of the live region. Use :func:`console` for the shared instance."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._tasks: list[Task] = []
        self._drawn_lines = 0
        self._spinner_frame = 0
        self._suspended = 0
        self._ticker: threading.Thread | None = None
        self._stop = threading.Event()
        self._started = time.monotonic() - _seconds_since_epoch()
        self._mode = "auto"

    # -- configuration -----------------------------------------------------

    def configure(self, mode: str | None) -> None:
        """Apply a ``--progress auto|on|off`` selection."""
        self._mode = (mode or "auto").lower()

    @property
    def enabled(self) -> bool:
        return self._mode != "off"

    def _detect_interactive(self) -> bool:
        if not self.enabled:
            return False
        stream = sys.stderr
        try:
            if not hasattr(stream, "isatty") or not stream.isatty():
                return False
        except (AttributeError, OSError, ValueError):
            return False
        term = os.environ.get("TERM", "")
        if term.lower() == "dumb":
            return False
        if os.name == "nt":
            return _enable_windows_vt()
        return bool(term)

    @property
    def interactive(self) -> bool:
        """Whether the live region is currently active on a terminal."""
        return any(task.interactive for task in self._tasks)

    def _message_interactive(self) -> bool:
        return self._tasks[0].interactive if self._tasks else self._detect_interactive()

    @staticmethod
    def _color() -> bool:
        return os.environ.get("NO_COLOR") is None

    @staticmethod
    def _unicode() -> bool:
        encoding = getattr(sys.stderr, "encoding", None) or ""
        return "utf" in encoding.lower()

    @staticmethod
    def width() -> int:
        try:
            return max(40, shutil.get_terminal_size((_DEFAULT_WIDTH, 24)).columns)
        except (OSError, ValueError):
            return _DEFAULT_WIDTH

    # -- tasks -------------------------------------------------------------

    @contextlib.contextmanager
    def step(
        self,
        label: str,
        *,
        done: str | None = None,
        parent: Task | None = None,
        persist: bool = True,
    ) -> Iterator[Task]:
        """Show ``label`` with a spinner until the block exits.

        ``done`` replaces the label on the permanent completion line.
        """
        task = Task(label=label, done_label=done, parent=parent, persist=persist)
        self._begin(task)
        try:
            yield task
        except BaseException:
            task.failed = True
            raise
        finally:
            self._end(task)

    @contextlib.contextmanager
    def transfer(
        self,
        label: str,
        total: int | None,
        *,
        done: str | None = None,
        parent: Task | None = None,
        persist: bool = True,
    ) -> Iterator[Task]:
        """Show ``label`` with a byte-level progress bar until the block exits."""
        task = Task(label=label, done_label=done, parent=parent, total=total or 0, persist=persist)
        self._begin(task)
        try:
            yield task
        except BaseException:
            task.failed = True
            raise
        finally:
            if not task.failed and task.total and task.current < task.total:
                task.update(task.total)
            self._end(task)

    @contextlib.contextmanager
    def suspend(self) -> Iterator[None]:
        """Clear the live region while another process owns the terminal."""
        with self._lock:
            self._suspended += 1
            self._write(self._clear_region())
        try:
            yield
        finally:
            with self._lock:
                self._suspended -= 1
                self._render()

    def print(self, message: str, *, tone: str | None = None, glyph: str | None = None) -> None:
        """Print a permanent line above the live region."""
        with self._lock:
            if self._message_interactive():
                self._print_above(self._stamp(tone, glyph, message))
            else:
                _plain(message)

    def success(self, message: str) -> None:
        with self._lock:
            if self._message_interactive():
                self._print_above(self._stamp(_GREEN, self._glyphs().check, message))
            else:
                _plain(message)

    def warn(self, message: str) -> None:
        with self._lock:
            if self._message_interactive():
                self._print_above(self._stamp(_YELLOW, self._glyphs().warning, message))
            else:
                _plain(f"WARNING: {message}")

    # -- internals ---------------------------------------------------------

    def _begin(self, task: Task) -> None:
        with self._lock:
            if not self.enabled:
                task.quiet = True
                return
            task.interactive = self._detect_interactive() if not self._tasks else self._tasks[0].interactive
            if task.parent is None:
                # Nest under the step that is currently running so unpack and
                # download rows render indented below their phase.
                task.parent = next((live for live in reversed(self._tasks) if not live.is_transfer), None)
            self._tasks.append(task)
            if task.interactive:
                self._ensure_ticker()
                self._render()
            elif task.is_transfer and task.total:
                task.update(0)
            else:
                _plain(f"{task.label}...")

    def _end(self, task: Task) -> None:
        stop_ticker = False
        with self._lock:
            task.finished = time.monotonic()
            if task not in self._tasks:
                return
            self._tasks.remove(task)
            if not task.interactive:
                return
            if task.persist:
                glyphs = self._glyphs()
                took = format_duration(task.elapsed)
                if task.failed:
                    line = self._stamp(_RED, glyphs.cross, task.label, f"({took})")
                elif task.is_transfer and task.total:
                    line = self._stamp(_GREEN, glyphs.check, task.done_label or task.label, f"({format_bytes(task.total)} in {took})")
                else:
                    line = self._stamp(_GREEN, glyphs.check, task.done_label or task.label, f"({took})")
                self._print_above(line)
            else:
                self._render()
            if not self._tasks:
                self._write(self._clear_region())
                stop_ticker = True
        if stop_ticker:
            # Join outside the lock: the ticker needs it to finish its frame.
            self._stop_ticker()

    def _ensure_ticker(self) -> None:
        if self._ticker is not None and self._ticker.is_alive():
            return
        self._stop.clear()
        self._ticker = threading.Thread(target=self._tick, name="pyronaut-progress", daemon=True)
        self._ticker.start()

    def _stop_ticker(self) -> None:
        self._stop.set()
        ticker = self._ticker
        self._ticker = None
        if ticker is not None and ticker is not threading.current_thread():
            ticker.join(timeout=1.0)

    def _tick(self) -> None:
        while not self._stop.wait(_FRAME_INTERVAL):
            with self._lock:
                if self._stop.is_set():
                    return
                self._spinner_frame += 1
                self._render()

    def _glyphs(self) -> _Glyphs:
        return _UNICODE if self._unicode() else _ASCII

    def _paint(self, tone: str | None, text: str) -> str:
        if tone is None or not text or not self._color():
            return text
        return f"{tone}{text}{_RESET}"

    def _stamp(self, tone: str | None, glyph: str | None, message: str, suffix: str | None = None) -> str:
        """Format a permanent line: ``[elapsed] ✓ message (suffix)``."""
        prefix = f"[{format_duration(time.monotonic() - self._started)}]"
        parts = [self._paint(_DIM, prefix)]
        if glyph:
            parts.append(self._paint(tone, glyph))
        parts.append(message)
        if suffix:
            parts.append(self._paint(_DIM, suffix))
        return " ".join(parts)

    def _print_above(self, line: str) -> None:
        self._write(self._clear_region() + line + "\n")
        self._render()

    def _clear_region(self) -> str:
        if self._drawn_lines == 0:
            return ""
        sequence = f"\033[{self._drawn_lines}A\r\033[J"
        self._drawn_lines = 0
        return sequence

    def _render(self) -> None:
        if self._suspended or not self.interactive:
            return
        lines = self._frame_lines()
        if not lines and self._drawn_lines == 0:
            return
        frame = []
        if self._drawn_lines:
            frame.append(f"\033[{self._drawn_lines}A")
        frame.append("\r")
        for line in lines:
            frame.append(f"\033[2K{line}\n")
        frame.append("\033[J")
        self._drawn_lines = len(lines)
        self._write("".join(frame))

    def _write(self, text: str) -> None:
        if not text:
            return
        try:
            sys.stderr.write(text)
            sys.stderr.flush()
        except (OSError, ValueError):
            pass

    def _frame_lines(self) -> list[str]:
        glyphs = self._glyphs()
        width = self.width() - 1
        spinner = glyphs.spinner[self._spinner_frame % len(glyphs.spinner)]
        lines: list[str] = []
        transfers = [task for task in self._tasks if task.is_transfer]
        for task in self._tasks:
            if task.is_transfer:
                continue
            elapsed = format_duration(task.elapsed)
            indent = "  " if task.parent is not None else ""
            marker = f"{glyphs.branch} " if task.parent is not None else ""
            left = f"{indent}{marker}{spinner} {task.label}"
            padding = max(1, width - len(left) - len(elapsed))
            line = (
                self._paint(_DIM, f"{indent}{marker}")
                + self._paint(_CYAN, spinner)
                + " "
                + (self._paint(_BOLD, task.label) if task.parent is None else task.label)
                + " " * padding
                + self._paint(_DIM, elapsed)
            )
            lines.append(_fit(line, width))
        for index, task in enumerate(transfers):
            if index == _MAX_TRANSFER_ROWS:
                remaining = len(transfers) - index
                lines.append(_fit(self._paint(_DIM, f"  {glyphs.branch} {glyphs.ellipsis} {remaining} more"), width))
                break
            lines.append(_fit(self._transfer_row(task, glyphs, width), width))
        return lines

    def _transfer_row(self, task: Task, glyphs: _Glyphs, width: int) -> str:
        # Size the name column so the bar and byte counts always fit; wider
        # terminals give long names more room.
        name_width = min(60, max(_MIN_TRANSFER_NAME_WIDTH, width - 42))
        # The bar already says "downloading"; keep the row to the subject.
        label = task.label[len("Downloading "):] if task.label.startswith("Downloading ") else task.label
        name = _truncate(label, name_width, glyphs.ellipsis)
        prefix = f"  {glyphs.branch} {name:<{name_width}} "
        if task.total:
            percent = task.percent
            detail = f"{format_bytes(task.current)}/{format_bytes(task.total)} ({percent}%)"
        else:
            percent = 0
            detail = format_bytes(task.current)
        rate = task.current / task.elapsed if task.elapsed > 0.5 else 0
        if rate > 0:
            speed = f" {format_bytes(int(rate))}/s"
            # The transfer rate is a nicety: show it only where it fits.
            if len(prefix) + _BAR_WIDTH + 1 + len(detail) + len(speed) <= width:
                detail += speed
        filled = percent * _BAR_WIDTH // 100
        bar = self._paint(_bar_color(), glyphs.bar_filled * filled) + self._paint(_DIM, glyphs.bar_empty * (_BAR_WIDTH - filled))
        return self._paint(_DIM, f"  {glyphs.branch} ") + f"{name:<{name_width}} " + bar + " " + self._paint(_DIM, detail)


def _bar_color() -> str:
    term = os.environ.get("TERM", "")
    if os.environ.get("COLORTERM") or "256color" in term:
        return "\033[38;5;141m"
    return "\033[35m"


def _truncate(value: str, max_width: int, ellipsis: str) -> str:
    if len(value) <= max_width:
        return value
    if max_width <= len(ellipsis):
        return value[:max_width]
    head = (max_width - len(ellipsis)) // 2
    tail = max_width - len(ellipsis) - head
    return value[:head] + ellipsis + value[-tail:]


def _fit(line: str, width: int) -> str:
    """Clamp a possibly colored line so the live region never wraps."""
    result: list[str] = []
    visible = 0
    index = 0
    while index < len(line):
        char = line[index]
        if char == "\033":
            end = line.find("m", index)
            if end < 0:
                break
            result.append(line[index : end + 1])
            index = end + 1
            continue
        if visible < width:
            result.append(char)
            visible += 1
        index += 1
    return "".join(result)


def _enable_windows_vt() -> bool:
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        handle = kernel32.GetStdHandle(-12)  # STD_ERROR_HANDLE
        mode = ctypes.c_uint32()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        return bool(kernel32.SetConsoleMode(handle, mode.value | 0x0004))  # ENABLE_VIRTUAL_TERMINAL_PROCESSING
    except Exception:
        return False


_console = Console()


def console() -> Console:
    """Return the process-wide progress console."""
    return _console
