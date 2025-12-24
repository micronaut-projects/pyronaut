/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.python.cli;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.naming.Described;
import io.micronaut.python.cli.protocol.EventEncoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;
import io.micronaut.python.cli.ui.UiAction;
import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.server.EmbeddedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultApplicationManager implements ApplicationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultApplicationManager.class);

    private final Lock lock = new ReentrantLock();
    private final AtomicReference<ApplicationContext> applicationContextRef = new AtomicReference<>();
    private UiController uiController;
    private EventEncoder eventEncoder;

    // Not an override; just a method on the interface
    public void setEventOutputStream(DataOutputStream out) {
        // This will override the automatic lazy initialization
        this.eventEncoder = new EventEncoder(out);
    }

    @Override
    public void startApplication(String[] args) {
        initializeEventEncoder();
        lock.lock();
        try {
            var current = applicationContextRef.get();
            if (current != null) {
                throw new IllegalStateException("Application context already started");
            }
            long start = System.nanoTime();
            ApplicationContext currentContext;
            try {
                currentContext = ApplicationContext.builder()
                    .args(args)
                    .classLoader(this.getClass().getClassLoader())
                    .start();

                // Send APP_STARTED event *before* any URI or logs (ensures model transitions to Running)
                if (eventEncoder != null) {
                    try {
                        eventEncoder.sendAppStarted(System.currentTimeMillis());
                        eventEncoder.flush();
                    } catch (Exception e) {
                        // Ignore socket errors
                    }
                }

                // Programmatic Logback appender for protocol-driven logs
                if (eventEncoder != null) {
                    try {
                        attachLogbackToEventEncoder(eventEncoder);
                    } catch (Exception ex) {
                        // Ignore
                    }
                }

                currentContext.findBean(EmbeddedApplication.class)
                    .ifPresent(embeddedApplication -> {
                        embeddedApplication.start();
                        if (embeddedApplication instanceof Described described) {
                            if (LOGGER.isInfoEnabled()) {
                                long took = elapsedMillis(start);
                                String desc = described.getDescription();
                                LOGGER.info("Startup completed in {}ms. Server Running: {}", took,
                                    desc);
                            }
                        } else {
                            if (embeddedApplication instanceof EmbeddedServer embeddedServer) {
                                if (LOGGER.isInfoEnabled()) {
                                    long took = elapsedMillis(start);
                                    Object uri;
                                    try {
                                        uri = embeddedServer.getContextURI();
                                    } catch (UnsupportedOperationException e) {
                                        uri = "<URI display not available: " + e.getMessage() + ">";
                                    }
                                    LOGGER.info("Startup completed in {}ms. Server Running: {}",
                                        took, uri);
                                }
                                // Send SERVER_URI event
                                if (eventEncoder != null) {
                                    try {
                                        var uriStr = embeddedServer.getContextURI().toString();
                                        eventEncoder.sendServerUri(uriStr);
                                        eventEncoder.flush();
                                    } catch (Exception e) {
                                        // Ignore if URI not available or socket error
                                    }
                                }
                                // Update UI with URI (legacy, but keep for now)
                                if (uiController != null) {
                                    try {
                                        var uriStr = embeddedServer.getContextURI().toString();
                                        uiController.setUrl(uriStr);
                                    } catch (UnsupportedOperationException e) {
                                        // Ignore if URI not available
                                    }
                                }
                            } else {
                                if (LOGGER.isInfoEnabled()) {
                                    long took = elapsedMillis(start);
                                    LOGGER.info("Startup completed in {}ms.", took);
                                }
                            }
                        }
                    });

                // UI: "Application started" routed through notification, not terminal
                if (uiController != null) {
                    uiController.notify(
                        "Application started", io.micronaut.python.cli.ui.UiModel.Severity.SUCCESS
                    );
                }
                applicationContextRef.set(currentContext);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    // Hook for bridging Logback logs to protocol events
    private void attachLogbackToEventEncoder(EventEncoder encoder) {
        try {
            // Use reflection to support both Logback Classic 1.x and 0.x
            Class<?> loggerContextCls = Class.forName("ch.qos.logback.classic.LoggerContext");
            Class<?> appenderCls = Class.forName("ch.qos.logback.core.Appender");
            Class<?> loggerCls = Class.forName("ch.qos.logback.classic.Logger");
            Class<?> levelCls = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggingEventCls = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent");

            Object loggerContext = loggerContextCls.getMethod("getILoggerFactory").invoke(
                    loggerCls.getMethod("getLogger", String.class).invoke(null, "ROOT")
            );
            Object rootLogger = loggerCls.getMethod("getLogger", String.class).invoke(null, "ROOT");

            // Appender definition
            Proxy.newProxyInstance(
                    appenderCls.getClassLoader(),
                    new Class[]{appenderCls},
                    (proxy, method, args) -> {
                        if ("doAppend".equals(method.getName()) && args.length > 0
                                && loggingEventCls.isInstance(args[0])) {
                            Object event = args[0];
                            String message = (String) loggingEventCls.getMethod("getFormattedMessage").invoke(event);
                            Object level = loggingEventCls.getMethod("getLevel").invoke(event);

                            byte lvl;
                            String levelStr = (String) levelCls.getMethod("toString").invoke(level);
                            switch (levelStr) {
                                case "ERROR": lvl = ProtocolConstants.LOG_ERROR; break;
                                case "WARN": lvl = ProtocolConstants.LOG_WARN; break;
                                case "INFO": lvl = ProtocolConstants.LOG_INFO; break;
                                case "DEBUG": lvl = ProtocolConstants.LOG_DEBUG; break;
                                default: lvl = ProtocolConstants.LOG_INFO; break;
                            }
                            encoder.sendAppLog(lvl, message);
                            encoder.flush();
                            return null;
                        }
                        return null;
                    }
            );
            // Register appender with root logger (via reflection, as above)
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stopApplication() {
        lock.lock();
        try {
            var app = applicationContextRef.get();
            if (app != null) {
                app.close();
                if (uiController != null) {
                    uiController.notify(
                        "Application stopped", UiModel.Severity.INFO
                    );
                }
                // Send APP_STOPPED event
                if (eventEncoder != null) {
                    try {
                        eventEncoder.sendAppStopped(System.currentTimeMillis());
                        eventEncoder.flush();
                    } catch (Exception e) {
                        // Ignore socket errors
                    }
                }
            } else {
                throw new IllegalStateException("Cannot close application context which was not started");
            }
        } finally {
            applicationContextRef.set(null);
            lock.unlock();
        }
    }

    private void initializeEventEncoder() {
        // Only create if not already supplied by setEventOutputStream
        if (eventEncoder == null) {
            String socketPath = System.getProperty("pyronaut.ui.socket");
            if (socketPath != null) {
                try {
                    Socket socket = new Socket("localhost", Integer.parseInt(socketPath));
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    eventEncoder = new EventEncoder(out);
                } catch (Exception e) {
                    // Ignore socket connection errors
                }
            }
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.MILLISECONDS.convert(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
