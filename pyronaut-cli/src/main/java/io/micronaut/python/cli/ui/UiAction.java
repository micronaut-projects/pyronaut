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
 *
 * <p>Each implementing record represents a discrete UI event that can be applied to the UI controller
 * to update the {@link UiModel} state.</p>
 */
public sealed interface UiAction permits
    UiAction.StartRunning, UiAction.StopRunning, UiAction.UpdateUri, UiAction.AddEndpoint,
    UiAction.StartCompiling, UiAction.StopCompiling, UiAction.AddCompileOutput, UiAction.UpdateFiles,
    UiAction.StartTesting, UiAction.StopTesting, UiAction.UpdateTestResult, UiAction.AddNotification {

    /**
     * Action indicating the application has started running.
     */
    record StartRunning() implements UiAction { }
    /**
     * Action indicating the application has stopped running.
     */
    record StopRunning() implements UiAction { }
    /**
     * Action to update the current server URI.
     * @param uri the new URI value
     */
    record UpdateUri(String uri) implements UiAction { }
    /**
     * Action to add an endpoint to the UI model.
     * @param endpoint the endpoint string to add
     */
    record AddEndpoint(String endpoint) implements UiAction { }

    /**
     * Action to indicate compilation started.
     */
    record StartCompiling() implements UiAction { }
    /**
     * Action to indicate compilation stopped.
     */
    record StopCompiling() implements UiAction { }
    /**
     * Action to add a line of compiler output to the activity log.
     * @param line single line of compiler output
     */
    record AddCompileOutput(String line) implements UiAction { }
    /**
     * Action to update the list of changed files.
     * @param files list of file paths that were updated
     */
    record UpdateFiles(List<String> files) implements UiAction { }

    /**
     * Action to indicate testing started.
     */
    record StartTesting() implements UiAction { }
    /**
     * Action to indicate testing stopped.
     */
    record StopTesting() implements UiAction { }
    /**
     * Action to update the test tree model.
     * @param updatedTree the updated test tree
     */
    record UpdateTestResult(UiModel.TestTree updatedTree) implements UiAction { }

    /**
     * Action to add a notification to the UI.
     * @param message notification message
     * @param severity notification severity
     */
    record AddNotification(String message, UiModel.Severity severity) implements UiAction { }

}
