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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A top-level test module that declares beans is imported from the application VFS when the context
 * starts, before pytest collects its mirror under {@code __pyronaut__/test-sources}.
 */
@Timeout(60)
class PreloadedTestModuleCollectionTest {
    private static final String MODULE_SOURCE = """
        import builtins

        builtins.preloaded_module_executions = getattr(builtins, "preloaded_module_executions", 0) + 1


        class PreloadedBean:
            pass


        def test_module_is_not_executed_twice():
            assert builtins.preloaded_module_executions == 1
        """;

    @TempDir
    Path tempDir;

    @AfterEach
    @BeforeEach
    void resetRuntime() {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
    }

    @Test
    void collectsATopLevelModuleAlreadyImportedFromAnIdenticalCopy() throws Exception {
        Path vfsCopy = writeModule("vfs", MODULE_SOURCE);
        Path testSource = writeModule("test-sources", MODULE_SOURCE);

        Result result = runPytest(vfsCopy, testSource);

        assertEquals(0, result.exitCode(), result.report());
        assertFalse(result.report().contains("import file mismatch"), result.report());
        assertTrue(result.report().contains("test_module_is_not_executed_twice"), result.report());
    }

    @Test
    void stillRejectsADifferentModuleImportedUnderTheSameName() throws Exception {
        Path vfsCopy = writeModule("vfs", MODULE_SOURCE.replace("PreloadedBean", "OtherBean"));
        Path testSource = writeModule("test-sources", MODULE_SOURCE);

        Result result = runPytest(vfsCopy, testSource);

        assertNotEquals(0, result.exitCode(), result.report());
        assertTrue(result.report().contains("import file mismatch"), result.report());
    }

    private Path writeModule(String directory, String source) throws Exception {
        Path module = tempDir.resolve(directory).resolve("test_preloaded_bean_module.py");
        Files.createDirectories(module.getParent());
        Files.writeString(module, source, StandardCharsets.UTF_8);
        return module;
    }

    private Result runPytest(Path preloadedCopy, Path testSource) throws Exception {
        Path junitXml = tempDir.resolve("junit.xml");
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            // What starting the application context does for a module that declares beans.
            context.getBindings("python").putMember("preloaded_root", preloadedCopy.getParent().toString());
            context.eval("python", """
                import sys
                sys.path.insert(0, preloaded_root)
                import test_preloaded_bean_module
                sys.path.remove(preloaded_root)
                """);
            JUnitPytestTestListener listener = new JUnitPytestTestListener(
                EngineExecutionListener.NOOP,
                Set.of(),
                tempDir.resolve("index.html").toString(),
                tempDir.resolve("last-nodeid.txt").toString(),
                tempDir.resolve("events.ndjson").toString()
            );
            Value runner = context.eval("python", "from pyronaut.test import run_pytest\nrun_pytest");

            Value exitCode = runner.execute(new String[]{testSource.toString()}, listener, junitXml.toString());

            return new Result(exitCode.asInt(), Files.readString(junitXml, StandardCharsets.UTF_8));
        }
    }

    private record Result(int exitCode, String report) {
    }
}
