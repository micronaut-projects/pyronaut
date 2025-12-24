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
package io.micronaut.python.cli.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream that captures stdout/stderr and sends them to the UI controller.
 * Prevents application logs from interfering with the TUI display.
 */
final class StreamCaptureOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final boolean isError;
    private final UiController controller;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    StreamCaptureOutputStream(OutputStream delegate, boolean isError, UiController controller) {
        this.delegate = delegate;
        this.isError = isError;
        this.controller = controller;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        buffer.write(b);

        // Check for newline to send complete lines to UI
        if (b == '\n') {
            sendToUI();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        buffer.write(b, off, len);

        // Check for newlines in the written data
        for (int i = off; i < off + len; i++) {
            if (b[i] == '\n') {
                sendToUI();
                break;
            }
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
        // Don't flush buffer here as we want to accumulate complete lines
    }

    @Override
    public void close() throws IOException {
        // Send any remaining content
        if (buffer.size() > 0) {
            sendToUI();
        }
        delegate.close();
    }

    private void sendToUI() {
        String line = buffer.toString().trim();
        buffer.reset();

        if (!line.isEmpty()) {
            // Send to UI as notification
            controller.notify(
                (isError ? "[STDERR] " : "[STDOUT] ") + line,
                isError ? UiModel.Severity.ERROR : UiModel.Severity.INFO
            );
        }
    }
}
