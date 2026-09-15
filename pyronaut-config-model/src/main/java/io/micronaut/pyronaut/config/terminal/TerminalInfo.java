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

import java.util.Locale;

/**
 * The capabilities of the terminal a tool renders on.
 *
 * <p>A tool that runs on the user's terminal detects them; a tool that
 * renders for another process (the compiler daemon drawing for its client)
 * receives them as a spec string produced by {@link #describe()}.
 *
 * @param interactive whether cursor movement can be used
 * @param color whether ANSI colours may be emitted
 * @param unicode whether Unicode glyphs may be emitted
 * @param width the terminal width in columns
 * @param epochMillis wall-clock start of the overall command, for elapsed stamps
 */
public record TerminalInfo(boolean interactive, boolean color, boolean unicode, int width, long epochMillis) {

    /**
     * Detect the capabilities of the current process's terminal.
     *
     * @param colorMode {@code auto}, {@code always} or {@code never}
     * @return the capabilities
     */
    public static TerminalInfo detect(String colorMode) {
        boolean tty = Terminal.isInteractive();
        return new TerminalInfo(tty, Terminal.colorEnabled(colorMode, tty), tty && Terminal.unicodeSupported(),
            Terminal.width(), Terminal.epochMillis());
    }

    /**
     * @return a plain (non-interactive) description
     */
    public static TerminalInfo plain() {
        return new TerminalInfo(false, false, false, Terminal.width(), Terminal.epochMillis());
    }

    /**
     * @return this description with interactivity (and colour/unicode) disabled
     */
    public TerminalInfo asPlain() {
        return new TerminalInfo(false, false, false, width, epochMillis);
    }

    /**
     * @return a spec string understood by {@link #parse(String)}
     */
    public String describe() {
        return (interactive ? "interactive," : "") + (color ? "color," : "") + (unicode ? "unicode," : "")
            + "width=" + width + ",epoch=" + epochMillis;
    }

    /**
     * @param spec a spec string from {@link #describe()}
     * @return the described capabilities; malformed parts fall back to plain defaults
     */
    public static TerminalInfo parse(String spec) {
        boolean interactive = false;
        boolean color = false;
        boolean unicode = false;
        int width = Terminal.DEFAULT_WIDTH;
        long epoch = Terminal.epochMillis();
        for (String part : spec.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            try {
                if (token.equals("interactive")) {
                    interactive = true;
                } else if (token.equals("color")) {
                    color = true;
                } else if (token.equals("unicode")) {
                    unicode = true;
                } else if (token.startsWith("width=")) {
                    width = Math.max(40, Integer.parseInt(token.substring("width=".length())));
                } else if (token.startsWith("epoch=")) {
                    epoch = Long.parseLong(token.substring("epoch=".length()));
                }
            } catch (NumberFormatException ignored) {
                // keep the default for a malformed value
            }
        }
        return new TerminalInfo(interactive, color, unicode, width, epoch);
    }

    /**
     * @return the epoch converted into the {@link System#nanoTime()} domain
     */
    public long epochNanos() {
        long elapsedMillis = System.currentTimeMillis() - epochMillis;
        if (elapsedMillis < 0 || elapsedMillis > 24L * 60 * 60 * 1000) {
            return System.nanoTime();
        }
        return System.nanoTime() - elapsedMillis * 1_000_000L;
    }
}
