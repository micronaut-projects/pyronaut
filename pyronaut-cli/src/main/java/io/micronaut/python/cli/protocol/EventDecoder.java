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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
     *
     * @return a handler for reading the event payload
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

        /**
         * Get the event type.
         *
         * @return the event type
         */
        public int getEventType() {
            return eventType;
        }

        /**
         * Get the protocol version.
         *
         * @return the protocol version
         */
        public int getVersion() {
            return version;
        }

        /**
         * Read application started timestamp.
         * @return the timestamp
         */
        public long readAppStarted() throws IOException {
            return in.readLong();
        }

        /**
         * Read application stopped timestamp.
         * @return the timestamp
         */
        public long readAppStopped() throws IOException {
            return in.readLong();
        }

        /**
         * Read test run started timestamp.
         * @return the timestamp
         */
        public long readTestRunStarted() throws IOException {
            return in.readLong();
        }

        /**
         * Read test run finished summary.
         *
         * @return array of long: passed, failed, skipped, running, pending
         */
        public long[] readTestRunFinished() throws IOException {
            long passed = in.readLong();
            long failed = in.readLong();
            long skipped = in.readLong();
            long running = in.readLong();
            long pending = in.readLong();
            return new long[]{passed, failed, skipped, running, pending};
        }

        /**
         * Read test node definition.
         *
         * @return the test node
         */
        public TestNode readTestNode() throws IOException {
            int id = in.readUnsignedShort();
            int parentId = in.readUnsignedShort();
            byte kind = in.readByte();
            int nameLen = in.readUnsignedShort();
            byte[] nameBuf = new byte[nameLen];
            in.readFully(nameBuf);
            int dispLen = in.readUnsignedShort();
            byte[] dispBuf = new byte[dispLen];
            in.readFully(dispBuf);
            String name = new String(nameBuf, StandardCharsets.UTF_8);
            String display = new String(dispBuf, StandardCharsets.UTF_8);
            return new TestNode(id, parentId, kind, name, display);
        }

        /**
         * Read test node started event.
         *
         * @return the test node ID
         */
        public int readTestNodeStarted() throws IOException {
            return in.readUnsignedShort();
        }

        /**
         * Read test node finished event.
         *
         * @return the test node finished info
         */
        public TestNodeFinished readTestNodeFinished() throws IOException {
            int id = in.readUnsignedShort();
            byte status = in.readByte();
            int msgLen = in.readUnsignedShort();
            if (msgLen > 0) {
                byte[] buf = new byte[msgLen];
                in.readFully(buf);
                String message = new String(buf, StandardCharsets.UTF_8);
                return new TestNodeFinished(id, status, message);
            }
            return new TestNodeFinished(id, status, "");
        }

        /**
         * Read test log event.
         *
         * @return the test log
         */
        public TestLog readTestLog() throws IOException {
            int id = in.readUnsignedShort();
            int len = in.readUnsignedShort();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new TestLog(id, new String(buf, StandardCharsets.UTF_8));
        }

        /**
         * Read server URI.
         *
         * @return the server URI
         */
        public String readServerUri() throws IOException {
            byte[] buf = new byte[payloadLen];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }

        /**
         * Read application log event.
         *
         * @return the log event
         */
        public LogEvent readAppLog() throws IOException {
            byte level = in.readByte();
            int msgLen = in.readUnsignedShort();
            byte[] msgBuf = new byte[msgLen];
            in.readFully(msgBuf);
            String message = new String(msgBuf, StandardCharsets.UTF_8);
            return new LogEvent(level, message);
        }

        /**
         * Read endpoint list.
         *
         * @return the list of endpoints
         */
        public List<String> readEndpointList() throws IOException {
            int count = in.readUnsignedShort();
            List<String> eps = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int len = in.readUnsignedShort();
                byte[] buf = new byte[len];
                in.readFully(buf);
                eps.add(new String(buf, StandardCharsets.UTF_8));
            }
            return eps;
        }

        /**
         * Skip the event payload.
         */
        public void skip() throws IOException {
            in.skipBytes(payloadLen);
        }

        /**
         * Log event record.
         * @param level   the log level
         * @param message the log message
         */
        public record LogEvent(byte level, String message) {
        }

        /**
         * Test node record.
         * @param id          the node ID
         * @param parentId    the parent node ID
         * @param kind        the node kind
         * @param name        the node name
         * @param displayName the node display name
         */
        public record TestNode(int id, int parentId, byte kind, String name, String displayName) {
        }

        /**
         * Test node finished record.
         * @param id      the node ID
         * @param status  the node status
         * @param message the finish message
         */
        public record TestNodeFinished(int id, byte status, String message) {
        }

        /**
         * Test log record.
         * @param id      the test node ID
         * @param message the log message
         */
        public record TestLog(int id, String message) {
        }

        /**
         * Test result record.
         * @param classes the list of test class results
         */
        public record TestResult(List<TestClassResult> classes) {
        }

        /**
         * Test class result record.
         * @param name    the class name
         * @param status  the class status
         * @param methods the list of test method results
         */
        public record TestClassResult(String name, byte status, List<TestMethodResult> methods) {
        }

        /**
         * Test method result record.
         * @param name        the method name
         * @param displayName the method display name
         * @param status      the method status
         * @param message     the method message
         */
        public record TestMethodResult(String name, String displayName, byte status, String message) {
        }
    }
}
