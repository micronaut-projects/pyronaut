import io
import os
import re
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

from pyronaut_cli_v2 import progress  # noqa: E402


class FakeTty(io.StringIO):
    encoding = "utf-8"

    def isatty(self):
        return True


class Screen:
    """Minimal VT emulator covering the sequences the live region uses."""

    def __init__(self, cols):
        self.lines = [""]
        self.row = 0
        self.col = 0
        self.cols = cols
        self.wrapped = False

    def feed(self, text):
        index = 0
        while index < len(text):
            char = text[index]
            if char == "\x1b":
                match = re.match(r"\x1b\[([0-9;?]*)([A-Za-z])", text[index:])
                if match is None:
                    index += 1
                    continue
                argument, command = match.groups()
                if command == "A":
                    self.row = max(0, self.row - int(argument or 1))
                elif command == "K":
                    self.lines[self.row] = ""
                elif command == "J":
                    self.lines[self.row] = self.lines[self.row][: self.col]
                    del self.lines[self.row + 1 :]
                index += len(match.group(0))
                continue
            if char == "\r":
                self.col = 0
            elif char == "\n":
                self.row += 1
                self.col = 0
                if self.row == len(self.lines):
                    self.lines.append("")
            else:
                line = self.lines[self.row].ljust(self.col)
                self.lines[self.row] = line[: self.col] + char + line[self.col + 1 :]
                self.col += 1
                if self.col > self.cols:
                    self.wrapped = True
            index += 1

    def text(self):
        return "\n".join(self.lines)


def render(output, cols=80):
    screen = Screen(cols)
    screen.feed(output)
    return screen


class ProgressTest(unittest.TestCase):
    def setUp(self):
        self.console = progress.Console()

    def test_non_interactive_output_is_plain_and_deterministic(self):
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            with self.console.step("Locating GraalVM JDK (25+)"):
                with self.console.transfer("Downloading GraalVM JDK", 1000) as transfer:
                    for done in (50, 150, 400, 999, 1000):
                        transfer.update(done)
            self.console.warn("careful")
            self.console.success("all done")

        output = stderr.getvalue()
        self.assertEqual(
            [
                "Locating GraalVM JDK (25+)...",
                "Downloading GraalVM JDK... 0%",
                "Downloading GraalVM JDK... 15%",
                "Downloading GraalVM JDK... 40%",
                "Downloading GraalVM JDK... 99%",
                "Downloading GraalVM JDK... 100%",
                "WARNING: careful",
                "all done",
            ],
            output.splitlines(),
        )
        self.assertNotIn("\r", output)
        self.assertNotIn("\x1b", output)

    def test_non_interactive_transfer_reports_completion_once(self):
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            with self.console.transfer("Downloading asset", 10) as transfer:
                transfer.update(10)
        self.assertEqual(["Downloading asset... 0%", "Downloading asset... 100%"], stderr.getvalue().splitlines())

    def test_off_mode_silences_steps_but_keeps_messages(self):
        self.console.configure("off")
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            with self.console.step("Provisioning native launchers"):
                with self.console.transfer("Downloading launcher", 100) as transfer:
                    transfer.update(100)
            self.console.warn("still shown")
        self.assertEqual(["WARNING: still shown"], stderr.getvalue().splitlines())

    def test_interactive_live_region_collapses_into_summary_lines(self):
        stderr = FakeTty()
        env = {"TERM": "xterm", "COLUMNS": "80", "NO_COLOR": "1"}
        with patch.dict(os.environ, env, clear=False), redirect_stderr(stderr):
            with self.console.step("Locating GraalVM JDK (25+)", done="GraalVM JDK ready") as step:
                with self.console.transfer("Downloading GraalVM JDK", 312_000_000, done="Downloaded GraalVM JDK") as transfer:
                    transfer.update(124_800_000)
                    self.console._render()
                    mid = render(stderr.getvalue()).text()
                    self.assertIn("Locating GraalVM JDK (25+)", mid)
                    self.assertIn("└ GraalVM JDK", mid)
                    self.assertIn("█████░░░░░░░░░ 124.8MB/312.0MB (40%)", mid)
                self.assertTrue(step.interactive)
                with self.console.step("Unpacking GraalVM JDK", done="Unpacked GraalVM JDK"):
                    self.console._render()
                    nested = render(stderr.getvalue()).text()
                    self.assertRegex(nested, r"  └ . Unpacking GraalVM JDK")
            self.console.success("Pyronaut setup completed")

        output = stderr.getvalue()
        self.assertIn("\x1b[2K", output)
        screen = render(output)
        self.assertFalse(screen.wrapped, screen.text())
        self.assertEqual(
            [
                "✓ Downloaded GraalVM JDK (312.0MB in",
                "✓ Unpacked GraalVM JDK (",
                "✓ GraalVM JDK ready (",
                "✓ Pyronaut setup completed",
            ],
            [re.sub(r"^\[\d+\.\ds\] ", "", line).split("0.")[0].rstrip() for line in screen.lines if line],
        )
        # The live region is gone once every step has finished.
        self.assertNotIn("Locating GraalVM JDK", screen.text())
        self.assertNotIn("\x1b", screen.text())

    def test_interactive_failure_prints_cross_and_clears_region(self):
        stderr = FakeTty()
        env = {"TERM": "xterm", "COLUMNS": "80", "NO_COLOR": "1"}
        with patch.dict(os.environ, env, clear=False), redirect_stderr(stderr):
            with self.assertRaises(RuntimeError):
                with self.console.step("Resolving SDK dependencies"):
                    raise RuntimeError("boom")
            print("boom", file=sys.stderr)

        screen = render(stderr.getvalue())
        lines = [line for line in screen.lines if line]
        self.assertRegex(lines[0], r"^\[\d+\.\ds\] ✗ Resolving SDK dependencies \(\d+\.\ds\)$")
        self.assertEqual("boom", lines[1])
        self.assertFalse(self.console.interactive)

    def test_suspend_clears_region_for_child_process_output(self):
        stderr = FakeTty()
        env = {"TERM": "xterm", "COLUMNS": "80", "NO_COLOR": "1"}
        with patch.dict(os.environ, env, clear=False), redirect_stderr(stderr):
            with self.console.step("Resolving SDK dependencies", done="SDK dependencies resolved"):
                with self.console.suspend():
                    suspended = render(stderr.getvalue()).text()
                    self.assertNotIn("Resolving SDK dependencies", suspended)
                    print("Resolving build dependencies...", file=sys.stderr)
                self.console._render()
                resumed = render(stderr.getvalue()).text()
                self.assertIn("Resolving build dependencies...", resumed)
                self.assertIn("Resolving SDK dependencies", resumed)

        final = render(stderr.getvalue()).lines
        self.assertEqual("Resolving build dependencies...", final[0])
        self.assertRegex(final[1], r"✓ SDK dependencies resolved")

    def test_interactive_rows_never_exceed_terminal_width(self):
        stderr = FakeTty()
        env = {"TERM": "xterm", "COLUMNS": "60", "NO_COLOR": "1"}
        long_label = "Downloading really-long-artifact-name-with-classifier-and-version-9.9.9-SNAPSHOT.tar.gz"
        with patch.dict(os.environ, env, clear=False), redirect_stderr(stderr):
            with self.console.step("A step with an unreasonably long label that would wrap a narrow terminal", persist=False):
                with self.console.transfer(long_label, 987_654_321, persist=False) as transfer:
                    transfer.update(123_456_789)
                    self.console._render()
                    # Live rows are clamped: a wrapped row would corrupt the repaint.
                    screen = render(stderr.getvalue(), cols=60)
                    self.assertFalse(screen.wrapped, screen.text())
                    self.assertEqual(2, len([line for line in screen.lines if line]), screen.text())
                    for line in screen.lines:
                        self.assertLess(len(line), 60, line)
                    self.assertIn("123.5MB/987.7MB (12%)", screen.text())
        self.assertEqual("", render(stderr.getvalue(), cols=60).text().strip())

    def test_colors_and_ascii_fallback(self):
        stderr = FakeTty()
        with patch.dict(os.environ, {"TERM": "xterm-256color", "COLUMNS": "80"}, clear=False), patch.dict(
            os.environ, {}, clear=False
        ), redirect_stderr(stderr):
            os.environ.pop("NO_COLOR", None)
            with self.console.step("Colored"):
                pass
        output = stderr.getvalue()
        self.assertIn("\x1b[32m✓\x1b[0m", output)
        self.assertIn("\x1b[36m", output)

        class AsciiTty(FakeTty):
            encoding = "latin-1"

        stderr = AsciiTty()
        with patch.dict(os.environ, {"TERM": "xterm", "COLUMNS": "80", "NO_COLOR": "1"}, clear=False), redirect_stderr(stderr):
            with self.console.step("Plain"):
                with self.console.transfer("Downloading thing", 100) as transfer:
                    transfer.update(50)
                    self.console._render()
        output = stderr.getvalue()
        self.assertIn("#######-------", output)
        self.assertIn("L thing", output)
        self.assertIn("+ Plain", output)
        self.assertNotIn("✓", output)

    def test_formatting_helpers(self):
        self.assertEqual("0.0s", progress.format_duration(0))
        self.assertEqual("3.2s", progress.format_duration(3.21))
        self.assertEqual("1m 05s", progress.format_duration(65))
        self.assertEqual("999B", progress.format_bytes(999))
        self.assertEqual("1.0KB", progress.format_bytes(1000))
        self.assertEqual("2.7MB", progress.format_bytes(2_700_000))
        self.assertEqual("1.5GB", progress.format_bytes(1_500_000_000))


if __name__ == "__main__":
    unittest.main()
