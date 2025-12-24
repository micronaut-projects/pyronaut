/*
 * Copyright 2017-2025 original authors
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

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;
import io.micronaut.python.cli.protocol.EventDecoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;

import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

/**
 * Clean TamboUI Control Panel for Pyronaut CLI.
 * Features a dashboard-style layout with status sections and stream capture.
 */
public final class PyronautTui {
    private final UiController controller;
    private final StreamsCapture streamsCapture;
    private volatile boolean running = true;
    private Thread outConsumer;
    private Thread errConsumer;

    public UiController getController() {
        return controller;
    }

    public PyronautTui(StreamsCapture streamsCapture) {
        this.controller = new UiController();
        this.streamsCapture = streamsCapture;
    }

    public PyronautTui() {
        this(StreamsCapture.getInstance());
    }

    // Add new field to hold the event input stream for protocol
    private DataInputStream eventInputStream;
    public void setEventInputStream(DataInputStream in) {
        this.eventInputStream = in;
    }

    public void run() throws Exception {
        startStreamConsumers();

        // Start event listening thread (if stream present)
        if (eventInputStream != null) {
            Thread eventThread = new Thread(() -> listenForEvents(eventInputStream));
            eventThread.setDaemon(true);
            eventThread.start();
        }

        // Run TUI with clean dashboard layout
        try (var runner = ToolkitRunner.create(TuiConfig.builder()
                .tickRate(Duration.ofMillis(100))
                .build())) {
            runner.run(() -> render(controller.getModel()));
        } finally {
            stopStreamConsumers();
        }
    }

    private void startStreamConsumers() {
        BlockingQueue<String> outQ = streamsCapture.stdoutQueue();
        BlockingQueue<String> errQ = streamsCapture.stderrQueue();

        running = true;

        outConsumer = new Thread(() -> {
            while (running) {
                try {
                    String line = outQ.take();
                    // During "Compiling" dispatch AddCompileOutput
                    if (controller.getModel() instanceof UiModel.Compiling) {
                        controller.addCompileOutput(line);
                    } else {
                        controller.notify(line, UiModel.Severity.INFO);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "StdoutConsumer");

        errConsumer = new Thread(() -> {
            while (running) {
                try {
                    String line = errQ.take();
                    // During "Compiling" dispatch AddCompileOutput (treat as log)
                    if (controller.getModel() instanceof UiModel.Compiling) {
                        controller.addCompileOutput(line);
                    } else {
                        controller.notify(line, UiModel.Severity.ERROR);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "StderrConsumer");

        outConsumer.setDaemon(true);
        errConsumer.setDaemon(true);
        outConsumer.start();
        errConsumer.start();
    }

    private void stopStreamConsumers() {
        running = false;
        if (outConsumer != null) outConsumer.interrupt();
        if (errConsumer != null) errConsumer.interrupt();
    }

    private void listenForEvents(DataInputStream in) {
        try {
            EventDecoder decoder = new EventDecoder(in);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    var event = decoder.readEvent();
                    handleEvent(event);
                } catch (Exception e) {
                    // Stream error, exit
                    break;
                }
            }
        } catch (Exception e) {
            // Ignore event input errors
        }
    }

    private void handleEvent(EventDecoder.EventHandler event) throws IOException {
        switch (event.getEventType()) {
            case ProtocolConstants.APP_STARTED -> {
                long timestamp = event.readAppStarted();
                controller.setRunning();
            }
            case ProtocolConstants.APP_STOPPED -> {
                long timestamp = event.readAppStopped();
                // Only stop running if not compiling—preserves Compiling state during a rebuild
                if (!(controller.getModel() instanceof UiModel.Compiling)) {
                    controller.setStopped();
                } else {
                    event.skip();
                }
            }
            case ProtocolConstants.SERVER_URI -> {
                String uri = event.readServerUri();
                controller.setUrl(uri);
            }
            case ProtocolConstants.APP_LOG -> {
                var logEvent = event.readAppLog();
                String levelStr = switch (logEvent.level()) {
                    case ProtocolConstants.LOG_DEBUG -> "[DEBUG]";
                    case ProtocolConstants.LOG_INFO -> "[INFO]";
                    case ProtocolConstants.LOG_WARN -> "[WARN]";
                    case ProtocolConstants.LOG_ERROR -> "[ERROR]";
                    case ProtocolConstants.LOG_FATAL -> "[FATAL]";
                    default -> "[UNKNOWN]";
                };
                controller.notify(
                        levelStr + " " + logEvent.message(),
                        switch (logEvent.level()) {
                            case ProtocolConstants.LOG_WARN -> UiModel.Severity.WARNING;
                            case ProtocolConstants.LOG_ERROR, ProtocolConstants.LOG_FATAL -> UiModel.Severity.ERROR;
                            default -> UiModel.Severity.INFO;
                        }
                );
            }
            default -> event.skip(); // Skip unknown events
        }
    }

    private Element render(UiModel model) {
        // Choose state string from controller
        String state = controller.isCompiling() ? "Compiling"
                      : controller.isTesting() ? "Testing"
                      : controller.getUrl() != null ? "Running"
                      : "Idle";

        // HEADER
        Element header = Toolkit.row(
            Toolkit.text("Pyronaut").bold().cyan(),
            Toolkit.spacer(),
            chip(state, statusChipColor(state)),
            controller.getUrl() != null ? chip(controller.getUrl(), Color.YELLOW) : Toolkit.text(""),
            Toolkit.spacer()
        );

        // DASHBOARD
        Element dashboard = new Dashboard(controller);

        // FOOTER
        Element footer = Toolkit.panel(
            Toolkit.row(
                Toolkit.text("Tab/Shift+Tab: focus panels – Up/Down/PgUp/PgDn/Home/End: scroll"),
                Toolkit.spacer(),
                Toolkit.text("Ctrl+C").bold().red(),
                Toolkit.text(" quit")
            )
        ).rounded().borderColor(Color.DARK_GRAY).length(1);

        return Toolkit.column(header, dashboard, footer);
    }

    private Color statusChipColor(String state) {
        return switch (state) {
            case "Idle" -> Color.DARK_GRAY;
            case "Running" -> Color.GREEN;
            case "Compiling" -> Color.YELLOW;
            case "Testing" -> Color.BLUE;
            default -> Color.DARK_GRAY;
        };
    }

    private Element renderNotifications(UiModel.Notification notif) {
        if (notif == null) return Toolkit.text("No notifications").dim();
        var color = switch (notif.severity()) {
            case INFO -> Toolkit.text(notif.message()).white();
            case SUCCESS -> Toolkit.text(notif.message()).green();
            case WARNING -> Toolkit.text(notif.message()).yellow();
            case ERROR -> Toolkit.text(notif.message()).red();
        };
        return color;
    }



    // Utility method for column varargs flattening
    private static Element[] concat(Element first, Element[] rest) {
        Element[] arr = new Element[rest.length + 1];
        arr[0] = first;
        System.arraycopy(rest, 0, arr, 1, rest.length);
        return arr;
    }

    // Simple colored "chip" (rounded, colored background)
    private static Element chip(String text, Color bg) {
        // Fallback: colored text w/ some spacing, as TamboUI toolkit.TextElement doesn't support .background(c).
        // fallback: colored and bold, but no background support
        return Toolkit.text("[ " + text + " ]")
                .bold()
                .dim(); // fallback style, or use .cyan()/.green() by chip type if needed
    }

    public void stop() {
        running = false;
        stopStreamConsumers();
    }
}
