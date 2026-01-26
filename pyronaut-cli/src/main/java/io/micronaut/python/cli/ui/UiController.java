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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class UiController {

    private Mode currentMode = Mode.RUN;
    private String url = null;
    private List<String> endpoints = List.of();
    private UiModel.Notification lastNotification = null;
    private List<UiModel.FileUpdate> updatedFiles = List.of();
    // Unified activity log buffer (kept across state changes)
    private final List<String> activityLogLines = new ArrayList<>();
    private static final int MAX_ACTIVITY_LINES = 10000;
    private final List<UiModel.Notification> notificationHistory = new ArrayList<>();
    private static final int MAX_NOTIFICATIONS = 500;
    private boolean compiling = false;
    private boolean testing = false;
    private boolean switchingToTest = false;
    private boolean testSession = false;
    private UiModel.TestTree testTree = null;
    private TestSummary lastTestSummary = null;

    // Incremental test tree state (built from protocol TEST_NODE/STARTED/FINISHED events)
    private static final byte KIND_CLASS = 1;
    private static final byte KIND_METHOD = 2;

    public void clearLogs() {
        activityLogLines.clear();
    }

    private static final class Node {
        final int id;
        final int parentId;
        final byte kind;
        final String name;
        final String displayName;
        UiModel.Status status = UiModel.Status.PENDING;
        String failureMessage = null;
        final List<Integer> children = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        Node(int id, int parentId, byte kind, String name, String displayName) {
            this.id = id;
            this.parentId = parentId;
            this.kind = kind;
            this.name = name;
            this.displayName = displayName;
        }
    }

    private final Map<Integer, Node> testNodes = new HashMap<>();
    private final Map<Integer, List<String>> pendingLogs = new HashMap<>();
    private int currentMethodNodeId = 0;
    public int getCurrentMethodNodeId() { return currentMethodNodeId; }

    public record TestSummary(long passed, long failed, long skipped, long running, long pending) {}

    public Optional<TestSummary> getLastTestSummary() {
        return Optional.ofNullable(lastTestSummary);
    }

    public void updateTestSummary(long passed, long failed, long skipped, long running, long pending) {
        this.lastTestSummary = new TestSummary(passed, failed, skipped, running, pending);
    }

    public String getUrl() {
        return url;
    }

    public List<String> getEndpoints() {
        return endpoints;
    }

    public UiModel.Notification getLastNotification() {
        return lastNotification;
    }

    public List<UiModel.Notification> getNotificationHistory() {
        return notificationHistory;
    }

    public List<UiModel.FileUpdate> getUpdatedFiles() {
        return updatedFiles;
    }

    public List<String> getActivityLogLines() {
        return activityLogLines;
    }

    public boolean isCompiling() {
        return compiling;
    }

    public boolean isTesting() {
        return testing;
    }

    public UiModel.TestTree getTestTree() {
        return testTree;
    }

    public UiModel getModel() {
        Optional<UiModel.Notification> notif = lastNotification == null ? Optional.empty() : Optional.of(lastNotification);
        if (testing && testTree != null) {
            return new UiModel.Testing(testTree, notif);
        } else if (compiling) {
            return new UiModel.Compiling(updatedFiles, activityLogLines, notif);
        }
        return new UiModel.Running(url, endpoints, notif);
    }

    // Setters/mutators for explicit, clear API (no dispatch switch)

    public void setUrl(String url) {
        this.url = url;
    }

    public void clearUrl() {
        this.url = null;
    }

    public void setRunning() {
        compiling = false;
        testing = false;
        currentMode = Mode.RUN;
    }

    public boolean isRunning() {
        return !compiling && !testing;
    }

    public void setStopped() {
        this.url = null;
        this.compiling = false;
        this.testing = false;
    }

    public void resetState() {
        this.url = null;
        this.endpoints = List.of();
        this.compiling = false;
        this.testing = false;
        this.lastTestSummary = null;
        resetTestTree();
    }

    public void setEndpoints(List<String> eps) {
        this.endpoints = List.copyOf(eps);
    }

    public void clearEndpoints() {
        this.endpoints = List.of();
    }

    public void startCompiling() {
        compiling = true;
    }

    public void stopCompiling() {
        compiling = false;
    }

    public void addActivityOutput(String line) {
        for (String l : line.split("\\r?\\n")) {
            if (!l.isEmpty()) {
                activityLogLines.add(l);
                if (activityLogLines.size() > MAX_ACTIVITY_LINES) {
                    activityLogLines.remove(0);
                }
            }
        }
    }

    public void updateFiles(List<UiModel.FileUpdate> files) {
        updatedFiles = files;
    }

    public void notify(String message, UiModel.Severity severity) {
        UiModel.Notification notif = new UiModel.Notification(message, severity, Instant.now());
        lastNotification = notif;
        notificationHistory.add(notif);
        if (notificationHistory.size() > MAX_NOTIFICATIONS) {
            notificationHistory.remove(0);
        }
    }

    public void startTesting() {
        testing = true;
        currentMode = Mode.TEST;
        this.lastTestSummary = null;
        testNodes.clear();
        this.testTree = new UiModel.TestSuite("Tests", List.of(), UiModel.Status.PENDING);
    }

    public void stopTesting() {
        testing = false;
    }

    // ---- Test tree incremental API ----

    public void resetTestTree() {
        testNodes.clear();
        testTree = null;
    }

    public void addTestNode(int id, int parentId, byte kind, String name, String displayName) {
        Node node = new Node(id, parentId, kind, name, displayName);
        testNodes.put(id, node);
        if (parentId != 0) {
            Node parent = testNodes.get(parentId);
            if (parent != null) {
                parent.children.add(id);
            }
        }
        var buffered = pendingLogs.remove(id);
        if (buffered != null && !buffered.isEmpty()) {
            node.logs.addAll(buffered);
        }
        rebuildUiTestTree();
    }

    public void nodeStarted(int id) {
        Node n = testNodes.get(id);
        if (n != null) {
            n.status = UiModel.Status.RUNNING;
            if (n.kind == KIND_METHOD) {
                currentMethodNodeId = id;
                // Clear previous failure message on new run start for this method
                n.failureMessage = null;
                n.logs.clear();
            }
            rebuildUiTestTree();
        }
    }

    public void nodeFinished(int id, UiModel.Status status, String message) {
        Node n = testNodes.get(id);
        if (n != null) {
            n.status = status;
            if (message != null && !message.isEmpty()) {
                n.failureMessage = message;
            }
            if (n.kind == KIND_METHOD && id == currentMethodNodeId) {
                currentMethodNodeId = 0;
            }
            rebuildUiTestTree();
        }
    }

    public void addTestLog(int id, String message) {
        Node n = testNodes.get(id);
        if (message == null || message.isEmpty()) {
            return;
        }
        if (n != null) {
            n.logs.add(message);
            rebuildUiTestTree();
        } else {
            pendingLogs.computeIfAbsent(id, k -> new ArrayList<>()).add(message);
        }
    }

    private void rebuildUiTestTree() {
        // Build synthetic root suite "Tests" by collecting classes under top-level suites
        List<UiModel.TestClass> classes = new ArrayList<>();
        for (Node root : testNodes.values()) {
            if (root.parentId == 0 || !testNodes.containsKey(root.parentId)) {
                collectClasses(root, classes);
            }
        }
        // Fallback: if no explicit class nodes were emitted, group all methods under a single class.
        if (classes.isEmpty()) {
            List<UiModel.TestMethod> methods = new ArrayList<>();
            for (Node root : testNodes.values()) {
                if (root.parentId == 0 || !testNodes.containsKey(root.parentId)) {
                    collectMethodsRecursively(root, methods);
                }
            }
            if (!methods.isEmpty()) {
                classes.add(new UiModel.TestClass("All tests", methods, aggregateStatusForMethods(methods)));
            }
        }
        UiModel.Status suiteStatus = aggregateStatusForClasses(classes);
        this.testTree = new UiModel.TestSuite("Tests", classes, suiteStatus);
    }

    private void collectClasses(Node node, List<UiModel.TestClass> out) {
        if (node.kind == KIND_CLASS) {
            out.add(toClass(node));
            return;
        }
        for (Integer childId : node.children) {
            Node child = testNodes.get(childId);
            if (child != null) {
                collectClasses(child, out);
            }
        }
    }

    private UiModel.TestClass toClass(Node cls) {
        List<UiModel.TestMethod> methods = new ArrayList<>();
        collectMethodsRecursively(cls, methods);
        UiModel.Status classStatus = aggregateStatusForMethods(methods);
        return new UiModel.TestClass(cls.name, methods, classStatus);
    }

    private void collectMethodsRecursively(Node node, List<UiModel.TestMethod> out) {
        if (node.kind == KIND_METHOD) {
            out.add(new UiModel.TestMethod(
                    node.name,
                    node.displayName,
                    node.status,
                    Optional.ofNullable(node.failureMessage),
                    List.copyOf(node.logs)
            ));
            return;
        }
        for (Integer childId : node.children) {
            Node c = testNodes.get(childId);
            if (c != null) {
                collectMethodsRecursively(c, out);
            }
        }
    }

    private static UiModel.Status aggregateStatusForClasses(List<UiModel.TestClass> classes) {
        UiModel.Status worst = UiModel.Status.PASSED;
        for (UiModel.TestClass c : classes) {
            worst = worseOf(worst, c.status());
        }
        return worst;
    }

    private static UiModel.Status aggregateStatusForMethods(List<UiModel.TestMethod> methods) {
        UiModel.Status worst = UiModel.Status.PASSED;
        if (methods.isEmpty()) {
            return UiModel.Status.PENDING;
        }
        for (UiModel.TestMethod m : methods) {
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
}
