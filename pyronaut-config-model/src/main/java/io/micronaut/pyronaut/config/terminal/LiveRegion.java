/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.config.terminal;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A repainting block of rows at the bottom of a terminal stream.
 *
 * <p>The region is repainted by a background ticker at a fixed rate so
 * spinners and elapsed times keep moving between events. Permanent output is
 * written above the region with {@link #printAbove(String)}; the region is
 * cleared on {@link #close()}. Callers describe the current rows through a
 * {@link Frame} and keep their own state under their own lock; the region
 * serialises rendering internally.
 *
 * <p>When the stream is not interactive the region draws nothing; callers
 * print plain lines themselves.
 */
public final class LiveRegion implements AutoCloseable {
    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String CYAN = "\033[36m";
    private static final long FRAME_INTERVAL_MILLIS = 80L;

    private final PrintStream output;
    private final boolean interactive;
    private final boolean color;
    private final Glyphs glyphs;
    private final String barColor;
    private final Frame frame;
    private final int width;
    private final long epochNanos;
    private final Object lock = new Object();
    private final Thread ticker;
    private volatile boolean running;
    private int drawnLines;
    private int spinnerFrame;
    private PrintStream previousOut;
    private PrintStream previousErr;

    /**
     * @param output the stream to draw on
     * @param interactive whether the stream is a terminal
     * @param color whether ANSI colours may be used
     * @param unicode whether Unicode glyphs may be used
     * @param frame the row supplier
     */
    public LiveRegion(PrintStream output, boolean interactive, boolean color, boolean unicode, Frame frame) {
        this(output, new TerminalInfo(interactive, color, unicode, Terminal.width(), Terminal.epochMillis()), frame);
    }

    /**
     * @param output the stream to draw on
     * @param terminal the terminal's capabilities
     * @param frame the row supplier
     */
    public LiveRegion(PrintStream output, TerminalInfo terminal, Frame frame) {
        this.output = output;
        this.interactive = terminal.interactive();
        this.color = interactive && terminal.color();
        this.glyphs = Glyphs.of(terminal.unicode());
        this.barColor = Terminal.supports256Colors() ? "\033[38;5;141m" : "\033[35m";
        this.frame = frame;
        this.width = terminal.width();
        this.epochNanos = terminal.epochNanos();
        if (interactive) {
            running = true;
            ticker = new Thread(this::tick, "pyronaut-progress");
            ticker.setDaemon(true);
            ticker.start();
        } else {
            ticker = null;
        }
    }

    public boolean interactive() {
        return interactive;
    }

    /**
     * Route {@link System#out} and {@link System#err} through the region so
     * that stray output (logging, library warnings) lands above the live rows
     * instead of tearing them. Restored by {@link #close()}. A no-op when the
     * region is not interactive.
     *
     * @return this region
     */
    public LiveRegion captureStandardStreams() {
        if (!interactive) {
            return this;
        }
        synchronized (lock) {
            if (previousOut != null) {
                return this;
            }
            previousOut = System.out;
            previousErr = System.err;
            System.setOut(new PrintStream(new LineForwarder(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new LineForwarder(), true, StandardCharsets.UTF_8));
        }
        return this;
    }

    public Glyphs glyphs() {
        return glyphs;
    }

    public PrintStream output() {
        return output;
    }

    /**
     * @param tone an ANSI colour constant, or {@code null}
     * @param text the text
     * @return the text wrapped in the colour when colour is enabled
     */
    public String paint(String tone, String text) {
        if (!color || tone == null || text.isEmpty()) {
            return text;
        }
        return tone + text + RESET;
    }

    /**
     * @param percent completion percentage
     * @param width bar width in cells
     * @return a coloured progress bar
     */
    public String bar(int percent, int width) {
        int filled = Math.max(0, Math.min(width, percent * width / 100));
        return paint(barColor, glyphs.barFilled().repeat(filled)) + paint(DIM, glyphs.barEmpty().repeat(width - filled));
    }

    /**
     * Format a permanent status line: {@code [elapsed] ✓ message (took)}.
     *
     * @param tone colour for the glyph
     * @param glyph the status glyph
     * @param message the message
     * @param tookNanos duration to append, or negative for none
     * @return the formatted line
     */
    public String stamp(String tone, String glyph, String message, long tookNanos) {
        return stamp(tone, glyph, message, tookNanos < 0 ? null : "(" + Terminal.formatDuration(tookNanos) + ")");
    }

    /**
     * Format a permanent status line with an arbitrary dim suffix.
     *
     * @param tone colour for the glyph
     * @param glyph the status glyph
     * @param message the message
     * @param suffix dim trailing text, or {@code null}
     * @return the formatted line
     */
    public String stamp(String tone, String glyph, String message, String suffix) {
        StringBuilder line = new StringBuilder();
        line.append(paint(DIM, "[" + Terminal.formatDuration(System.nanoTime() - epochNanos) + "]")).append(' ');
        if (glyph != null && !glyph.isEmpty()) {
            line.append(paint(tone, glyph)).append(' ');
        }
        line.append(message);
        if (suffix != null && !suffix.isEmpty()) {
            line.append(' ').append(paint(DIM, suffix));
        }
        return line.toString();
    }

    /**
     * Write a permanent line above the region and repaint the region below it.
     *
     * @param line the line, without a trailing newline
     */
    public void printAbove(String line) {
        synchronized (lock) {
            StringBuilder out = new StringBuilder(clearSequence());
            out.append(line).append('\n');
            output.print(out);
            refreshLocked();
        }
    }

    /**
     * Repaint the region now instead of waiting for the ticker.
     */
    public void refresh() {
        synchronized (lock) {
            refreshLocked();
        }
    }

    @Override
    public void close() {
        running = false;
        if (ticker != null) {
            ticker.interrupt();
            try {
                ticker.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (!interactive) {
                return;
            }
            if (previousOut != null) {
                System.out.flush();
                System.err.flush();
                System.setOut(previousOut);
                System.setErr(previousErr);
                previousOut = null;
                previousErr = null;
            }
            output.print(clearSequence());
            output.flush();
        }
    }

    private void tick() {
        while (running) {
            try {
                Thread.sleep(FRAME_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (!running) {
                    return;
                }
                spinnerFrame++;
                refreshLocked();
            }
        }
    }

    private String clearSequence() {
        if (drawnLines == 0) {
            return "";
        }
        String sequence = "\033[" + drawnLines + "A\r\033[J";
        drawnLines = 0;
        return sequence;
    }

    private void refreshLocked() {
        if (!interactive) {
            return;
        }
        int usable = width - 1;
        List<String> lines = frame.lines(this, spinnerFrame, usable);
        if (lines.isEmpty() && drawnLines == 0) {
            return;
        }
        StringBuilder out = new StringBuilder();
        if (drawnLines > 0) {
            out.append("\033[").append(drawnLines).append('A');
        }
        out.append('\r');
        for (String line : lines) {
            out.append("\033[2K").append(fit(line, usable)).append('\n');
        }
        out.append("\033[J");
        drawnLines = lines.size();
        output.print(out);
        output.flush();
    }

    /**
     * Clamp a possibly coloured line to a width so the region never wraps; a
     * wrapped row breaks the cursor arithmetic used to repaint.
     *
     * @param line the line
     * @param width the visible width
     * @return the clamped line
     */
    public static String fit(String line, int width) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (ch == '\033') {
                int end = line.indexOf('m', index);
                if (end < 0) {
                    break;
                }
                result.append(line, index, end + 1);
                index = end + 1;
                continue;
            }
            if (visible < width) {
                result.append(ch);
                visible++;
            }
            index++;
        }
        return result.toString();
    }

    /**
     * @param line a possibly coloured line
     * @return its width without escape sequences
     */
    public static int visibleLength(String line) {
        return line.replaceAll("\033\\[[0-9;]*m", "").length();
    }

    /**
     * Lay out a header row: {@code spinner label · detail} with the elapsed
     * time right-aligned.
     *
     * @param spinnerFrame the spinner frame
     * @param label the bold label
     * @param detail dim detail after the separator, or {@code null}
     * @param elapsedNanos elapsed time to right-align
     * @param width the usable width
     * @return the row
     */
    public String headerRow(int spinnerFrame, String label, String detail, long elapsedNanos, int width) {
        return headerRow("", spinnerFrame, label, detail, elapsedNanos, width);
    }

    /**
     * Lay out a header row with a leading indent.
     *
     * @param indent text before the spinner, such as a branch glyph
     * @param spinnerFrame the spinner frame
     * @param label the bold label
     * @param detail dim detail after the separator, or {@code null}
     * @param elapsedNanos elapsed time to right-align
     * @param width the usable width
     * @return the row
     */
    public String headerRow(String indent, int spinnerFrame, String label, String detail, long elapsedNanos, int width) {
        String spinner = glyphs.spinner()[Math.floorMod(spinnerFrame, glyphs.spinner().length)];
        String elapsed = Terminal.formatDuration(elapsedNanos);
        String detailText = detail == null || detail.isEmpty() ? "" : " " + glyphs.separator() + " " + detail;
        int padding = Math.max(1, width - (indent.length() + 2 + label.length() + detailText.length()) - elapsed.length());
        return paint(DIM, indent) + paint(CYAN, spinner) + " " + paint(BOLD, label)
            + paint(DIM, detailText) + " ".repeat(padding) + paint(DIM, elapsed);
    }

    /**
     * Buffers bytes until a newline and prints each complete line above the
     * region.
     */
    private final class LineForwarder extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                printAbove(pending.toString(StandardCharsets.UTF_8).replace("\r", ""));
                pending.reset();
            } else {
                pending.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                write(bytes[index]);
            }
        }

        @Override
        public synchronized void flush() {
            if (pending.size() > 0) {
                printAbove(pending.toString(StandardCharsets.UTF_8).replace("\r", ""));
                pending.reset();
            }
        }
    }

    /**
     * Supplies the rows currently shown in the region.
     */
    @FunctionalInterface
    public interface Frame {
        /**
         * @param region the region, for glyphs and painting helpers
         * @param spinnerFrame the current spinner frame index
         * @param width the usable width in columns
         * @return the rows to draw, top to bottom
         */
        List<String> lines(LiveRegion region, int spinnerFrame, int width);
    }
}
