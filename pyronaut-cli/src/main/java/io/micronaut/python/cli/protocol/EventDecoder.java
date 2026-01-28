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

import java.io.ByteArrayInputStream;
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

        public long readTestRunStarted() throws IOException {
            return in.readLong();
        }

        public long[] readTestRunFinished() throws IOException {
            long passed = in.readLong();
            long failed = in.readLong();
            long skipped = in.readLong();
            long running = in.readLong();
            long pending = in.readLong();
            return new long[] { passed, failed, skipped, running, pending };
        }

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

        public int readTestNodeStarted() throws IOException {
            return in.readUnsignedShort();
        }

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

        public TestLog readTestLog() throws IOException {
            int id = in.readUnsignedShort();
            int len = in.readUnsignedShort();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new TestLog(id, new String(buf, StandardCharsets.UTF_8));
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
         * Legacy full test result snapshot reader.
         * Binary format:
         * - u16 classCount
         *   For each class:
         *     - u16 classNameLen, bytes (UTF-8)
         *     - u8 classStatus
         *     - u16 methodCount
         *       For each method:
         *         - u16 methodNameLen, bytes (UTF-8)
         *         - u16 displayNameLen, bytes (UTF-8)
         *         - u8 status
         *         - u16 messageLen, bytes (UTF-8, may be 0)
         */
        public TestResult readTestResult() throws IOException {
            byte[] buf = new byte[payloadLen];
            in.readFully(buf);
            var bin = new DataInputStream(new ByteArrayInputStream(buf));
            int classCount = bin.readUnsignedShort();
            var classes = new ArrayList<TestClassResult>(classCount);
            for (int i = 0; i < classCount; i++) {
                int cnLen = bin.readUnsignedShort();
                byte[] cnBuf = new byte[cnLen];
                bin.readFully(cnBuf);
                String className = new String(cnBuf, StandardCharsets.UTF_8);
                byte classStatus = bin.readByte();
                int methodCount = bin.readUnsignedShort();
                var methods = new ArrayList<TestMethodResult>(methodCount);
                for (int j = 0; j < methodCount; j++) {
                    int mnLen = bin.readUnsignedShort();
                    byte[] mnBuf = new byte[mnLen];
                    bin.readFully(mnBuf);
                    String methodName = new String(mnBuf, StandardCharsets.UTF_8);
                    int dnLen = bin.readUnsignedShort();
                    byte[] dnBuf = new byte[dnLen];
                    bin.readFully(dnBuf);
                    String displayName = new String(dnBuf, StandardCharsets.UTF_8);
                    byte status = bin.readByte();
                    int msgLen = bin.readUnsignedShort();
                    String message = "";
                    if (msgLen > 0) {
                        byte[] msgBuf = new byte[msgLen];
                        bin.readFully(msgBuf);
                        message = new String(msgBuf, StandardCharsets.UTF_8);
                    }
                    methods.add(new TestMethodResult(methodName, displayName, status, message));
                }
                classes.add(new TestClassResult(className, classStatus, methods));
            }
            return new TestResult(classes);
        }

        public void skip() throws IOException {
            in.skipBytes(payloadLen);
        }

        public record LogEvent(byte level, String message) {}
        public record TestNode(int id, int parentId, byte kind, String name, String displayName) {}
        public record TestNodeFinished(int id, byte status, String message) {}
        public record TestLog(int id, String message) {}
        public record TestResult(List<TestClassResult> classes) {}
        public record TestClassResult(String name, byte status, List<TestMethodResult> methods) {}
        public record TestMethodResult(String name, String displayName, byte status, String message) {}
    }
}
