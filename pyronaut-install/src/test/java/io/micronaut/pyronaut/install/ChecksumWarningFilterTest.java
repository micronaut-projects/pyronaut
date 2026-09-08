package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumWarningFilterTest {

    @Test
    void preservesNonAsciiOutput() {
        String captured = capture(() -> {
            System.err.println("█████ résumé /pfad/mit/umläuten");
            System.err.print("partial ✓");
        });

        assertEquals("█████ résumé /pfad/mit/umläuten" + System.lineSeparator() + "partial ✓", captured);
    }

    @Test
    void suppressesChecksumStackTraceButKeepsWarningLine() {
        String captured = capture(() -> {
            System.err.println("WARN org.eclipse.aether.internal.impl.WarnChecksumPolicy - Could not validate integrity of download from x");
            System.err.println("org.eclipse.aether.transfer.ChecksumFailureException: boom");
            System.err.println("\tat some.Class.method(Class.java:1)");
            System.err.println("next line ✓");
        });

        assertEquals(
            "WARN org.eclipse.aether.internal.impl.WarnChecksumPolicy - Could not validate integrity of download from x"
                + System.lineSeparator() + "next line ✓" + System.lineSeparator(),
            captured
        );
    }

    private static String capture(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            try (ChecksumWarningFilter ignored = ChecksumWarningFilter.install()) {
                action.run();
            }
        } finally {
            System.setErr(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
