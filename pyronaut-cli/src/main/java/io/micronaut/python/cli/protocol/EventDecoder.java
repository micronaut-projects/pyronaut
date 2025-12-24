package io.micronaut.python.cli.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Decoder for receiving events from the socket.
 */
public final class EventDecoder {
    private final DataInputStream in;

    public EventDecoder(DataInputStream in) {
        this.in = in;
    }

    /**
     * Reads the next event and returns an EventHandler that can be used to process it.
     */
    public EventHandler readEvent() throws IOException {
        int eventType = in.readUnsignedByte();
        int version = in.readUnsignedByte();
        int payloadLen = in.readUnsignedShort();
        return new EventHandler(eventType, version, payloadLen, in);
    }

    /**
     * Handler for a specific event.
     */
    public static final class EventHandler {
        private final int eventType;
        private final int version;
        private final int payloadLen;
        private final DataInputStream in;

        EventHandler(int eventType, int version, int payloadLen, DataInputStream in) {
            this.eventType = eventType;
            this.version = version;
            this.payloadLen = payloadLen;
            this.in = in;
        }

        public int getEventType() {
            return eventType;
        }

        public int getVersion() {
            return version;
        }

        public long readAppStarted() throws IOException {
            return in.readLong();
        }

        public long readAppStopped() throws IOException {
            return in.readLong();
        }

        public String readServerUri() throws IOException {
            byte[] buf = new byte[payloadLen];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }

        public LogEvent readAppLog() throws IOException {
            byte level = in.readByte();
            int msgLen = in.readUnsignedShort();
            byte[] msgBuf = new byte[msgLen];
            in.readFully(msgBuf);
            String message = new String(msgBuf, StandardCharsets.UTF_8);
            return new LogEvent(level, message);
        }

        public void skip() throws IOException {
            in.skipBytes(payloadLen);
        }

        public record LogEvent(byte level, String message) {}
    }
}
