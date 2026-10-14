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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestFunctionInvokerTest {

    @Test
    void convertsForeignExceptionToResult() {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            context.eval("python", """
                import java

                IllegalStateException = java.type("java.lang.IllegalStateException")

                def test_function():
                    raise IllegalStateException("boom")
                """);
            Value testFunction = context.getBindings("python").getMember("test_function");

            PytestFunctionInvoker.Result result = PytestFunctionInvoker.call(testFunction);

            assertFalse(result.success);
            assertEquals("java.lang.IllegalStateException", result.exceptionClass);
            assertTrue(result.stack.contains("java.lang.IllegalStateException"));
            assertTrue(result.stack.contains("boom"));
        }
    }

    @Test
    void applicationContextWrapperConvertsLifecycleFailure() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.getBindings("python").putMember("failingContext", new FailingContext());

            context.eval("python", """
                wrapper = ApplicationContextWrapper(failingContext)
                try:
                    wrapper.stop()
                except RuntimeError as e:
                    lifecycle_error = str(e)
                """);

            String error = context.getBindings("python").getMember("lifecycle_error").asString();
            assertTrue(error.contains("Micronaut application context stop failed"));
            assertTrue(error.contains("java.lang.IllegalStateException"));
            assertTrue(error.contains("boom"));
        }
    }

    @Test
    void applicationContextWrapperMapsSnakeCaseModuleClassesToGeneratedBeanNames() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.getBindings("python").putMember("failingContext", new FailingContext());

            context.eval("python", """
                DemoConsumer = type("DemoConsumer", (), {"__module__": "example.micronaut.demo_consumer"})
                wrapper = ApplicationContextWrapper(failingContext)
                lookup_key = wrapper._python_type_to_lookup_key(DemoConsumer)
                """);

            String lookupKey = context.getBindings("python").getMember("lookup_key").asString();
            assertEquals("example.micronaut.DemoConsumer", lookupKey);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "example.micronaut.test_test_client, example.micronaut.TestClient",
        "example.micronaut.test_clients, example.micronaut.test_clients.TestClient",
        "example.test_support.test_client, example.test_support.TestClient",
        "example.micronaut.TestClient, example.micronaut.TestClient"
    })
    void applicationContextWrapperStripsOnlyMatchingTypeModuleNames(String moduleName, String expectedLookupKey) throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.getBindings("python").putMember("module_name", moduleName);

            context.eval("python", """
                TestClient = type("TestClient", (), {"__module__": module_name})
                wrapper = ApplicationContextWrapper(object())
                lookup_key = wrapper._python_type_to_lookup_key(TestClient)
                """);

            assertEquals(expectedLookupKey, context.getBindings("python").getMember("lookup_key").asString());
        }
    }

    @Test
    void applicationContextWrapperFallsBackToTheGeneratedPackageOfAPythonClass() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.getBindings("python").putMember("failingContext", new FailingContext());

            context.eval("python", """
                wrapper = ApplicationContextWrapper(failingContext)
                top_level = wrapper._python_type_lookup_candidates(
                    type("TopLevelBean", (), {"__module__": "test_top_level_beans"}))
                packaged = wrapper._python_type_lookup_candidates(
                    type("MessageService", (), {"__module__": "helloworld.services"}))
                same_name = wrapper._python_type_lookup_candidates(type("Bar", (), {"__module__": "Bar"}))
                top_level = "|".join(top_level)
                packaged = "|".join(packaged)
                same_name = "|".join(same_name)
                """);

            Value bindings = context.getBindings("python");
            assertEquals("test_top_level_beans.TopLevelBean|python.TopLevelBean", bindings.getMember("top_level").asString());
            assertEquals("helloworld.services.MessageService|helloworld.MessageService", bindings.getMember("packaged").asString());
            assertEquals("Bar.Bar|python.Bar", bindings.getMember("same_name").asString());
        }
    }

    @Test
    void applicationContextWrapperResolvesMicronautJavaTypeKeys() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.eval("python", """
                import java

                class _MicronautJavaType:
                    def __init__(self, target, interface=False):
                        self._target = target
                        self._interface = interface

                    def _resolved(self):
                        if isinstance(self._target, str):
                            self._target = java.type(self._target)
                        return self._target

                EmbeddedServer = _MicronautJavaType("java.lang.String", True)
                wrapper = ApplicationContextWrapper(object())
                _, lookup_key = wrapper._resolve_bean_key(EmbeddedServer)
                _, string_lookup_key = wrapper._resolve_bean_key("java.lang.String")
                _, foreign_lookup_key = wrapper._resolve_bean_key(java.type("java.lang.String"))
                """);

            String lookupKey = context.getBindings("python").getMember("lookup_key").asString();
            assertEquals("java.lang.String", lookupKey);
            assertEquals("java.lang.String", context.getBindings("python").getMember("string_lookup_key").asString());
            assertEquals("java.lang.String", context.getBindings("python").getMember("foreign_lookup_key").asString());
        }
    }

    @Test
    void convertsMicronautTestPropertiesToJavaMap() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());

            Value properties = context.eval("python", "to_java_map({'micronaut.security.enabled': 'false'})");
            assertEquals("java.util.LinkedHashMap", properties.getMetaObject().getMetaQualifiedName());
            assertEquals("false", properties.invokeMember("get", "micronaut.security.enabled").asString());
        }
    }

    @Test
    void pytestListenerFiltersFrameworkFramesFromFailureText() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String pytestListener = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_listener.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval("python", """
                import sys

                class _PytestStub:
                    class ExitCode:
                        OK = 0

                    @staticmethod
                    def hookimpl(*args, **kwargs):
                        return lambda function: function

                sys.modules["pytest"] = _PytestStub
                """);
            context.eval(Source.newBuilder("python", pytestListener, "pytest-listener.py").build());

            context.eval("python", """
                failure_text = '''AssertionError: assert 200 == 201
                \tat <python> test_index(test_simple_python.py:27:0)
                \tat org.graalvm.nativeimage.builder/com.oracle.svm.core.reflect.SubstrateMethodAccessor.invoke(SubstrateMethodAccessor.java:117)
                \tat com.oracle.svm.truffle.api.SubstrateOptimizedCallTarget.invokeCallBoundary(SubstrateOptimizedCallTarget.java:124)
                \tat io.micronaut.test.pytest.execution.PytestFunctionInvoker.call(PytestFunctionInvoker.java:33)
                \tat io.micronaut.test.pytest.execution.PytestTestExecutor.execute(PytestTestExecutor.java:123)
                \tat io.micronaut.test.pytest.PytestTestEngine.execute(PytestTestEngine.java:159)
                \tat org.junit.platform.launcher.core.EngineExecutionOrchestrator.executeEngine(EngineExecutionOrchestrator.java:246)
                \tat io.micronaut.pyronaut.test.PyronautTestMain.call(PyronautTestMain.java:212)
                \tat picocli.CommandLine.execute(CommandLine.java:2174)'''
                filtered_failure_text = _filter_internal_traceback_frames(failure_text)
                compacted_assertion_text = _compact_assertion_failure(filtered_failure_text)
                """);

            String filtered = context.getBindings("python").getMember("filtered_failure_text").asString();
            assertTrue(filtered.contains("AssertionError: assert 200 == 201"));
            assertTrue(filtered.contains("test_simple_python.py:27"));
            assertFalse(filtered.contains("PytestFunctionInvoker"));
            assertFalse(filtered.contains("PytestTestExecutor"));
            assertFalse(filtered.contains("PytestTestEngine"));
            assertFalse(filtered.contains("EngineExecutionOrchestrator"));
            assertFalse(filtered.contains("PyronautTestMain"));
            assertFalse(filtered.contains("picocli"));
            assertFalse(filtered.contains("SubstrateMethodAccessor"));
            assertFalse(filtered.contains("SubstrateOptimizedCallTarget"));

            String compacted = context.getBindings("python").getMember("compacted_assertion_text").asString();
            assertEquals("AssertionError: assert 200 == 201", compacted);
            assertFalse(compacted.contains("test_simple_python.py"));
        }
    }

    @Test
    void pytestRunnerUsesProcessedBytecodeAndDisablesPytestCacheProvider() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String pytestRunner = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_runner.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval("python", """
                import sys
                import types

                pytest_module = types.ModuleType("pytest")
                recorded_args = []

                def pytest_main(args, plugins=None):
                    recorded_args.extend(args)
                    return 0

                pytest_module.main = pytest_main
                sys.modules["pytest"] = pytest_module

                pyronaut_module = types.ModuleType("pyronaut")
                pyronaut_test_module = types.ModuleType("pyronaut.test")
                pyronaut_test_module.create_plugin = lambda listener: object()
                sys.modules["pyronaut"] = pyronaut_module
                sys.modules["pyronaut.test"] = pyronaut_test_module
                """);
            context.getBindings("python").putMember("pytest_runner_source", pytestRunner);
            context.eval("python", """
                pytest_runner_globals = {"__name__": "pyronaut.test.pytest_runner"}
                exec(pytest_runner_source, pytest_runner_globals)
                run_pytest = pytest_runner_globals["run_pytest"]
                """);

            context.eval("python", """
                run_pytest(["tests/test_demo.py"], object(), None)
                """);

            Value recordedArgs = context.getBindings("python").getMember("recorded_args");
            assertEquals("--assert=plain", recordedArgs.getArrayElement(0).asString());
            assertEquals("-p", recordedArgs.getArrayElement(1).asString());
            assertEquals("no:cacheprovider", recordedArgs.getArrayElement(2).asString());
            assertEquals("tests/test_demo.py", recordedArgs.getArrayElement(3).asString());
        }
    }

    @Test
    void pytestRunnerAddsVirtualenvSitePackages() throws Exception {
        Path virtualenv = Files.createTempDirectory("graalpy-venv");
        Files.createDirectories(virtualenv.resolve("lib/python3.13/site-packages"));
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String pytestRunner = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_runner.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.getBindings("python").putMember("pytest_runner_source", pytestRunner);
            context.getBindings("python").putMember("virtualenv", virtualenv.toString());
            context.eval("python", """
                import os
                import sys
                from pathlib import Path

                os.environ["VIRTUAL_ENV"] = virtualenv
                pytest_runner_globals = {"__name__": "pyronaut.test.pytest_runner"}
                exec(pytest_runner_source, pytest_runner_globals)
                import types

                pytest_module = types.ModuleType("pytest")
                pytest_module.main = lambda args, plugins=None: 0
                sys.modules["pytest"] = pytest_module
                pyronaut_module = types.ModuleType("pyronaut")
                pyronaut_test_module = types.ModuleType("pyronaut.test")
                pyronaut_test_module.create_plugin = lambda listener: object()
                sys.modules["pyronaut"] = pyronaut_module
                sys.modules["pyronaut.test"] = pyronaut_test_module
                pytest_runner_globals["run_pytest"](["tests/test_demo.py"], object(), None)
                site_packages = str(Path(virtualenv) / "lib" / "python3.13" / "site-packages")
                site_packages_loaded = site_packages in sys.path
                """);
            assertTrue(context.getBindings("python").getMember("site_packages_loaded").asBoolean());
        }
    }

    public static final class FailingContext {
        public void stop() {
            throw new IllegalStateException("boom");
        }
    }
}
