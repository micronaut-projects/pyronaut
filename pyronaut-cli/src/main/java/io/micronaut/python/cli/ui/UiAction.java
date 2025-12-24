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

import java.util.List;

/**
 * Actions that can update the UI model.
 */
public sealed interface UiAction permits
    UiAction.StartRunning, UiAction.StopRunning, UiAction.UpdateUri, UiAction.AddEndpoint,
    UiAction.StartCompiling, UiAction.StopCompiling, UiAction.AddCompileOutput, UiAction.UpdateFiles,
    UiAction.StartTesting, UiAction.StopTesting, UiAction.UpdateTestResult, UiAction.AddNotification {

    record StartRunning() implements UiAction {}
    record StopRunning() implements UiAction {}
    record UpdateUri(String uri) implements UiAction {}
    record AddEndpoint(String endpoint) implements UiAction {}

    record StartCompiling() implements UiAction {}
    record StopCompiling() implements UiAction {}
    record AddCompileOutput(String line) implements UiAction {}
    record UpdateFiles(List<String> files) implements UiAction {}

    record StartTesting() implements UiAction {}
    record StopTesting() implements UiAction {}
    record UpdateTestResult(UiModel.TestTree updatedTree) implements UiAction {}

    record AddNotification(String message, UiModel.Severity severity) implements UiAction {}
}
