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
package io.micronaut.python.cli.ui;

import io.micronaut.python.cli.protocol.ProtocolConstants;

import java.io.PrintStream;
import java.util.List;

/**
 * An event sink that outputs events from the console to
 * the provided print streams.
 */
public final class ConsoleEventSink implements ProtocolEventSink {

    private final PrintStream out;
    private final PrintStream err;

    public ConsoleEventSink(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void onAppStarted() {
        out.println("Application started");
    }

    @Override
    public void onAppStopped() {
        out.println("Application stopped");
    }

    @Override
    public void onServerUri(String uri) {
        out.println("Server: " + uri);
    }

    @Override
    public void onEndpointList(List<String> endpoints) {
        if (!endpoints.isEmpty()) {
            out.println("Endpoints:");
            for (var ep : endpoints) {
                out.println(" - " + ep);
            }
        }
    }

    @Override
    public void onAppLog(byte level, String message) {
        String prefix;
        PrintStream ps;
        switch (level) {
            case ProtocolConstants.LOG_DEBUG -> {
                prefix = "[DEBUG] ";
                ps = out;
            }
            case ProtocolConstants.LOG_INFO -> {
                prefix = "[INFO] ";
                ps = out;
            }
            case ProtocolConstants.LOG_WARN -> {
                prefix = "[WARN] ";
                ps = out;
            }
            case ProtocolConstants.LOG_ERROR, ProtocolConstants.LOG_FATAL -> {
                prefix = "[ERROR] ";
                ps = err;
            }
            default -> {
                prefix = "";
                ps = out;
            }
        }
        ps.println(prefix + message);
    }

    @Override
    public void onTestRunStarted() {
        out.println("Test run started");
    }

    @Override
    public void onTestRunFinished(long passed, long failed, long skipped, long running, long pending) {
        out.println("Tests completed: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
    }

    @Override
    public void onTestNode(int id, int parentId, byte kind, String name, String displayName) {
        if (kind == 1) {
            out.println("[TEST-CLASS] " + name);
        } else {
            out.println("[TEST] " + displayName);
        }
    }

    @Override
    public void onTestNodeStarted(int id) {
    }

    @Override
    public void onTestNodeFinished(int id, byte status, String message) {
        String s;
        switch (status) {
            case ProtocolConstants.TEST_PASSED -> s = "PASSED";
            case ProtocolConstants.TEST_FAILED -> s = "FAILED";
            case ProtocolConstants.TEST_SKIPPED -> s = "SKIPPED";
            case ProtocolConstants.TEST_RUNNING -> s = "RUNNING";
            case ProtocolConstants.TEST_PENDING -> s = "PENDING";
            default -> s = "";
        }
        if (message != null && !message.isEmpty()) {
            out.println("  -> " + s + ": " + message);
        } else {
            out.println("  -> " + s);
        }
    }

    @Override
    public void onTestLog(int id, String message) {
        out.println("  " + message);
    }
}
