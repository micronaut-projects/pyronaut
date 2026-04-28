/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.test.pytest.listener;

import io.micronaut.test.pytest.PythonAssertionError;
import org.graalvm.polyglot.Value;
import org.junit.platform.engine.TestExecutionResult;



/**
 * Listener interface for pytest test execution events.
 * This interface allows Java code to receive notifications about pytest test execution.
 */
public interface PytestTestListener {

    /**
     * Forward per-test output captured by the pytest plugin.
     * @param testId pytest nodeid (e.g. path/to/test.py::test_name)
     * @param stream one of "stdout", "stderr" or "log"
     * @param text full text captured for this test phase
     */
    void onOutput(String testId, String stream, String text);

    /**
     * Called before pytest starts processing a test file.
     *
     * @param file the path to the Python test file
     */
    void beforeFile(String file);

    /**
     * Called after pytest finishes processing a test file.
     *
     * @param file the path to the Python test file
     * @param result The result
     */
    void afterFile(String file, TestExecutionResult result);

    /**
     * Called before an individual test starts execution.
     *
     * @param testId the unique identifier of the test
     * @param item The pytest function to be executed
     */
    void beforeTest(String testId, Value item);

    /**
     * Called after an individual test finishes execution.
     *
     * @param testId the unique identifier of the test
     * @param item The pytest function that was executed
     * @param result The test execution result
     *
     */
    void afterTest(String testId, Value item, TestExecutionResult result);

    /**
     * Called when test execution is complete.
     *
     * @param result The overall test execution result
     */
    void onResult(TestExecutionResult result);

    /**
     * Build a successful test execution result without requiring Python-side class lookup.
     *
     * @return A successful result
     */
    default TestExecutionResult successfulResult() {
        return TestExecutionResult.successful();
    }

    /**
     * Build a failed test execution result backed by a runtime exception.
     *
     * @param message The failure message
     * @return A failed result
     */
    default TestExecutionResult failedResult(String message) {
        return TestExecutionResult.failed(new RuntimeException(message));
    }

    /**
     * Build a failed test execution result backed by a PythonAssertionError.
     *
     * @param message The failure message
     * @return A failed result
     */
    default TestExecutionResult failedAssertionResult(String message) {
        return TestExecutionResult.failed(new PythonAssertionError(message));
    }
}
