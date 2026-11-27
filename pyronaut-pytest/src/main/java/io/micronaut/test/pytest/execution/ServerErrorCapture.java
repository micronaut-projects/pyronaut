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

import io.micronaut.core.annotation.Internal;
import org.graalvm.polyglot.PolyglotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Records the exceptions the application logs at error level while a test
 * runs, so a failing test can show the server-side exception behind it (an
 * HTTP 500 from a route that raised, for example) instead of only the
 * client-side assertion.
 *
 * <p>The exceptions are observed through the logging backend, which sees
 * them regardless of where the log output is routed. Only Logback, the
 * backend Pyronaut applications use, is supported; with any other backend
 * nothing is recorded.
 */
@Internal
public final class ServerErrorCapture {
    private static final Logger LOG = LoggerFactory.getLogger(ServerErrorCapture.class);
    private static final String LOGBACK_CONTEXT = "ch.qos.logback.classic.LoggerContext";
    private static final int MAX_EXCEPTIONS = 5;
    private static final int MAX_HOST_FRAMES = 12;
    private static final Pattern INTERNAL_FRAME = Pattern.compile(
        "^(java\\.|jdk\\.|sun\\.|com\\.oracle\\.|org\\.graalvm\\.|io\\.netty\\.|reactor\\.|io\\.micronaut\\.core\\.propagation\\.|io\\.micronaut\\.core\\.execution\\.).*"
    );

    private static final Object LOCK = new Object();
    private static final List<Captured> CAPTURED = new ArrayList<>();
    private static volatile boolean recording;
    private static volatile boolean installed;

    private ServerErrorCapture() {
    }

    /**
     * Start recording for a new test, discarding anything recorded before.
     */
    public static void begin() {
        install();
        synchronized (LOCK) {
            CAPTURED.clear();
            recording = true;
        }
    }

    /**
     * Stop recording.
     */
    public static void end() {
        synchronized (LOCK) {
            recording = false;
            CAPTURED.clear();
        }
    }

    /**
     * Take the exceptions recorded since {@link #begin()} or the last drain.
     *
     * @return the exceptions rendered for a failure report, separated by blank lines;
     * empty when none were logged
     */
    public static String drain() {
        List<Captured> captured;
        synchronized (LOCK) {
            captured = List.copyOf(CAPTURED);
            CAPTURED.clear();
        }
        return render(captured);
    }

    /**
     * Record an exception logged by the application.
     *
     * @param loggerName the name of the logger that logged it
     * @param throwable the exception
     */
    static void recordException(String loggerName, Throwable throwable) {
        if (!recording || throwable == null || isOwnLogger(loggerName)) {
            return;
        }
        synchronized (LOCK) {
            if (!recording || CAPTURED.size() >= MAX_EXCEPTIONS) {
                return;
            }
            for (Captured existing : CAPTURED) {
                if (existing.throwable() == throwable) {
                    return;
                }
            }
            CAPTURED.add(new Captured(loggerName, throwable));
        }
    }

    private static boolean isOwnLogger(String loggerName) {
        return loggerName != null && loggerName.startsWith("io.micronaut.test.pytest.");
    }

    private static void install() {
        if (installed) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
            try {
                if (LOGBACK_CONTEXT.equals(LoggerFactory.getILoggerFactory().getClass().getName())) {
                    LogbackServerErrorHook.install(LoggerFactory.getILoggerFactory());
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("Unable to observe application errors for test failure reports", e);
            }
        }
    }

    static String render(List<Captured> captured) {
        if (captured.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(1024);
        for (Captured entry : captured) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            appendThrowable(builder, entry.throwable(), "", Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return builder.toString();
    }

    private static void appendThrowable(StringBuilder builder, Throwable throwable, String prefix, Set<Throwable> seen) {
        if (!seen.add(throwable)) {
            return;
        }
        if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(prefix).append(describe(throwable));
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        boolean guestFrames = throwable instanceof PolyglotException && hasGuestFrames(stackTrace);
        // For an exception raised in Python, its traceback is what matters;
        // the host frames are the framework calling into the route.
        List<StackTraceElement> frames = new ArrayList<>(stackTrace.length);
        for (StackTraceElement element : stackTrace) {
            boolean shown = guestFrames
                ? isGuestFrame(element)
                : !INTERNAL_FRAME.matcher(element.getClassName()).matches();
            if (shown) {
                frames.add(element);
            }
        }
        int limit = guestFrames ? frames.size() : Math.min(frames.size(), MAX_HOST_FRAMES);
        for (int i = 0; i < limit; i++) {
            builder.append("\n\tat ").append(frames.get(i));
        }
        if (frames.size() > limit) {
            builder.append("\n\t...");
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            appendThrowable(builder, cause, "Caused by: ", seen);
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable instanceof PolyglotException) {
            String message = throwable.getMessage();
            return message == null || message.isBlank() ? throwable.toString() : message;
        }
        return throwable.toString();
    }

    private static boolean hasGuestFrames(StackTraceElement[] stackTrace) {
        for (StackTraceElement element : stackTrace) {
            if (isGuestFrame(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuestFrame(StackTraceElement element) {
        // Polyglot exceptions report guest frames with a "<language>" class name.
        return element.getClassName().startsWith("<");
    }

    record Captured(String loggerName, Throwable throwable) {
    }
}
