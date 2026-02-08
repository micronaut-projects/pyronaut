/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.python.cli;

/**
 * Utility holder for CLI mode flags.
 *
 * <p>Provides a global flag to control plain (non-ANSI) output rendering.</p>
 */
public final class CliMode {

    private static volatile boolean plain;

    private CliMode() {
    }

    /**
     * Set whether CLI should run in plain mode (no ANSI/interactive UI).
     *
     * @param value true to enable plain mode, false to disable
     */
    public static void setPlain(boolean value) {
        plain = value;
    }

    /**
     * Query whether CLI is running in plain mode.
     *
     * @return true if plain mode is enabled, false otherwise
     */
    public static boolean isPlain() {
        return plain;
    }
}
