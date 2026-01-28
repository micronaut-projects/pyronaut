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

    /**
     * Send an application started event.
     *
     * @param timestamp the start timestamp
     * @throws IOException if an I/O error occurs
     */
    public void sendAppStarted(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.APP_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    /**
     * Send an application stopped event.
     *
     * @param timestamp the stop timestamp
     * @throws IOException if an I/O error occurs
     */
    public void sendAppStopped(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.APP_STOPPED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    /**
     * Send the server URI event.
     *
     * @param uri the server URI
     * @throws IOException if an I/O error occurs
     */
    public void sendServerUri(String uri) throws IOException {
        byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
        out.writeByte(ProtocolConstants.SERVER_URI);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(uriBytes.length); // payload length
        out.write(uriBytes);
    }

    /**
     * Send an application log event.
     *
     * @param level   the log level
     * @param message the log message
     * @throws IOException if an I/O error occurs
     */
    public void sendAppLog(byte level, String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(ProtocolConstants.APP_LOG);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(1 + 2 + msgBytes.length); // level(1) + len(2) + msg
        out.writeByte(level);
        out.writeShort(msgBytes.length);
        out.write(msgBytes);
    }

    /**
     * Send a test run started event.
     *
     * @param timestamp the start timestamp
     * @throws IOException if an I/O error occurs
     */
    public void sendTestRunStarted(long timestamp) throws IOException {
        out.writeByte(ProtocolConstants.TEST_RUN_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(8); // payload length: long (8 bytes)
        out.writeLong(timestamp);
    }

    /**
     * Send a test run finished event.
     *
     * @param passed  number of passed tests
     * @param failed  number of failed tests
     * @param skipped number of skipped tests
     * @param running number of running tests
     * @param pending number of pending tests
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Send a test node event.
     *
     * @param id          the test node ID
     * @param parentId    the parent test node ID
     * @param kind        the test node kind
     * @param name        the test node name
     * @param displayName the test node display name
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Send a test node started event.
     *
     * @param id the test node ID
     * @throws IOException if an I/O error occurs
     */
    public void sendTestNodeStarted(int id) throws IOException {
        out.writeByte(ProtocolConstants.TEST_NODE_STARTED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(2);
        out.writeShort(id & 0xFFFF);
        out.flush();
    }

    /**
     * Send a test node finished event.
     *
     * @param id      the test node ID
     * @param status  the test node status
     * @param message the test node message
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Send a test log event.
     *
     * @param id      the test node ID
     * @param message the log message
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Send an endpoint list event.
     *
     * @param endpoints the list of endpoints
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Flush the output stream.
     *
     * @throws IOException if an I/O error occurs
     */
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Send an application start failed event.
     *
     * @throws IOException if an I/O error occurs
     */
    public void sendAppStartFailed() throws IOException {
        out.writeByte(ProtocolConstants.APP_START_FAILED);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeShort(0);
        out.flush();
    }

}
