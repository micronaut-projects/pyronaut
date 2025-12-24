package io.micronaut.python.cli.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Encoder for sending events over the socket.
 */
public final class EventEncoder {
    private final DataOutputStream out;

    public EventEncoder(DataOutputStream out) {
        this.out = out;
    }

    public void sendAppStarted(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.APP_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    public void sendAppStopped(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.APP_STOPPED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    public void sendServerUri(String uri) throws IOException {
        byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
        out.writeByte(ProtocolConstants.SERVER_URI);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(uriBytes.length); // payload length
        out.write(uriBytes);
    }

    public void sendAppLog(byte level, String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(ProtocolConstants.APP_LOG);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(1 + 2 + msgBytes.length); // level(1) + len(2) + msg
        out.writeByte(level);
        out.writeShort(msgBytes.length);
        out.write(msgBytes);
    }

    public void flush() throws IOException {
        out.flush();
    }
}
