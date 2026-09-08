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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
        // Raw UTF-8 bytes are buffered until a full line is available so that
        // multi-byte characters (progress bars, non-ASCII paths) are decoded
        // intact instead of byte by byte.
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private final boolean[] suppressChecksumTrace = new boolean[1];

        private FilteringOutputStream(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            lineBuffer.write(b);
            if (b == '\n') {
                flushLine();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            int start = offset;
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (bytes[i] == '\n') {
                    lineBuffer.write(bytes, start, i - start + 1);
                    flushLine();
                    start = i + 1;
                }
            }
            if (start < end) {
                lineBuffer.write(bytes, start, end - start);
            }
        }

        @Override
        public void flush() throws IOException {
            if (lineBuffer.size() > 0) {
                flushLine();
            }
            delegate.flush();
        }

        private void flushLine() {
            String line = lineBuffer.toString(StandardCharsets.UTF_8);
            lineBuffer.reset();
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
