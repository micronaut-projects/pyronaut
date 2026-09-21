package io.micronaut.pyronaut.config.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
