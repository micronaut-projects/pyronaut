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

import java.io.Console;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Terminal capability detection shared by the Pyronaut tools.
 */
public final class Terminal {
    /**
     * Environment variable carrying the orchestrating CLI's start time in
     * epoch milliseconds so every delegated tool stamps progress lines on one
     * timeline.
     */
    public static final String PROGRESS_EPOCH_ENV = "PYRONAUT_PROGRESS_EPOCH_MS";
    static final int DEFAULT_WIDTH = 80;

    private Terminal() {
    }

    /**
     * @return whether the standard streams are attached to a terminal capable of cursor movement
     */
    public static boolean isInteractive() {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return false;
        }
        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }

    /**
     * Resolve whether ANSI colours should be emitted.
     *
     * @param colorMode {@code auto}, {@code always} or {@code never}
     * @param tty whether the output is a terminal
     * @return whether colour sequences should be written
     */
    public static boolean colorEnabled(String colorMode, boolean tty) {
        String mode = colorMode == null ? "auto" : colorMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "always" -> true;
            case "never" -> false;
            default -> tty && System.getenv("NO_COLOR") == null;
        };
    }

    /**
     * @return whether the error stream can safely carry Unicode glyphs
     */
    public static boolean unicodeSupported() {
        String override = System.getProperty("pyronaut.progress.unicode");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        String encoding = System.getProperty("stderr.encoding",
            System.getProperty("native.encoding", Charset.defaultCharset().name()));
        return encoding.toUpperCase(Locale.ROOT).replace("-", "").contains("UTF8");
    }

    /**
     * @return whether the terminal advertises 256-colour support
     */
    public static boolean supports256Colors() {
        String term = System.getenv("TERM");
        return System.getenv("COLORTERM") != null || (term != null && term.contains("256color"));
    }

    /**
     * The terminal width. Java cannot query it, so the orchestrating CLI
     * passes {@code COLUMNS}; otherwise rows are sized for 80 columns.
     *
     * @return the width in columns
     */
    public static int width() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int value = Integer.parseInt(columns.trim());
                if (value >= 40) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default width
            }
        }
        return DEFAULT_WIDTH;
    }

    /**
     * Wall-clock start of the overall command: the orchestrating CLI's start
     * when it passed one through the environment, else now.
     *
     * @return the epoch in milliseconds
     */
    public static long epochMillis() {
        long now = System.currentTimeMillis();
        String epoch = System.getenv(PROGRESS_EPOCH_ENV);
        if (epoch != null) {
            try {
                long value = Long.parseLong(epoch.trim());
                if (now - value >= 0 && now - value < 24L * 60 * 60 * 1000) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the local epoch
            }
        }
        return now;
    }

    /**
     * @param nanos a duration
     * @return {@code 3.2s} below a minute, {@code 1m 05s} above
     */
    public static String formatDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        long whole = (long) seconds;
        return String.format(Locale.ROOT, "%dm %02ds", whole / 60, whole % 60);
    }

    /**
     * @param bytes a byte count
     * @return a decimal-unit size such as {@code 2.7MB}
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1000) {
            return bytes + "B";
        }
        double value = bytes / 1000.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
    }

    /**
     * Wrap a path in an OSC 8 hyperlink so capable terminals make it clickable.
     *
     * @param label the visible text
     * @param uri the link target
     * @return the escaped link
     */
    public static String link(String label, String uri) {
        return "\u001b]8;;" + uri + "\u001b\\" + label + "\u001b]8;;\u001b\\";
    }
}
