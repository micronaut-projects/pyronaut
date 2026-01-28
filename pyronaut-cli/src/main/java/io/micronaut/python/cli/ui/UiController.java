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

/**
 * Controller that maintains the UI model state for the CLI tooling.
 *
 * <p>This controller stores run, compile and test state, activity logs, notifications and an
 * incremental test tree and exposes methods to update and query that state.</p>
 */
public final class UiController {

    // Static constants first (DeclarationOrder)
    private static final int MAX_ACTIVITY_LINES = 10000;
    private static final int MAX_NOTIFICATIONS = 500;

    // Incremental test tree kinds
    private static final byte KIND_CLASS = 1;
    private static final byte KIND_METHOD = 2;

    // Instance fields
    private String url = null;
    private List<String> endpoints = List.of();
    private UiModel.Notification lastNotification = null;
    private List<UiModel.FileUpdate> updatedFiles = List.of();
    // Unified activity log buffer (kept across state changes)
    private final List<String> activityLogLines = new ArrayList<>();
    private final List<UiModel.Notification> notificationHistory = new ArrayList<>();
    private boolean compiling = false;
    private boolean testing = false;
    private UiModel.TestTree testTree = null;
    private TestSummary lastTestSummary = null;

    private final Map<Integer, Node> testNodes = new HashMap<>();
    private final Map<Integer, List<String>> pendingLogs = new HashMap<>();
    private int currentMethodNodeId = 0;

    /**
     * Returns the node id of the currently running test method, or 0 if none.
     * @return current method node id
     */
    public int getCurrentMethodNodeId() {
        return currentMethodNodeId;
    }

    /**
     * Returns the last generated test summary if present.
     * @return optional last test summary
     */
    public Optional<TestSummary> getLastTestSummary() {
        return Optional.ofNullable(lastTestSummary);
    }

    /**
     * Update the last test summary counters.
     * @param passed passed count
     * @param failed failed count
     * @param skipped skipped count
     * @param running running count
     * @param pending pending count
     */
    public void updateTestSummary(long passed, long failed, long skipped, long running, long pending) {
        this.lastTestSummary = new TestSummary(passed, failed, skipped, running, pending);
    }

    /**
     * Returns the current server URL exposed by the running application, or null if none.
     * @return server URL or null
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the list of discovered endpoints.
     * @return unmodifiable list of endpoints
     */
    public List<String> getEndpoints() {
        return endpoints;
    }

    /**
     * Returns the last notification or null if none.
     * @return last notification or null
     */
    public UiModel.Notification getLastNotification() {
        return lastNotification;
    }

    /**
     * Returns the historic list of notifications.
     * @return notification history
     */
    public List<UiModel.Notification> getNotificationHistory() {
        return notificationHistory;
    }

    /**
     * Returns the most recent file updates.
     * @return list of file updates
     */
    public List<UiModel.FileUpdate> getUpdatedFiles() {
        return updatedFiles;
    }

    /**
     * Returns the activity log lines maintained by the controller.
     * @return list of activity log lines
     */
    public List<String> getActivityLogLines() {
        return activityLogLines;
    }

    /**
     * Returns whether compilation is in progress.
     * @return true if compiling
     */
    public boolean isCompiling() {
        return compiling;
    }

    /**
     * Returns whether testing is in progress.
     * @return true if testing
     */
    public boolean isTesting() {
        return testing;
    }

    /**
     * Returns the current test tree model, or null if none.
     * @return current test tree or null
     */
    public UiModel.TestTree getTestTree() {
        return testTree;
    }

    /**
     * Returns a snapshot model suitable for rendering the UI.
         * @return the active UI model (Running, Compiling or Testing)
     */
    public UiModel getModel() {
        Optional<UiModel.Notification> notif = lastNotification == null ? Optional.empty() : Optional.of(lastNotification);
        if (testing && testTree != null) {
            return new UiModel.Testing(testTree, notif);
        } else if (compiling) {
            return new UiModel.Compiling(updatedFiles, activityLogLines, notif);
        }
        return new UiModel.Running(url, endpoints, notif);
    }

    /**
     * Clears the activity log maintained by the controller.
     */
    public void clearLogs() {
        activityLogLines.clear();
    }

    // Setters/mutators for explicit, clear API (no dispatch switch)

    /**
     * Set the server URL.
     * @param url server URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Clear the stored server URL.
     */
    public void clearUrl() {
        this.url = null;
    }

    /**
     * Set controller to running mode (not compiling nor testing).
     */
    public void setRunning() {
        compiling = false;
        testing = false;
    }

    /**
     * Returns true when controller is in running mode.
     * @return true if running (not compiling or testing)
     */
    public boolean isRunning() {
        return !compiling && !testing;
    }

    /**
     * Mark the application as stopped and clear transient state.
     */
    public void setStopped() {
        this.url = null;
        this.compiling = false;
        this.testing = false;
    }

    /**
     * Reset all controller state including test tree and endpoints.
     */
    public void resetState() {
        this.url = null;
        this.endpoints = List.of();
        this.compiling = false;
        this.testing = false;
        this.lastTestSummary = null;
        resetTestTree();
    }

    /**
     * Replace the known endpoints list.
     *
     * @param eps endpoints to set; must not be {@code null}
     * @throws NullPointerException if {@code eps} is {@code null}
     */
    public void setEndpoints(List<String> eps) {
        this.endpoints = List.copyOf(eps);
    }

    /**
     * Clear the known endpoints.
     */
    public void clearEndpoints() {
        this.endpoints = List.of();
    }

    /**
     * Mark compilation as started.
     */
    public void startCompiling() {
        compiling = true;
    }

    /**
     * Mark compilation as finished.
     */
    public void stopCompiling() {
        compiling = false;
    }

    /**
     * Append activity output lines to the unified activity log buffer.
     * Newlines are split and empty lines ignored.
     *
     * @param line output text (may contain newlines); must not be {@code null}
     * @throws NullPointerException if {@code line} is {@code null}
     */
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

    /**
     * Replace the list of file updates.
     *
     * @param files list of file updates; may be {@code null} to indicate no updates
     */
    public void updateFiles(List<UiModel.FileUpdate> files) {
        updatedFiles = files;
    }

    /**
     * Add a notification to the controller and append it to the history.
     *
     * @param message  notification message; may be {@code null}
     * @param severity notification severity; must not be {@code null}
     * @throws NullPointerException if {@code severity} is {@code null}
     */
    public void notify(String message, UiModel.Severity severity) {
        if (severity == null) {
            throw new NullPointerException("severity");
        }
        UiModel.Notification notif = new UiModel.Notification(message, severity, Instant.now());
        lastNotification = notif;
        notificationHistory.add(notif);
        if (notificationHistory.size() > MAX_NOTIFICATIONS) {
            notificationHistory.remove(0);
        }
    }

    /**
     * Prepare the controller for a testing session and clear previous test state.
     */
    public void startTesting() {
        testing = true;
        this.lastTestSummary = null;
        testNodes.clear();
        this.testTree = new UiModel.TestSuite("Tests", List.of(), UiModel.Status.PENDING);
    }

    /**
     * Stop the testing session.
     */
    public void stopTesting() {
        testing = false;
    }

    // ---- Test tree incremental API ----

    /**
     * Reset the incremental test tree state.
     */
    public void resetTestTree() {
        testNodes.clear();
        testTree = null;
    }

    /**
     * Add a test node to the incremental test tree.
     *
     * @param id          node id
     * @param parentId    parent node id (0 if root)
     * @param kind        node kind (KIND_CLASS or KIND_METHOD)
     * @param name        internal name for the node; may be {@code null}
     * @param displayName display name for the UI; may be {@code null}
     */
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

    /**
     * Mark a node as started (running).
     * @param id node id
     */
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

    /**
     * Mark a node as finished with a given status and optional failure message.
     *
     * @param id node id
     * @param status final status
     * @param message optional failure message
     */
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

    /**
     * Append a log message to a test node; if the node is not yet known, buffer the log for later attachment.
     *
     * @param id      node id
     * @param message log message; must not be {@code null} or empty
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public void addTestLog(int id, String message) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        Node n = testNodes.get(id);
        if (message.isEmpty()) {
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

    // Inner types last (InnerTypeLast)
    /**
     * Internal node representing a test suite/class/method in the incremental test tree.
     *
     * <p>This class is package-private to the controller and is used to build the UI test tree
     * structure before converting to {@link UiModel} views.</p>
     */
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

    /**
     * Summary of test counts.
     *
     * @param passed number passed
     * @param failed number failed
     * @param skipped number skipped
     * @param running number running
     * @param pending number pending
     */
    public record TestSummary(long passed, long failed, long skipped, long running, long pending) { }
}
