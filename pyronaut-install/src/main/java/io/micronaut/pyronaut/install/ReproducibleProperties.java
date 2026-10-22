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
package io.micronaut.pyronaut.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Writes {@link Properties} files without the timestamp comment {@link Properties#store} always emits, so that
 * repeated installs produce byte-identical output.
 */
final class ReproducibleProperties {
    private ReproducibleProperties() {
    }

    static void write(Properties properties, Path file, String comment) throws IOException {
        Files.writeString(file, render(properties, comment), StandardCharsets.ISO_8859_1);
    }

    static String render(Properties properties, String comment) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            // The stream form escapes non-ASCII characters, and since JDK 18 writes entries in key order.
            // Without comments, the first line is always the date.
            properties.store(output, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String entries = output.toString(StandardCharsets.ISO_8859_1).lines()
            .skip(1)
            .map(line -> line + "\n")
            .collect(Collectors.joining());
        return comment == null ? entries : "#" + comment + "\n" + entries;
    }
}
