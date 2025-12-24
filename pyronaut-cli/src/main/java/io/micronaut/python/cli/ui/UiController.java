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
import java.util.List;
import java.util.Optional;

public final class UiController {

    private String url = null;
    private List<String> endpoints = List.of();
    private UiModel.Notification lastNotification = null;
    private List<String> updatedFiles = List.of();
    private final List<String> compileLogLines = new ArrayList<>();
    private static final int MAX_COMPILE_LINES = 5000;
    private final List<UiModel.Notification> notificationHistory = new ArrayList<>();
    private static final int MAX_NOTIFICATIONS = 500;
    private boolean compiling = false;
    private boolean testing = false;
    private UiModel.TestTree testTree = null;

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

    public List<String> getUpdatedFiles() {
        return updatedFiles;
    }

    public List<String> getCompileLogLines() {
        return compileLogLines;
    }

    public String getCompileLog() {
        return String.join("\n", compileLogLines);
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
            return new UiModel.Compiling(updatedFiles, compileLogLines, notif);
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
    }
    public void setStopped() {
        this.url = null;
        this.compiling = false;
        this.testing = false;
    }
    public void addEndpoint(String endpoint) {
        var newEndpoints = new ArrayList<>(endpoints);
        newEndpoints.add(endpoint);
        endpoints = List.copyOf(newEndpoints);
    }
    public void startCompiling() {
        compiling = true;
        compileLogLines.clear();
    }
    public void stopCompiling() {
        compiling = false;
    }
    public void addCompileOutput(String line) {
        for (String l : line.split("\\r?\\n")) {
            if (!l.isEmpty()) {
                compileLogLines.add(l);
                if (compileLogLines.size() > MAX_COMPILE_LINES) {
                    compileLogLines.remove(0);
                }
            }
        }
    }
    public void updateFiles(List<String> files) {
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
    }
    public void stopTesting() {
        testing = false;
    }
    public void updateTestTree(UiModel.TestTree updatedTree) {
        testTree = updatedTree;
    }
}
