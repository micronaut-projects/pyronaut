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

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerErrorCaptureTest {
    private static final Logger SERVER_LOG = LoggerFactory.getLogger("io.micronaut.http.server.RouteExecutor");

    @AfterEach
    void stopRecording() {
        ServerErrorCapture.end();
    }

    @Test
    void recordsErrorsLoggedWithAnExceptionWhileATestRuns() {
        ServerErrorCapture.begin();
        IllegalStateException cause = new IllegalStateException("database unavailable");
        SERVER_LOG.error("Unexpected error occurred: {}", cause.getMessage(), cause);
        SERVER_LOG.error("no exception attached");
        SERVER_LOG.warn("not an error", new IllegalArgumentException("warning"));
        LoggerFactory.getLogger("io.micronaut.test.pytest.execution.JUnitPytestTestListener")
            .error("the test framework's own failure", new IllegalArgumentException("framework"));

        String rendered = ServerErrorCapture.drain();

        assertTrue(rendered.startsWith("java.lang.IllegalStateException: database unavailable"), rendered);
        assertTrue(rendered.contains("ServerErrorCaptureTest.recordsErrorsLoggedWithAnExceptionWhileATestRuns"), rendered);
        assertFalse(rendered.contains("warning"), rendered);
        assertFalse(rendered.contains("framework"), rendered);
        assertEquals("", ServerErrorCapture.drain(), "drain takes what was recorded");
    }

    @Test
    void recordsNothingOutsideATestAndForgetsThePreviousTest() {
        ServerErrorCapture.begin();
        SERVER_LOG.error("first test", new IllegalStateException("from the first test"));
        ServerErrorCapture.end();
        SERVER_LOG.error("between tests", new IllegalStateException("between tests"));
        ServerErrorCapture.begin();

        assertEquals("", ServerErrorCapture.drain());
    }

    @Test
    void keepsRecordingAfterTheApplicationReconfiguresLogging() throws Exception {
        ServerErrorCapture.begin();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        try {
            SERVER_LOG.error("after reset", new IllegalStateException("logged after the reset"));

            assertTrue(ServerErrorCapture.drain().contains("logged after the reset"));
        } finally {
            // Restore the test logging configuration.
            new ch.qos.logback.classic.util.ContextInitializer((LoggerContext) LoggerFactory.getILoggerFactory())
                .autoConfig();
        }
    }

    @Test
    void rendersCausesAndLimitsHostFrames() {
        RuntimeException wrapper = new RuntimeException("request failed", new IllegalStateException("root cause"));
        StackTraceElement[] frames = new StackTraceElement[30];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("example.Service", "call" + i, "Service.java", i);
        }
        wrapper.setStackTrace(frames);

        String rendered = ServerErrorCapture.render(List.of(new ServerErrorCapture.Captured("server", wrapper)));

        assertTrue(rendered.contains("java.lang.RuntimeException: request failed"), rendered);
        assertTrue(rendered.contains("\tat example.Service.call11(Service.java:11)"), rendered);
        assertFalse(rendered.contains("call12("), rendered);
        assertTrue(rendered.contains("\n\t...\nCaused by: java.lang.IllegalStateException: root cause"), rendered);
    }
}
