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
package io.micronaut.pyronaut.config.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies {@code -Dname=value} options that the Pyronaut CLI moved into a file.
 *
 * <p>On Windows, classpath-valued system properties can exceed the command
 * line limit. The CLI then writes them, one option per line, to the file named
 * by {@link #PROPERTY}. Native launchers call {@link #apply()} before reading
 * any other system property.</p>
 */
public final class JvmOptionsFile {
    public static final String PROPERTY = "pyronaut.jvm.options.file";

    private JvmOptionsFile() {
    }

    /**
     * Sets the system properties listed in the file named by {@link #PROPERTY}, if any.
     */
    public static void apply() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
                if (!line.startsWith("-D")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator > 2) {
                    System.setProperty(line.substring(2, separator), line.substring(separator + 1));
                } else if (separator < 0 && line.length() > 2) {
                    System.setProperty(line.substring(2), "");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + PROPERTY + " " + file, e);
        }
    }
}
