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
package io.micronaut.test.pytest.execution;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Redirects {@link System#out} and {@link System#err} into buffers while
 * tests run so framework and application console output can be attributed
 * to the test that produced it and stored in the test reports instead of
 * interleaving with the runner's progress display.
 *
 * <p>The pytest plugin drains the buffers at the end of every test phase and
 * records the text as report sections, from which pytest's JUnit XML,
 * Pyronaut's HTML report and the event log are already produced. Java test
 * listeners drain them when a JUnit test finishes.
 */
public final class ConsoleCapture implements AutoCloseable {
    private static volatile ConsoleCapture active;
    // Colour and hyperlink sequences carry nothing worth keeping in a report.
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u001B]*\u001B\\\\");

    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final Buffer stdout = new Buffer();
    private final Buffer stderr = new Buffer();
    private final StringBuilder sessionStdout = new StringBuilder();
    private final StringBuilder sessionStderr = new StringBuilder();

    private ConsoleCapture(PrintStream originalOut, PrintStream originalErr) {
        this.originalOut = originalOut;
        this.originalErr = originalErr;
    }

    /**
     * Start capturing the standard streams. Only one capture is active at a
     * time; a second install returns the active one.
     *
     * @return the active capture
     */
    public static synchronized ConsoleCapture install() {
        ConsoleCapture current = active;
        if (current != null) {
            return current;
        }
        ConsoleCapture capture = new ConsoleCapture(System.out, System.err);
        System.setOut(new PrintStream(capture.stdout, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capture.stderr, true, StandardCharsets.UTF_8));
        active = capture;
        return capture;
    }

    /**
     * @return the capture in effect, or {@code null} when output is not captured
     */
    public static ConsoleCapture active() {
        return active;
    }

    /**
     * Take the text captured on a stream since the last drain.
     *
     * @param stream {@code stdout} or {@code stderr}
     * @return the captured text, empty when nothing was written
     */
    public String drain(String stream) {
        return ANSI.matcher("stderr".equals(stream) ? stderr.drain() : stdout.drain()).replaceAll("");
    }

    /**
     * Move whatever is pending on both streams to the session-level output,
     * so it is not attributed to the next test. Called when the test session
     * has finished its own bootstrap and is about to run the first test.
     */
    public void stashSession() {
        synchronized (sessionStdout) {
            sessionStdout.append(drain("stdout"));
            sessionStderr.append(drain("stderr"));
        }
    }

    /**
     * Take the session-level output (stashed text plus whatever is pending).
     *
     * @param stream {@code stdout} or {@code stderr}
     * @return the text, empty when nothing was written
     */
    public String drainSession(String stream) {
        synchronized (sessionStdout) {
            StringBuilder builder = "stderr".equals(stream) ? sessionStderr : sessionStdout;
            builder.append(drain(stream));
            String text = builder.toString();
            builder.setLength(0);
            return text;
        }
    }

    /**
     * Stash pending output as session output if a capture is active.
     */
    public static void stashSessionIfActive() {
        ConsoleCapture current = active;
        if (current != null) {
            current.stashSession();
        }
    }

    /**
     * Take the text captured on a stream since the last drain, if a capture
     * is active.
     *
     * @param stream {@code stdout} or {@code stderr}
     * @return the captured text, or {@code null} when nothing is captured
     */
    public static String drainActive(String stream) {
        ConsoleCapture current = active;
        if (current == null) {
            return null;
        }
        String text = current.drain(stream);
        return text.isEmpty() ? null : text;
    }

    /**
     * @return the stream that was {@link System#out} before capture began
     */
    public PrintStream originalOut() {
        return originalOut;
    }

    /**
     * @return the stream that was {@link System#err} before capture began
     */
    public PrintStream originalErr() {
        return originalErr;
    }

    /**
     * Restore the original streams.
     */
    @Override
    public void close() {
        synchronized (ConsoleCapture.class) {
            if (active != this) {
                return;
            }
            System.out.flush();
            System.err.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
            active = null;
        }
    }

    private static final class Buffer extends ByteArrayOutputStream {
        synchronized String drain() {
            String text = toString(StandardCharsets.UTF_8);
            reset();
            return text;
        }

        @Override
        public synchronized void write(int b) {
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            super.write(b, off, len);
        }
    }
}
