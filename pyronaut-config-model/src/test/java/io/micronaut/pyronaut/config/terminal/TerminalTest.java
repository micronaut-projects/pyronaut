package io.micronaut.pyronaut.config.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalTest {

    @Test
    void notifyLaunchedIsANoOpWithoutTheHandshake() {
        // No PYRONAUT_LAUNCH_HANDSHAKE in the test environment: must return
        // immediately and stay idempotent.
        Terminal.notifyLaunched();
        Terminal.notifyLaunched();
        assertEquals("PYRONAUT_LAUNCH_HANDSHAKE", Terminal.LAUNCH_HANDSHAKE_ENV);
    }

    @Test
    void formatsDurationsAndSizes() {
        assertEquals("0.5s", Terminal.formatDuration(500_000_000L));
        assertEquals("1m 05s", Terminal.formatDuration(65_000_000_000L));
        assertEquals("2.7MB", Terminal.formatBytes(2_700_000L));
    }

    @Test
    void repaintsOnlyTerminalsThatNameTheirType() {
        assertTrue(Terminal.supportsCursorMovement(true, "xterm-256color", false));
        assertFalse(Terminal.supportsCursorMovement(false, "xterm-256color", false));
        assertFalse(Terminal.supportsCursorMovement(true, "dumb", false));
        // A build sandbox's pseudo-terminal without TERM gets plain lines.
        assertFalse(Terminal.supportsCursorMovement(true, null, false));
        assertFalse(Terminal.supportsCursorMovement(true, "", false));
        // Windows consoles do not set TERM.
        assertTrue(Terminal.supportsCursorMovement(true, null, true));
    }
}
