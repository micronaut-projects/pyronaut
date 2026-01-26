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

import io.micronaut.python.cli.protocol.EventEncoder;
import io.micronaut.python.cli.protocol.ProtocolConstants;
import io.micronaut.python.cli.ui.StreamsCapture;
import org.junit.platform.engine.reporting.ReportEntry;
import org.jspecify.annotations.NonNull;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.FileSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("unused")
public class TestApplicationManager implements ApplicationManager {
    private EventEncoder eventEncoder;
    private final Map<String, Integer> idByUid = new HashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread execThread;

    @Override
    public void setEventOutputStream(DataOutputStream out) {
        this.eventEncoder = new EventEncoder(out);
    }

    @Override
    public void startApplication(String[] args) {
        cancelled.set(false);
        execThread = Thread.currentThread();
        var cl = Thread.currentThread().getContextClassLoader();
        var config = LauncherConfig.builder()
                .enableLauncherDiscoveryListenerAutoRegistration(true)
                .enableLauncherSessionListenerAutoRegistration(true)
                .enablePostDiscoveryFilterAutoRegistration(true)
                .enableTestEngineAutoRegistration(true)
                .build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create(config);
        try {
            if (eventEncoder != null) {
                eventEncoder.sendTestRunStarted(System.currentTimeMillis());
                eventEncoder.flush();
            }
        } catch (Exception ignored) {
        }
        if (Thread.currentThread().isInterrupted() || cancelled.get()) {
            return;
        }
        var plan = testPlanFor(launcher, listener);
        emitPlanNodes(plan);
        if (Thread.currentThread().isInterrupted() || cancelled.get()) {
            return;
        }
        var execListener = new EmittingListener();
        try {
            launcher.execute(plan, execListener);
        } catch (Exception ignored) {
        }
        sendSummary(listener);
    }

    private void sendSummary(SummaryGeneratingListener listener) {
        try {
            var summary = listener.getSummary();
            var passed = summary.getTestsSucceededCount();
            var failed = summary.getTestsFailedCount();
            var skipped = summary.getTestsSkippedCount();
            var aborted = summary.getTestsAbortedCount();
            skipped += aborted;
            var running = 0;
            var pending = 0;
            eventEncoder.sendTestRunFinished(passed, failed, skipped, running, pending);
            eventEncoder.flush();
        } catch (Exception ignored) {
        }
    }

    private TestPlan testPlanFor(Launcher launcher, SummaryGeneratingListener listener) {
        var selectors = DiscoverySelectors.selectDirectory("tests");
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();
        // Register only summary listener here; execution listener is passed to execute()
        launcher.registerTestExecutionListeners(listener);
        return launcher.discover(request);
    }

    // ---- Emission of test plan structure (classes & methods) ----

    private void emitPlanNodes(TestPlan plan) {
        for (var root : plan.getRoots()) {
            emitRecursively(plan, root);
        }
    }

    private void emitRecursively(TestPlan plan, TestIdentifier id) {
        try {
            if (isClass(id)) {
                emitClassNode(id);
            } else if (isMethod(id)) {
                emitMethodNode(plan, id);
            }
        } catch (Exception ignored) {
        }
        for (var child : plan.getChildren(id)) {
            emitRecursively(plan, child);
        }
    }

    private boolean isClass(TestIdentifier id) {
        return id.getSource().filter(src -> (src instanceof ClassSource) || (src instanceof FileSource)).isPresent();
    }

    private boolean isMethod(TestIdentifier id) {
        // Treat JUnit Java methods and generic JUnit Platform test nodes (e.g., pytest cases) as methods
        return id.isTest() || id.getSource().filter(MethodSource.class::isInstance).isPresent();
    }

    private int idFor(TestIdentifier id) {
        var key = id.getUniqueId();
        return idByUid.computeIfAbsent(key, k -> nextId.getAndIncrement());
    }

    private int ensureClassParentId(TestPlan plan, TestIdentifier id) {
        var current = id;
        while (true) {
            var parent = plan.getParent(current);
            if (parent.isEmpty()) {
                break;
            }
            current = parent.get();
            if (isClass(current)) {
                return idFor(current);
            }
        }
        return 0;
    }

    private void emitClassNode(TestIdentifier id) throws IOException {
        var nodeId = idFor(id);
        var opt = id.getSource();
        String name;
        String display;
        if (opt.isPresent() && opt.get() instanceof ClassSource cs) {
            var fqcn = cs.getClassName();
            name = fqcn;
            display = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        } else if (opt.isPresent() && opt.get() instanceof FileSource fs) {
            var p = fs.getFile();
            name = p.toString();
            display = p.getName();
        } else {
            name = id.getDisplayName();
            display = name;
        }
        eventEncoder.sendTestNode(nodeId, 0, (byte) 1, name, display);
    }

    private void emitMethodNode(TestPlan plan, TestIdentifier id) throws IOException {
        var nodeId = idFor(id);
        var parentId = ensureClassParentId(plan, id);
        var opt = id.getSource();
        String name;
        String display;
        if (opt.isPresent() && opt.get() instanceof MethodSource ms) {
            name = ms.getMethodName();
            display = id.getDisplayName();
        } else {
            name = id.getDisplayName();
            display = name;
        }
        eventEncoder.sendTestNode(nodeId, parentId, (byte) 2, name, display);
    }

    // ---- Live execution events -> node started/finished ----

    private class EmittingListener implements TestExecutionListener {
        private final Map<Integer, Long> testStartIndex = new ConcurrentHashMap<>();

        @Override
        public void executionStarted(@NonNull TestIdentifier id) {
            try {
                if (isClass(id) || isMethod(id)) {
                    var nid = idFor(id);
                    eventEncoder.sendTestNodeStarted(nid);
                    if (id.isTest()) {
                        long start = StreamsCapture.getInstance().tailIndex();
                        testStartIndex.put(nid, start);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void executionFinished(@NonNull TestIdentifier id, @NonNull TestExecutionResult result) {
            try {
                if (isClass(id) || isMethod(id)) {
                    var nid = idFor(id);
                    if (id.isTest()) {
                        var cap = StreamsCapture.getInstance();
                        long start = testStartIndex.getOrDefault(nid, cap.tailIndex());
                        long end = cap.tailIndex();
                        for (String line : cap.readRange(start, end)) {
                            eventEncoder.sendTestLog(nid, line);
                        }
                        testStartIndex.remove(nid);
                    }
                    var status = switch (result.getStatus()) {
                        case SUCCESSFUL -> ProtocolConstants.TEST_PASSED;
                        case ABORTED -> ProtocolConstants.TEST_SKIPPED;
                        case FAILED -> ProtocolConstants.TEST_FAILED;
                    };
                    var msg = result.getThrowable().map(Throwable::getMessage).orElse("");
                    eventEncoder.sendTestNodeFinished(nid, status, msg);
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void executionSkipped(@NonNull TestIdentifier id, @NonNull String reason) {
            try {
                if (isClass(id) || isMethod(id)) {
                    var nid = idFor(id);
                    eventEncoder.sendTestNodeFinished(nid, ProtocolConstants.TEST_SKIPPED, reason);
                }
            } catch (Exception ignored) {
            }
        }

        // Dedup last-emitted content per node and stream, and preserve line breaks
        private final Map<Integer, Map<String, String>> lastEmitted = new ConcurrentHashMap<>();

        @Override
        public void reportingEntryPublished(@NonNull TestIdentifier id, @NonNull ReportEntry entry) {
            try {
                if (!id.isTest()) {
                    return;
                }
                var nid = idFor(id);
                var kv = entry.getKeyValuePairs();
                forwardStream(nid, "stdout", kv.getOrDefault("stdout", kv.get("system-out")));
                forwardStream(nid, "stderr", kv.getOrDefault("stderr", kv.get("system-err")));
                forwardStream(nid, "log", kv.get("log"));
            } catch (Exception ignored) {
            }
        }

        private void forwardStream(int nid, String stream, String text) throws IOException {
            if (text == null || text.isEmpty()) {
                return;
            }
            var lastByStream = lastEmitted.computeIfAbsent(nid, k -> new ConcurrentHashMap<>());
            var last = lastByStream.get(stream);
            if (text.equals(last)) {
                return; // exact duplicate, drop
            }
            lastByStream.put(stream, text);
            // Preserve newlines by splitting into list rows; keep empty lines as a single space for UI
            String[] lines = text.split("\r?\n", -1);
            for (String line : lines) {
                eventEncoder.sendTestLog(nid, line.isEmpty() ? " " : line);
            }
        }
    }

    @Override
    public void stopApplication() {
        cancelled.set(true);
        var t = execThread;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Exception ignored) {
            }
        }
    }
}
