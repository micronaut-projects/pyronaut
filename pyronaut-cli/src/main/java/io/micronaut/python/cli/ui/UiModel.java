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

    record Idle() implements UiModel {}

    record Running(String uri, List<String> endpoints, Optional<Notification> notification) implements UiModel {}

    record Compiling(List<String> updatedFiles, List<String> compileLogLines, Optional<Notification> notification) implements UiModel {}

    record Testing(TestTree testTree, Optional<Notification> notification) implements UiModel {}

    /**
     * Represents a notification to display.
     */
    record Notification(String message, Severity severity, Instant timestamp) {}

    enum Severity { INFO, SUCCESS, WARNING, ERROR }

    /**
     * Hierarchical representation of test results.
     */
    sealed interface TestTree permits TestSuite, TestClass, TestMethod {}

    record TestSuite(String name, List<TestClass> classes, Status status) implements TestTree {}

    record TestClass(String name, List<TestMethod> methods, Status status) implements TestTree {}

    record TestMethod(String name, String displayName, Status status, Optional<String> failureMessage) implements TestTree {}

    enum Status { PENDING, RUNNING, PASSED, FAILED, SKIPPED }
}
