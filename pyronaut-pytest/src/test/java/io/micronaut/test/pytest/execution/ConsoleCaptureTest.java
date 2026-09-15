package io.micronaut.test.pytest.execution;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConsoleCaptureTest {

    @Test
    void capturesAndDrainsStandardStreamsUntilClosed() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        assertNull(ConsoleCapture.drainActive("stdout"));
        try (ConsoleCapture capture = ConsoleCapture.install()) {
            assertSame(capture, ConsoleCapture.install());
            assertSame(out, capture.originalOut());
            System.out.println("\u001B[36mapplication\u001B[0;39m log line");
            System.err.print("warning");
            assertEquals("application log line" + System.lineSeparator(), ConsoleCapture.drainActive("stdout"));
            assertEquals("warning", capture.drain("stderr"));
            assertEquals("", capture.drain("stdout"));
            assertNull(ConsoleCapture.drainActive("stderr"));
        }
        assertSame(out, System.out);
        assertSame(err, System.err);
        assertNull(ConsoleCapture.active());
    }

    @Test
    void stashesBootstrapOutputAsSessionOutput() {
        try (ConsoleCapture capture = ConsoleCapture.install()) {
            System.out.print("test session starts");
            ConsoleCapture.stashSessionIfActive();
            System.out.print("inside test");
            assertEquals("inside test", capture.drain("stdout"));
            System.out.print("after tests");
            assertEquals("test session startsafter tests", capture.drainSession("stdout"));
            assertEquals("", capture.drainSession("stdout"));
        }
    }
}
