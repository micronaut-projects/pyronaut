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

import io.micronaut.python.cli.protocol.EventDecoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;

import java.io.DataInputStream;
import java.io.EOFException;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("checkstyle:MissingJavadocType")
public final class ProtocolEventLoop implements Runnable {

    private final DataInputStream in;
    private final ProtocolEventSink sink;
    private final AtomicBoolean running = new AtomicBoolean();

    public ProtocolEventLoop(DataInputStream in, ProtocolEventSink sink) {
        this.in = in;
        this.sink = sink;
    }

    @Override
    public void run() {
        running.set(true);
        var decoder = new EventDecoder(in);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                var ev = decoder.readEvent();
                if (ev == null) {
                    continue;
                }
                switch (ev.getEventType()) {
                    case ProtocolConstants.APP_STARTED -> {
                        ev.readAppStarted();
                        sink.onAppStarted();
                    }
                    case ProtocolConstants.APP_STOPPED -> {
                        ev.readAppStopped();
                        sink.onAppStopped();
                    }
                    case ProtocolConstants.SERVER_URI -> {
                        var uri = ev.readServerUri();
                        sink.onServerUri(uri);
                    }
                    case ProtocolConstants.APP_LOG -> {
                        var log = ev.readAppLog();
                        sink.onAppLog(log.level(), log.message());
                    }
                    case ProtocolConstants.ENDPOINT_LIST -> {
                        var endpoints = ev.readEndpointList();
                        sink.onEndpointList(endpoints);
                    }
                    case ProtocolConstants.TEST_RUN_STARTED -> {
                        ev.readTestRunStarted();
                        sink.onTestRunStarted();
                    }
                    case ProtocolConstants.TEST_RUN_FINISHED -> {
                        var c = ev.readTestRunFinished();
                        sink.onTestRunFinished(c[0], c[1], c[2], c[3], c[4]);
                    }
                    case ProtocolConstants.TEST_NODE -> {
                        var n = ev.readTestNode();
                        sink.onTestNode(n.id(), n.parentId(), n.kind(), n.name(), n.displayName());
                    }
                    case ProtocolConstants.TEST_NODE_STARTED -> {
                        var id = ev.readTestNodeStarted();
                        sink.onTestNodeStarted(id);
                    }
                    case ProtocolConstants.TEST_NODE_FINISHED -> {
                        var f = ev.readTestNodeFinished();
                        sink.onTestNodeFinished(f.id(), f.status(), f.message());
                    }
                    case ProtocolConstants.TEST_LOG -> {
                        var l = ev.readTestLog();
                        sink.onTestLog(l.id(), l.message());
                    }
                    case ProtocolConstants.APP_START_FAILED -> {
                        sink.onAppStopped();
                    }
                    default -> ev.skip();
                }
            } catch (EOFException eof) {
                running.set(false);
                break;
            } catch (Exception e) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    break;
                }
            }
        }
    }

    public void stop() {
        running.set(false);
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }
}
