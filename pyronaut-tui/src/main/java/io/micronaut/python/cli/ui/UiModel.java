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
import java.util.List;
import java.util.Optional;

/**
 * Immutable representation of the entire UI state.
 * Uses sealed classes for exhaustive pattern matching and type safety.
 */
public sealed interface UiModel permits UiModel.Running, UiModel.Compiling, UiModel.Testing, UiModel.Idle {

    /** UI idle state. */
    record Idle() implements UiModel { }
  
    /**
     * UI running state.
     *
     * @param uri The server URI
     * @param endpoints The list of available endpoints
     * @param notification Optional notification to display
     */
    record Running(String uri, List<String> endpoints, Optional<Notification> notification) implements UiModel { }
  
    /**
     * UI compiling state.
     *
     * @param updatedFiles The list of files updated by the compiler
     * @param activityLogLines The activity log output lines
     * @param notification Optional notification to display
     */
    record Compiling(List<FileUpdate> updatedFiles, List<String> activityLogLines, Optional<Notification> notification) implements UiModel { }
  
    /**
     * UI testing state.
     *
     * @param testTree The hierarchical test tree to display
     * @param notification Optional notification to display
     */
    record Testing(TestTree testTree, Optional<Notification> notification) implements UiModel { }
  
    /**
     * Represents a notification to display.
     *
     * @param message The notification message
     * @param severity The severity level
     * @param timestamp The time the notification was created
     */
    record Notification(String message, Severity severity, Instant timestamp) { }
 
 
    /** Notification severity levels. */
    enum Severity { INFO, SUCCESS, WARNING, ERROR }
 
    /**
     * Hierarchical representation of test results.
     */
    sealed interface TestTree permits TestSuite, TestClass, TestMethod { }
  
    /**
     * A suite of test classes.
     *
     * @param name The suite name
     * @param classes The list of test classes
     * @param status The aggregated suite status
     */
    record TestSuite(String name, List<TestClass> classes, Status status) implements TestTree { }
  
    /**
     * A test class containing test methods.
     *
     * @param name The class name
     * @param methods The list of test methods
     * @param status The aggregated class status
     */
    record TestClass(String name, List<TestMethod> methods, Status status) implements TestTree { }
  
    /**
     * A single test method result.
     *
     * @param name The method name
     * @param displayName The human-friendly test display name
     * @param status The method status
     * @param failureMessage Optional failure message if any
     * @param logs The list of log lines associated with the method
     */
    record TestMethod(String name, String displayName, Status status, Optional<String> failureMessage, List<String> logs) implements TestTree { }
  
    /** Status of a test or suite. */
    enum Status { PENDING, RUNNING, PASSED, FAILED, SKIPPED }
  
    /** File update types. */
    enum UpdateType { ADDED, MODIFIED, DELETED, CHANGED }
  
    /**
     * A file update in the workspace.
     *
     * @param path The file path
     * @param type The type of change
     */
    record FileUpdate(String path, UpdateType type) { }


}
