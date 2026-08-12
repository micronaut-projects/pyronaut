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

import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.ActionHandler;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.KeyCode;
import io.micronaut.python.cli.protocol.EventDecoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;
import io.micronaut.python.cli.ui.view.RootView;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@SuppressWarnings("checkstyle:MissingJavadocType")
public final class PyronautTui {
    private static final String TEST_RESOURCES_IMAGE_PULL_MARKER = "Pulling docker image:";
    private static final String TEST_RESOURCES_CONTAINER_CREATE_MARKER = "Creating container for image:";
    private static final String TEST_RESOURCES_CONTAINER_STARTED_MARKER = " started in PT";

    private final UiController controller;
    private final RootView view;
    private Runnable onQuit;
    private Runnable onCtrlR;
    private Runnable onCtrlT;
    private ModeCoordinator coordinator;
    private Supplier<Mode> desiredModeSupplier;
    private DataInputStream eventInputStream;
    private long initialLogStart = -1L;

    public PyronautTui() {
        this.controller = new UiController();
        this.view = new RootView(controller);
    }

    public UiController getController() {
        return controller;
    }

    public void setEventInputStream(DataInputStream in) {

        this.eventInputStream = in;
    }

    public void setInitialLogStart(long start) {
        this.initialLogStart = start;
    }

    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit;
    }

    public void setOnRunRequested(Runnable r) {
        this.onCtrlR = r;
    }

    public void setOnTestRequested(Runnable r) {
        this.onCtrlT = r;
    }

    public void setMode(Mode mode) {
        this.view.setMode(mode);
    }

    public void setCoordinator(ModeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setDesiredModeSupplier(Supplier<Mode> supplier) {
        this.desiredModeSupplier = supplier;
    }

    public void run() throws Exception {
        if (eventInputStream != null) {
            var sink = new TuiEventSink(controller);
            if (this.coordinator != null) {
                sink.setCoordinator(this.coordinator);
            }
            var loop = new ProtocolEventLoop(eventInputStream, sink);
            var eventThread = new Thread(loop, "Pyronaut-EventListener");
            eventThread.setDaemon(true);
            eventThread.start();
        }

        // Backfill early logs and attach live consumers here (exactly once)
        var cap = StreamsCapture.getInstance();
        if (initialLogStart >= 0) {
            long end = cap.tailIndex();
            for (String line : cap.readRange(initialLogStart, end)) {
                routeCapturedLine(controller, line);
            }
        }
        cap.getStdOutThread().addConsumer(line -> routeCapturedLine(controller, line));
        cap.getStdErrThread().addConsumer(line -> routeCapturedLine(controller, line));

        var actions = new GlobalActions();

        var styleEngine = StyleEngine.create();
        try {
            styleEngine.loadStylesheet("pyronaut", "/pyronaut-ui.tcss");
            styleEngine.setActiveStylesheet("pyronaut");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load /pyronaut-ui.tcss", e);
        }

        var config = TuiConfig.builder()
                .mouseCapture(false)
                .build();
        var bindings = BindingSets.standard()
                .toBuilder()
                .bind(KeyTrigger.ctrl('s'), RootView.SAVE_LOGS)
                .bind(KeyTrigger.key(KeyCode.F1), RootView.SELECT_TAB_APP)
                .bind(KeyTrigger.key(KeyCode.F2), RootView.SELECT_TAB_LOGS)
                .bind(KeyTrigger.key(KeyCode.F3), RootView.SELECT_TAB_RESOURCES)
                .bind(KeyTrigger.ch('t'), RootView.FOCUS_TREE)
                .bind(KeyTrigger.ch('o'), RootView.FOCUS_OUTPUT)
                .bind(KeyTrigger.ctrl('r'), "switchToRun")
                .bind(KeyTrigger.ctrl('t'), "switchToTest")
                .build();
        var runnerThread = Thread.currentThread();
        try (var runner = ToolkitRunner.builder()
                .bindings(bindings)
                .app(actions)
                .withAutoBindingRegistration()
                .faultTolerant(true)
                .config(config)
                .build()) {
            runner.styleEngine(styleEngine);

            var rootHandler = new ActionHandler(bindings)
                    .registerAnnotated(view)
                    .registerAnnotated(actions);
            runner.eventRouter().addGlobalHandler(rootHandler);

            actions.setQuitter(() -> {
                try {
                    if (onQuit != null) {
                        onQuit.run();
                    }
                } finally {
                    runner.quit();
                    runnerThread.interrupt();
                }
            });
            actions.setSwitchToRun(() -> {
                var r = onCtrlR;
                if (r != null) {
                    r.run();
                }
            });
            actions.setSwitchToTest(() -> {
                var r = onCtrlT;
                if (r != null) {
                    r.run();
                }
            });
 
            runner.run(this::render);
        }

    }

    private static UiModel.Status mapStatus(byte code) {
        return switch (code) {
            case ProtocolConstants.TEST_PASSED -> UiModel.Status.PASSED;
            case ProtocolConstants.TEST_FAILED -> UiModel.Status.FAILED;
            case ProtocolConstants.TEST_SKIPPED -> UiModel.Status.SKIPPED;
            case ProtocolConstants.TEST_RUNNING -> UiModel.Status.RUNNING;
            case ProtocolConstants.TEST_PENDING -> UiModel.Status.PENDING;
            default -> UiModel.Status.PENDING;
        };
    }

    private UiModel.TestSuite buildTreeFromSnapshot(EventDecoder.EventHandler.TestResult r) {
        List<UiModel.TestClass> classes = new ArrayList<>();
        for (var c : r.classes()) {
            List<UiModel.TestMethod> methods = new ArrayList<>();
            for (var m : c.methods()) {
                var msgOpt = (m.message() == null || m.message().isEmpty())
                        ? Optional.<String>empty()
                        : Optional.of(m.message());
                methods.add(new UiModel.TestMethod(m.name(), m.displayName(), mapStatus(m.status()), msgOpt, List.of()));
            }
            UiModel.Status classStatus = methods.isEmpty()
                    ? mapStatus(c.status())
                    : aggregateStatusForMethods(methods);
            classes.add(new UiModel.TestClass(c.name(), methods, classStatus));
        }
        UiModel.Status suiteStatus = aggregateStatusForClasses(classes);
        return new UiModel.TestSuite("Tests", classes, suiteStatus);
    }

    private static UiModel.Status aggregateStatusForClasses(List<UiModel.TestClass> classes) {
        UiModel.Status worst = UiModel.Status.PASSED;
        if (classes.isEmpty()) {
            return UiModel.Status.PENDING;
        }
        for (var c : classes) {
            worst = worseOf(worst, c.status());
        }
        return worst;
    }

    private static UiModel.Status aggregateStatusForMethods(List<UiModel.TestMethod> methods) {
        UiModel.Status worst = UiModel.Status.PASSED;
        if (methods.isEmpty()) {
            return UiModel.Status.PENDING;
        }
        for (var m : methods) {
            worst = worseOf(worst, m.status());
        }
        return worst;
    }

    private static UiModel.Status worseOf(UiModel.Status a, UiModel.Status b) {
        return weight(b) > weight(a) ? b : a;
    }

    private static int weight(UiModel.Status s) {
        return switch (s) {
            case FAILED -> 5;
            case RUNNING -> 4;
            case PENDING -> 3;
            case SKIPPED -> 2;
            case PASSED -> 1;
        };
    }

    private static void routeCapturedLine(UiController controller, String line) {
        if (line != null && isTestResourcesLine(line)) {
            controller.addTestResourcesOutput(line);
            return;
        }
        controller.addActivityOutput(line);
    }

    private static boolean isTestResourcesLine(String line) {
        return line.startsWith("[test-resources-service]")
            || line.startsWith("[test-resources]")
            || line.startsWith("[STDERR] [test-resources]")
            || line.contains(TEST_RESOURCES_IMAGE_PULL_MARKER)
            || line.contains(TEST_RESOURCES_CONTAINER_CREATE_MARKER)
            || line.contains(TEST_RESOURCES_CONTAINER_STARTED_MARKER);
    }

    private Element render() {
        return view;
    }

}
