package io.micronaut.pyronaut.testresources;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestResourcesConsoleFilterTest {

    @Test
    void acceptsErrors() {
        assertEquals(FilterReply.ACCEPT, new TestResourcesConsoleFilter().decide(event(Level.ERROR, "boom")));
    }

    @Test
    void acceptsImagePullNotifications() {
        assertEquals(
            FilterReply.ACCEPT,
            new TestResourcesConsoleFilter().decide(event(Level.INFO, "Pulling docker image: testcontainers/ryuk:0.13.0"))
        );
    }

    @Test
    void acceptsContainerCreateNotifications() {
        assertEquals(
            FilterReply.ACCEPT,
            new TestResourcesConsoleFilter().decide(event(Level.INFO, "Creating container for image: testcontainers/ryuk:0.13.0"))
        );
    }

    @Test
    void acceptsContainerStartedNotifications() {
        assertEquals(
            FilterReply.ACCEPT,
            new TestResourcesConsoleFilter().decide(event(Level.INFO, "Container testcontainers/ryuk:0.13.0 started in PT0.24592S"))
        );
    }

    @Test
    void deniesRegularInfoNoise() {
        assertEquals(FilterReply.DENY, new TestResourcesConsoleFilter().decide(event(Level.INFO, "Connected to docker")));
    }

    private static LoggingEvent event(Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setMessage(message);
        return event;
    }
}
