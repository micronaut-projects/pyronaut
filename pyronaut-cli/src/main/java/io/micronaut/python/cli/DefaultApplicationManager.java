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
import io.micronaut.context.banner.Banner;
import io.micronaut.context.banner.MicronautBanner;
import io.micronaut.context.banner.ResourceBanner;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.core.naming.Described;
import io.micronaut.python.cli.protocol.EventEncoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.server.EmbeddedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultApplicationManager implements ApplicationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultApplicationManager.class);
    private static final String BANNER_NAME = "micronaut-banner.txt";

    private final Lock lock = new ReentrantLock();
    private final AtomicReference<ApplicationContext> applicationContextRef = new AtomicReference<>();
    private EventEncoder eventEncoder;

    static {
        ContextUtil.setReuseContext();
    }

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
                // WARNING: do NOT use Micronaut.builder(...) here, because
                // it will install a shutdown hook that will prevent the
                // context to be closed when we need it and will cause locks
                // when shutting down the TUI
                currentContext = ApplicationContext.builder()
                        .args(args)
                        .classLoader(this.getClass().getClassLoader())
                        .start();
                resolveBanner(currentContext).print();
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
                            try {
                                embeddedApplication.start();
                            } catch (Exception e) {
                                try {
                                    eventEncoder.sendAppStartFailed();
                                } catch (IOException ex) {
                                    throw new RuntimeException(ex);
                                }
                                throw new RuntimeException(e);
                            }
                            if (embeddedApplication instanceof Described described) {
                                if (LOGGER.isInfoEnabled()) {
                                    long took = elapsedMillis(start);
                                    String desc = described.getDescription();
                                    LOGGER.info("Startup completed in {}ms. Server Running: {}", took, desc);
                                }
                            } else if (embeddedApplication instanceof EmbeddedServer embeddedServer) {
                                if (LOGGER.isInfoEnabled()) {
                                    long took = elapsedMillis(start);
                                    Object uri;
                                    try {
                                        uri = embeddedServer.getContextURI();
                                    } catch (UnsupportedOperationException e) {
                                        uri = "<URI display not available: " + e.getMessage() + ">";
                                    }
                                    LOGGER.info("Startup completed in {}ms. Server Running: {}", took, uri);
                                }
                                if (eventEncoder != null) {
                                    try {
                                        var uriStr = embeddedServer.getContextURI().toString();
                                        eventEncoder.sendServerUri(uriStr);
                                        List<String> endpoints = discoverEndpoints(currentContext);
                                        if (!endpoints.isEmpty()) {
                                            eventEncoder.sendEndpointList(endpoints);
                                        }
                                        eventEncoder.flush();
                                    } catch (Exception e) {
                                        // Ignore if URI not available or socket error
                                    }
                                }
                            } else {
                                if (LOGGER.isInfoEnabled()) {
                                    long took = elapsedMillis(start);
                                    LOGGER.info("Startup completed in {}ms.", took);
                                }
                            }
                        });
                applicationContextRef.set(currentContext);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    private List<String> discoverEndpoints(ApplicationContext ctx) {
        List<String> result = new ArrayList<>();
        try {
            var serverOpt = ctx.findBean(EmbeddedServer.class);
            if (serverOpt.isEmpty()) {
                return result;
            }
            var server = serverOpt.get();
            var baseUri = server.getURL();
            var routesUri = URI.create(baseUri.toString() + "/routes");
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var req = HttpRequest.newBuilder(routesUri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                var body = resp.body();
                // Keys look like: "{[/hello/{name}],method=[GET],produces=[application/json]}"
                // Capture paths and methods from the key portion
                Pattern p = Pattern.compile("\\{\\[([^\\]]+)\\],\\s*method=\\[([^\\]]+)\\]");
                Matcher m = p.matcher(body);
                Set<String> ordered = new LinkedHashSet<>();
                while (m.find()) {
                    var pathsGroup = m.group(1);
                    var methodsGroup = m.group(2);
                    var paths = pathsGroup.split(",");
                    var methods = methodsGroup.split(",");
                    for (var rawMethod : methods) {
                        var method = rawMethod.trim();
                        if ("HEAD".equalsIgnoreCase(method)) {
                            continue;
                        }
                        for (var rawPath : paths) {
                            var path = rawPath.trim();
                            ordered.add(method + " " + path);
                        }
                    }
                }
                result = new ArrayList<>(ordered);

            }
        } catch (Exception ignored) {
        }
        return result;
    }


    // Hook for bridging Logback logs to protocol events
    private void attachLogbackToEventEncoder(EventEncoder encoder) {
        try {
            // Use reflection to support both Logback Classic 1.x and 0.x
            Class<?> appenderCls = Class.forName("ch.qos.logback.core.Appender");
            Class<?> levelCls = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggingEventCls = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent");

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
                            lvl = switch (levelStr) {
                                case "ERROR" -> ProtocolConstants.LOG_ERROR;
                                case "WARN" -> ProtocolConstants.LOG_WARN;
                                case "INFO" -> ProtocolConstants.LOG_INFO;
                                case "DEBUG" -> ProtocolConstants.LOG_DEBUG;
                                default -> ProtocolConstants.LOG_INFO;
                            };
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

    private Banner resolveBanner(ApplicationContext applicationContext) {
        var out = System.out;
        var loader =  applicationContext.getBean(ResourceLoader.class);
        return loader.getResource(BANNER_NAME)
            .map(resource -> (Banner) new ResourceBanner(resource, out))
            .orElseGet(() -> new MicronautBanner(out));
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.MILLISECONDS.convert(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
