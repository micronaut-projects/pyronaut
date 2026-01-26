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
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Robust, non-global, tee-based stdout/stderr capture for the TUI.
 */
public final class StreamsCapture {

    private static final StreamsCapture INSTANCE = new StreamsCapture();

    private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> stderrQueue = new LinkedBlockingQueue<>();
    // Ring buffer for all lines with sequence numbers for windowing
    private static final int MAX_RING = 100_000;
    private final Object ringLock = new Object();
    private final Deque<String> ring = new ArrayDeque<>();
    // baseSeq = sequence number immediately BEFORE the first element in ring
    private long baseSeq = 0;
    private final AtomicLong nextSeq = new AtomicLong(0);

    private PrintStream origOut;
    private PrintStream origErr;
    private final AtomicBoolean installed = new AtomicBoolean();
    private OutputNotificationThread stdOutThread;
    private OutputNotificationThread stdErrThread;

    private StreamsCapture() {

    }

    public static StreamsCapture getInstance() {
        return INSTANCE;
    }

    public static void installGlobal() {
        INSTANCE.install();
    }

    public void install() {
        if (installed.get()) {
            return;
        }
        installed.set(true);
        origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(new TeeOutputStream(OutputStream.nullOutputStream(), stdoutQueue, false), true));
        System.setErr(new PrintStream(new TeeOutputStream(OutputStream.nullOutputStream(), stderrQueue, true), true));
        stdOutThread = new OutputNotificationThread("Standard Output", stdoutQueue);
        stdErrThread = new OutputNotificationThread("Standard Error", stderrQueue);
        stdOutThread.start();
        stdErrThread.start();
    }

    public void restore() {
        if (!installed.get()) {
            return;
        }
        if (origOut != null) {
            System.setOut(origOut);
        }
        if (origErr != null) {
            System.setErr(origErr);
        }
        try {
            stdOutThread.interrupt();
        } finally {
            try {
                stdErrThread.interrupt();
            } finally {
                installed.set(false);
            }
        }
    }

    // Ring buffer API
    public long tailIndex() {
        return nextSeq.get();
    }

    public List<String> readFrom(long startSeq) {
        synchronized (ringLock) {
            var currentTail = nextSeq.get();
            return readRangeLocked(startSeq, currentTail);
        }
    }

    public List<String> readRange(long startSeq, long endExclusive) {
        synchronized (ringLock) {
            return readRangeLocked(startSeq, endExclusive);
        }
    }

    private List<String> readRangeLocked(long startSeq, long endExclusive) {
        if (endExclusive <= startSeq) {
            return List.of();
        }
        var currentTail = nextSeq.get();
        var end = Math.min(endExclusive, currentTail);
        var startOffset = Math.max(0, startSeq - baseSeq);
        var endOffset = Math.max(0, end - baseSeq);
        int startIndex = (int) Math.min(Integer.MAX_VALUE, startOffset);
        int endIndex = (int) Math.min(Integer.MAX_VALUE, endOffset);
        var out = new ArrayList<String>(Math.max(0, endIndex - startIndex));
        int i = 0;
        for (var s : ring) {
            if (i >= endIndex) {
                break;
            }
            if (i >= startIndex) {
                out.add(s);
            }
            i++;
        }
        return out;
    }

    private void appendToRing(String line) {
        synchronized (ringLock) {
            ring.addLast(line);
            var seq = nextSeq.incrementAndGet();
            if (ring.size() > MAX_RING) {
                ring.removeFirst();
            }
            // After increment, nextSeq == seq. If ring size is n, the first element is at sequence (seq - n + 1)
            // Therefore baseSeq (seq before first element) = (seq - n)
            baseSeq = seq - ring.size();
        }
    }

    private class TeeOutputStream extends OutputStream {
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
            if (b == '\n') {
                flushBuffer();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            for (var i = off; i < off + len; i++) {
                buf.write(b[i]);
                if (b[i] == '\n') {
                    flushBuffer();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            // ensure partial line is pushed to queues and ring on flush
            flushBuffer();
        }

        @Override
        public void close() throws IOException {
            flushBuffer();
            delegate.close();
        }

        private void flushBuffer() {
            if (buf.size() == 0) {
                return;
            }
            var s = buf.toString();
            buf.reset();
            if (!s.isEmpty() && !s.equals("\n") && !s.equals("\r\n")) {
                var line = (error ? "[STDERR] " : "") + s.stripTrailing();
                if (!queue.offer(line)) {
                    throw new IllegalStateException();
                }
                appendToRing(line);
            }
        }
    }

    public OutputNotificationThread getStdOutThread() {
        return stdOutThread;
    }

    public OutputNotificationThread getStdErrThread() {
        return stdErrThread;
    }

    public static class OutputNotificationThread extends Thread {
        private final BlockingQueue<String> queue;
        private final List<Consumer<? super String>> consumers = new ArrayList<>();

        private OutputNotificationThread(String name, BlockingQueue<String> queue) {
            this.queue = queue;
            setName(name);
            setDaemon(true);
        }

        public void addConsumer(Consumer<? super String> consumer) {
            consumers.add(consumer);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    var message = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        List<Exception> errors = new ArrayList<>(consumers.size());
                        for (Consumer<? super String> consumer : consumers) {
                            try  {
                                consumer.accept(message);
                            } catch (Exception ex) {
                                errors.add(ex);
                            }
                        }
                        if (errors.size() == 1) {
                            throw new RuntimeException(errors.getFirst());
                        } else if (errors.size() > 1) {
                            var runtimeException = new RuntimeException("Multiple errors while consuming output messages");
                            for (Exception error : errors) {
                                runtimeException.addSuppressed(error);
                            }
                            throw runtimeException;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
