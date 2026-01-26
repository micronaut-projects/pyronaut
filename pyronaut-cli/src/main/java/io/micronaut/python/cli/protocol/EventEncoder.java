package io.micronaut.python.cli.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    public void sendTestRunStarted(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.TEST_RUN_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    public void sendTestRunFinished(long passed, long failed, long skipped, long running, long pending) throws IOException {
        out.writeByte(ProtocolConstants.TEST_RUN_FINISHED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(10); // five unsigned shorts
        out.writeLong(passed);
        out.writeLong(failed);
        out.writeLong(skipped);
        out.writeLong(running);
        out.writeLong(pending);
    }

    public void sendTestNode(int id, int parentId, byte kind, String name, String displayName) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] displayBytes = displayName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + 2 + 1 + 2 + nameBytes.length + 2 + displayBytes.length;

        out.writeByte(ProtocolConstants.TEST_NODE);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(payloadLen);

        out.writeShort(id & 0xFFFF);
        out.writeShort(parentId & 0xFFFF);
        out.writeByte(kind & 0xFF);
        out.writeShort(nameBytes.length);
        out.write(nameBytes);
        out.writeShort(displayBytes.length);
        out.write(displayBytes);
        out.flush();
    }

    public void sendTestNodeStarted(int id) throws IOException {
        out.writeByte(ProtocolConstants.TEST_NODE_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(2);
        out.writeShort(id & 0xFFFF);
        out.flush();
    }

    public void sendTestNodeFinished(int id, byte status, String message) throws IOException {
        byte[] msgBytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + 1 + 2 + msgBytes.length;

        out.writeByte(ProtocolConstants.TEST_NODE_FINISHED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(payloadLen);

        out.writeShort(id & 0xFFFF);
        out.writeByte(status & 0xFF);
        out.writeShort(msgBytes.length);
        if (msgBytes.length > 0) {
            out.write(msgBytes);
        }
        out.flush();
    }

    public void sendTestLog(int id, String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + 2 + msgBytes.length;

        out.writeByte(ProtocolConstants.TEST_LOG);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(payloadLen);

        out.writeShort(id & 0xFFFF);
        out.writeShort(msgBytes.length);
        out.write(msgBytes);
        out.flush();
    }

     public void sendEndpointList(List<String> endpoints) throws IOException {
        int count = endpoints == null ? 0 : endpoints.size();
        int payload = 2;

         if (count > 0) {
             for (var s : endpoints) {
                 var b = s.getBytes(StandardCharsets.UTF_8);
                 payload += 2 + b.length;
             }
         }
         out.writeByte(ProtocolConstants.ENDPOINT_LIST);
         out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
         out.writeShort(payload);
         out.writeShort(count & 0xFFFF);
         if (count > 0) {
             for (var s : endpoints) {
                 var b = s.getBytes(StandardCharsets.UTF_8);
                 out.writeShort(b.length & 0xFFFF);
                 out.write(b);
             }
         }
     }
 
     public void flush() throws IOException {
         out.flush();
     }
 }
