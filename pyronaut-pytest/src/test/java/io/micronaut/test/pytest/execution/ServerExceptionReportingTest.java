/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.test.pytest.execution;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineExecutionListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
class ServerExceptionReportingTest {
    @TempDir
    Path tempDir;

    @AfterEach
    @BeforeEach
    void resetRuntime() {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
    }

    @Test
    void failingTestReportsTheServerSideException() throws Exception {
        Path testFile = tempDir.resolve("test_server_error.py");
        Files.writeString(testFile, """
            import pytest
            import requests
            from pyronaut.test import MicronautTest, micronaut_test_fixture

            @pytest.fixture
            def client(request):
                fixture = micronaut_test_fixture(request, MicronautTest(transactional=False))
                yield requests.with_context(fixture)
                fixture.stop()

            def test_server_error(client):
                response = client.get("/pytest-server-error")
                assert response.status_code == 200
            """, StandardCharsets.UTF_8);
        Path junitXml = tempDir.resolve("junit.xml");
        Path eventsReport = tempDir.resolve("events.ndjson");
        // As under `pyronaut test`, application logging is captured with the test's output.
        try (ConsoleCapture ignored = ConsoleCapture.install();
             Context context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            JUnitPytestTestListener listener = new JUnitPytestTestListener(
                EngineExecutionListener.NOOP,
                Set.of(),
                tempDir.resolve("index.html").toString(),
                tempDir.resolve("last-nodeid.txt").toString(),
                eventsReport.toString()
            );
            Value runner = context.eval("python", "from pyronaut.test import run_pytest\nrun_pytest");

            Value exitCode = runner.execute(new String[]{testFile + "::test_server_error"}, listener, junitXml.toString());

            String report = Files.readString(junitXml, StandardCharsets.UTF_8);
            String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
            String finished = events.lines()
                .filter(line -> line.contains("\"eventType\":\"test_finished\""))
                .findFirst()
                .orElse("");
            assertEquals(1, exitCode.asInt(), report);
            // The failure itself, not just the captured output, carries the server exception
            // with its Python traceback.
            assertTrue(finished.contains("Server-side exceptions logged during the test"), finished);
            assertTrue(finished.contains("ValueError: deliberate server-side error 4242"), finished);
            assertTrue(finished.contains("at <python>.explode(server_errors.py:5)"), finished);
            assertTrue(finished.contains("at <python>.server_error(server_errors.py:10)"), finished);
            String failure = report.substring(report.indexOf("<failure"), report.indexOf("</failure>"));
            assertTrue(failure.contains("ValueError: deliberate server-side error 4242"), report);
        }
    }
}
