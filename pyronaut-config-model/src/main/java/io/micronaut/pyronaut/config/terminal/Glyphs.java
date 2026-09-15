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

/**
 * Symbol set used by the live progress region, with an ASCII fallback for
 * terminals that cannot render Unicode.
 */
public final class Glyphs {
    public static final Glyphs UNICODE = new Glyphs(
        new String[] {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"},
        "█", "░", "✓", "✗", "!", "•", "–", "└", "·", "…");
    public static final Glyphs ASCII = new Glyphs(
        new String[] {"-", "\\", "|", "/"},
        "#", "-", "+", "x", "!", "*", "-", "L", "-", "...");

    private final String[] spinner;
    private final String barFilled;
    private final String barEmpty;
    private final String check;
    private final String cross;
    private final String warning;
    private final String bullet;
    private final String skipped;
    private final String branch;
    private final String separator;
    private final String ellipsis;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Glyphs(String[] spinner, String barFilled, String barEmpty, String check, String cross, String warning,
                   String bullet, String skipped, String branch, String separator, String ellipsis) {
        this.spinner = spinner;
        this.barFilled = barFilled;
        this.barEmpty = barEmpty;
        this.check = check;
        this.cross = cross;
        this.warning = warning;
        this.bullet = bullet;
        this.skipped = skipped;
        this.branch = branch;
        this.separator = separator;
        this.ellipsis = ellipsis;
    }

    public String[] spinner() {
        return spinner;
    }

    public String barFilled() {
        return barFilled;
    }

    public String barEmpty() {
        return barEmpty;
    }

    public String check() {
        return check;
    }

    public String cross() {
        return cross;
    }

    public String warning() {
        return warning;
    }

    public String bullet() {
        return bullet;
    }

    public String skipped() {
        return skipped;
    }

    public String branch() {
        return branch;
    }

    public String separator() {
        return separator;
    }

    public String ellipsis() {
        return ellipsis;
    }

    /**
     * @param unicode whether Unicode glyphs can be rendered
     * @return the matching glyph set
     */
    public static Glyphs of(boolean unicode) {
        return unicode ? UNICODE : ASCII;
    }

    /**
     * Shorten a value to fit a column, eliding the middle.
     *
     * @param value the text
     * @param maxWidth the column width
     * @return the truncated text
     */
    public String truncate(String value, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (value.length() <= maxWidth) {
            return value;
        }
        if (maxWidth <= ellipsis.length()) {
            return value.substring(0, maxWidth);
        }
        int head = (maxWidth - ellipsis.length()) / 2;
        int tail = maxWidth - ellipsis.length() - head;
        return value.substring(0, head) + ellipsis + value.substring(value.length() - tail);
    }
}
