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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Filters noisy checksum warning stack traces from Maven Resolver while retaining the warning line itself.
 */
final class ChecksumWarningFilter implements AutoCloseable {
    private static final String CHECKSUM_WARNING_TOKEN =
        "WARN org.eclipse.aether.internal.impl.WarnChecksumPolicy - Could not validate integrity of download";
    private static final String CHECKSUM_EXCEPTION_TOKEN =
        "org.eclipse.aether.transfer.ChecksumFailureException:";

    private final PrintStream originalErr;
    private final PrintStream filteredErr;

    private ChecksumWarningFilter(PrintStream originalErr) {
        this.originalErr = originalErr;
        this.filteredErr = new PrintStream(new FilteringOutputStream(originalErr), true, StandardCharsets.UTF_8);
    }

    static ChecksumWarningFilter install() {
        ChecksumWarningFilter filter = new ChecksumWarningFilter(System.err);
        System.setErr(filter.filteredErr);
        return filter;
    }

    @Override
    public void close() {
        filteredErr.flush();
        System.setErr(originalErr);
    }

    static String filterLine(String line, boolean[] suppressChecksumTrace) {
        if (suppressChecksumTrace[0]) {
            if (isChecksumTraceLine(line)) {
                return null;
            }
            suppressChecksumTrace[0] = false;
        }
        if (line.contains(CHECKSUM_WARNING_TOKEN)) {
            suppressChecksumTrace[0] = true;
        }
        return line;
    }

    private static boolean isChecksumTraceLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith(CHECKSUM_EXCEPTION_TOKEN)
            || trimmed.startsWith("at ")
            || trimmed.startsWith("... ")
            || trimmed.startsWith("Caused by: ")
            || trimmed.startsWith("Suppressed: ");
    }

    private static final class FilteringOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final StringBuilder lineBuffer = new StringBuilder();
        private final boolean[] suppressChecksumTrace = new boolean[1];

        private FilteringOutputStream(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            char c = (char) b;
            lineBuffer.append(c);
            if (c == '\n') {
                flushLine();
            }
        }

        @Override
        public void flush() throws IOException {
            if (!lineBuffer.isEmpty()) {
                flushLine();
            }
            delegate.flush();
        }

        private void flushLine() {
            String line = lineBuffer.toString();
            lineBuffer.setLength(0);
            String filtered = filterLine(stripTrailingNewline(line), suppressChecksumTrace);
            if (filtered != null) {
                delegate.print(filtered);
                if (line.endsWith("\n")) {
                    delegate.print('\n');
                }
            }
        }

        private static String stripTrailingNewline(String line) {
            if (line.endsWith("\n")) {
                return line.substring(0, line.length() - 1);
            }
            return line;
        }
    }
}
