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

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Robust, non-global, tee-based stdout/stderr capture for the TUI.
 */
public final class StreamsCapture {

    private static final StreamsCapture INSTANCE = new StreamsCapture();

    private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> stderrQueue = new LinkedBlockingQueue<>();
    private PrintStream origOut;
    private PrintStream origErr;
    private boolean installed = false;

    private StreamsCapture() {

    }

    public static StreamsCapture getInstance() {
        return INSTANCE;
    }

    public static void installGlobal() {
        INSTANCE.install();
    }

    public void install() {
        if (installed) {
            return;
        }
        installed = true;
        origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(new TeeOutputStream(origOut, stdoutQueue, false), true));
        System.setErr(new PrintStream(new TeeOutputStream(origErr, stderrQueue, true), true));
    }

    public void restore() {
        if (!installed) {
            return;
        }
        if (origOut != null) {
            System.setOut(origOut);
        }
        if (origErr != null) {
            System.setErr(origErr);
        }
        installed = false;
    }

    public BlockingQueue<String> stdoutQueue() {
        return stdoutQueue;
    }

    public BlockingQueue<String> stderrQueue() {
        return stderrQueue;
    }

    private static class TeeOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final BlockingQueue<String> queue;
        private final boolean error;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        TeeOutputStream(OutputStream delegate, BlockingQueue<String> queue, boolean error) {
            this.delegate = delegate;
            this.queue = queue;
            this.error = error;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            buf.write(b);
            if (b == '\n') flushBuffer();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                buf.write(b[i]);
                if (b[i] == '\n') flushBuffer();
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            // don't flush buffer
        }

        @Override
        public void close() throws IOException {
            flushBuffer();
            delegate.close();
        }

        private void flushBuffer() {
            if (buf.size() == 0) return;
            String s = buf.toString();
            buf.reset();
            if (!s.isEmpty() && !s.equals("\n") && !s.equals("\r\n")) {
                queue.offer((error ? "[STDERR] " : "") + s.stripTrailing());
            }
        }
    }
}
