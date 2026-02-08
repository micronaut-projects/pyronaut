package io.micronaut.python.cli.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StreamsCaptureTest {

    private PrintStream origOut;
    private PrintStream origErr;

    @BeforeEach
    void install() {
        origOut = System.out;
        origErr = System.err;
        StreamsCapture.getInstance().install();
    }

    @AfterEach
    void restore() {
        StreamsCapture.getInstance().restore();
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    void tailIndexAdvancesAndReadFromReturnsNewLines() {
        var cap = StreamsCapture.getInstance();
        long start = cap.tailIndex();
        System.out.println("line1");
        System.out.println("line2");
        System.err.println("err1");
        List<String> lines = cap.readFrom(start);
        assertEquals(3, lines.size(), "Should return all lines since start");
        assertTrue(lines.get(0).contains("line1"));
        assertTrue(lines.get(1).contains("line2"));
        assertTrue(lines.get(2).contains("[STDERR] err1"));
    }

    @Test
    void readFromFutureTailIsEmpty() {
        var cap = StreamsCapture.getInstance();
        long future = cap.tailIndex() + 1000;
        List<String> lines = cap.readFrom(future);
        assertTrue(lines.isEmpty());
    }

    @Test
    void readFromBeforeBaseReturnsEverything() {
        var cap = StreamsCapture.getInstance();
        System.out.println("a");
        System.out.println("b");
        System.err.println("c");
        List<String> all = cap.readFrom(0);
        assertTrue(all.size() >= 3);
        assertTrue(all.get(all.size() - 3).contains("a"));
        assertTrue(all.get(all.size() - 2).contains("b"));
        assertTrue(all.get(all.size() - 1).contains("[STDERR] c"));
    }
}
