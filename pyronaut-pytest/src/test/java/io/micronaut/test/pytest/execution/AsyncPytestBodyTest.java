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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.platform.engine.EngineExecutionListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AsyncPytestBodyTest {
    @TempDir
    Path tempDir;

    @AfterEach
    @BeforeEach
    void resetRuntime() {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
    }

    @Test
    void invokerWaitsForTheCoroutineBody() throws Exception {
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            Value function = context.eval("python", """
                import asyncio
                completed = False
                async def test_body():
                    global completed
                    await asyncio.wait_for(asyncio.sleep(0.01), timeout=1)
                    completed = True
                test_body
                """);

            PytestFunctionInvoker.Result result = PytestFunctionInvoker.call(function);

            assertTrue(result.success, result.stack);
            assertTrue(context.getBindings("python").getMember("completed").asBoolean(), "the coroutine was actually awaited");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "normal, 0, SUCCESSFUL",
        "assertion, 1, async body ran",
        "python_error, 1, async Python failure",
        "java_error, 1, java.lang.IllegalStateException: async Java failure"
    })
    void pytestAwaitsTheBodyAndReportsItsFailure(String testName, int expectedExitCode, String expectedMessage) throws Exception {
        Path testFile = tempDir.resolve("test_async_body.py");
        Files.writeString(testFile, """
            import asyncio
            import java
            import pytest
            from pyronaut.test import MicronautTest, micronaut_test_fixture

            @pytest.fixture
            def my_context(request):
                fixture = micronaut_test_fixture(request, MicronautTest(transactional=False, start_application=False))
                yield fixture
                fixture.stop()

            @pytest.fixture
            def completed_body():
                events = []
                yield events
                assert events == ["started", "finished"], "the coroutine must finish before fixture cleanup"

            async def finish(events):
                events.append("started")
                await asyncio.wait_for(asyncio.sleep(0.01), timeout=1)
                events.append("finished")

            async def test_normal(completed_body, my_context):
                await finish(completed_body)

            async def test_assertion(completed_body, my_context):
                await finish(completed_body)
                assert False, "async body ran"

            async def test_python_error(completed_body, my_context):
                await finish(completed_body)
                raise ValueError("async Python failure")

            async def test_java_error(completed_body, my_context):
                await finish(completed_body)
                raise java.type("java.lang.IllegalStateException")("async Java failure")
            """, StandardCharsets.UTF_8);
        Path junitXml = tempDir.resolve("junit.xml");
        Path eventsReport = tempDir.resolve("events.ndjson");
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            JUnitPytestTestListener listener = new JUnitPytestTestListener(
                EngineExecutionListener.NOOP,
                Set.of(),
                tempDir.resolve("index.html").toString(),
                tempDir.resolve("last-nodeid.txt").toString(),
                eventsReport.toString()
            );
            Value runner = context.eval("python", "from pyronaut.test import run_pytest\nrun_pytest");

            Value exitCode = runner.execute(new String[]{testFile + "::test_" + testName}, listener, junitXml.toString());

            String report = Files.readString(junitXml, StandardCharsets.UTF_8);
            assertEquals(expectedExitCode, exitCode.asInt(), report);
            assertTrue(Files.readString(eventsReport, StandardCharsets.UTF_8).contains(expectedMessage), report);
            assertFalse(report.contains("async def functions are not natively supported"), report);
            assertFalse(report.contains("the coroutine must finish before fixture cleanup"), report);
            assertFalse(report.contains("ForeignException"), report);
        }
    }
}
