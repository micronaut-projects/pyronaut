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
package io.micronaut.pyronaut.dev;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micronaut.pyronaut.dev.runtime.PyronautDevTestResourcesPropertySourceLoader;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarationRequest;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarations;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarationsVisitor;
import io.micronaut.pyronaut.logback.LogbackConfigurer;
import io.micronaut.python.processing.PythonCall;
import io.micronaut.python.processing.PythonSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PyronautDevMainTest {
    @Test
    void dispatchesCoveredToolCommandsInProcess() {
        List<String> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command.name() + " " + String.join(" ", args));
            return 0;
        };

        assertEquals(0, PyronautDevMain.execute(new String[]{"install", "--project-dir", "demo"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"process", "--project-dir", "demo"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"validate-config", "--project-dir", "demo"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"test-resources-server", "status", "--project-dir", "demo"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"run", "--project-dir", "demo"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"test", "--project-dir", "demo"}, invoker));

        assertEquals(List.of(
            "INSTALL --project-dir demo",
            "PROCESS --project-dir demo",
            "VALIDATE_CONFIG --project-dir demo",
            "TEST_RESOURCES_SERVER status --project-dir demo",
            "RUN --project-dir demo",
            "TEST --project-dir demo"
        ), calls);
    }

    @Test
    void retainsNativeProvidedPolyglotApiForDirectCompilation(@TempDir Path tempDir) throws IOException {
        Path polyglot = tempDir.resolve("polyglot-25.3.4.1.jar");
        Path application = tempDir.resolve("application-1.0.jar");
        Files.createFile(polyglot);
        Files.createFile(application);
        String previous = System.getProperty("pyronaut.dev.native.provided.artifacts");
        try {
            System.setProperty(
                "pyronaut.dev.native.provided.artifacts",
                "org.graalvm.polyglot:polyglot"
            );
            PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of("App.py"));

            // ValueCoercible extends ProxyObject from this native-provided API jar.
            List<Path> processorClasspath = PyronautDevMain.filterDirectSourcePaths(
                List.of(polyglot, application), invocation, false, false
            );
            List<Path> compileClasspath = PyronautDevMain.filterDirectSourcePaths(
                List.of(polyglot, application), invocation, false, false
            );
            assertEquals(
                List.of(polyglot, application),
                processorClasspath
            );
            assertEquals(
                List.of(polyglot, application),
                compileClasspath
            );
            assertEquals(
                List.of(application),
                PyronautDevMain.filterDirectSourcePaths(List.of(polyglot, application), invocation, false, true)
            );
        } finally {
            if (previous == null) {
                System.clearProperty("pyronaut.dev.native.provided.artifacts");
            } else {
                System.setProperty("pyronaut.dev.native.provided.artifacts", previous);
            }
        }
    }

    @Test
    void keepsSelectedProjectTestsWithTheTestDelegate() {
        List<String> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command.name() + " " + String.join(" ", args));
            return 0;
        };

        assertEquals(0, PyronautDevMain.execute(new String[]{"test", "--tests", "tests/test_app.py"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"test", "--tests=tests/test_app.py::test_index"}, invoker));
        assertEquals(0, PyronautDevMain.execute(new String[]{"test", "--tests", "test_*"}, invoker));

        assertEquals(List.of(
            "TEST --tests tests/test_app.py",
            "TEST --tests=tests/test_app.py::test_index",
            "TEST --tests test_*"
        ), calls);
    }

    @Test
    void routesRunSourceToDirectExecution(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Foo.java");
        Files.writeString(source, "class Foo {}\n");
        List<PyronautDevMain.ToolCommand> calls = new ArrayList<>();

        int exit = PyronautDevMain.execute(
            new String[]{"run", source.toString()},
            (command, args) -> {
                calls.add(command);
                return 0;
            },
            (invocation, stagingRoot) -> 0
        );

        assertEquals(0, exit);
        assertEquals(List.of(), calls);
    }

    @Test
    void isolatesSinglePythonSourceFromSiblingModules(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("controller.py");
        Path unrelated = tempDir.resolve("unrelated.py");
        Files.writeString(source, "def index(): pass\n");
        Files.writeString(unrelated, "class Unrelated: pass\n");
        List<Path> stagingRoots = new ArrayList<>();

        assertEquals(0, PyronautDevMain.execute(
            new String[]{"run", source.toString()},
            (command, args) -> 0,
            (invocation, stagingRoot) -> {
                stagingRoots.add(stagingRoot);
                assertTrue(Files.isRegularFile(stagingRoot.resolve("src/controller.py")));
                assertFalse(Files.exists(stagingRoot.resolve("src/unrelated.py")));
                return 0;
            }
        ));

        assertEquals(List.of(tempDir.resolve("__pyronaut__")), stagingRoots);
    }

    @Test
    void parsesDirectSourceInvocation() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectTestSourceArgs(List.of(
            "--port", "8081",
            "--property", "a.b=c",
            "-Dmicronaut.environments=dev",
            "--config", "config/application.toml",
            "--setup", "pyproject.toml",
            "--compile-python-bytecode",
            "src/HelloController.py",
            "--",
            "tests/test_hello.py"
        ));

        assertTrue(invocation.test());
        assertEquals(Path.of("pyproject.toml"), invocation.setup());
        assertEquals(List.of(Path.of("config/application.toml")), invocation.configs());
        assertEquals(List.of(Path.of("src/HelloController.py")), invocation.sources());
        assertEquals(List.of(Path.of("tests/test_hello.py")), invocation.testSources());
        assertEquals("8081", invocation.properties().get("micronaut.server.port"));
        assertEquals("c", invocation.properties().get("a.b"));
        assertEquals("dev", invocation.properties().get("micronaut.environments"));
        assertEquals("true", invocation.properties().get("pyronaut.dev.compile-python-bytecode"));
        assertTrue(invocation.testSourceSeparator());
    }

    @Test
    void directTestsUseImplicitLanguageSpecificRecursiveSelectors(@TempDir Path tempDir) throws IOException {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path javaTest = nested.resolve("NestedTest.java");
        Path pythonTest = nested.resolve("NestedTest.py");
        Path pythonPytest = nested.resolve("test_nested.py");
        Files.writeString(javaTest, "class NestedTest {}\n");
        Files.writeString(pythonTest, "class NestedTest: pass\n");
        Files.writeString(pythonPytest, "def test_nested(): pass\n");

        assertEquals(List.of(javaTest), PyronautDevMain.findImplicitTestSources(tempDir, PyronautDevMain.SourceType.JAVA));
        assertEquals(List.of(pythonTest, pythonPytest), PyronautDevMain.findImplicitTestSources(tempDir, PyronautDevMain.SourceType.PYTHON));

        PyronautDevMain.DirectSourceInvocation javaInvocation = PyronautDevMain.parseDirectTestSourceArgs(List.of("App.java"));
        PyronautDevMain.DirectSourceInvocation pythonInvocation = PyronautDevMain.parseDirectTestSourceArgs(List.of("App.py"));
        assertTrue(javaInvocation.testSources().isEmpty());
        assertTrue(pythonInvocation.testSources().isEmpty());
        assertFalse(javaInvocation.testSourceSeparator());
        assertFalse(pythonInvocation.testSourceSeparator());
    }

    @Test
    void dispatchesDirectTestsWithDirectorySource(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("main.py");
        Path testSource = tempDir.resolve("test_main.py");
        Files.writeString(source, "print('ok')\n");
        Files.writeString(testSource, "def test_main(): pass\n");
        List<PyronautDevMain.DirectSourceInvocation> invocations = new ArrayList<>();

        assertEquals(0, PyronautDevMain.execute(
            new String[]{"test", tempDir.toString(), "--", testSource.toString()},
            (command, arguments) -> 0,
            (invocation, stagingRoot) -> {
                invocations.add(invocation);
                return 0;
            }
        ));

        assertEquals(1, invocations.size());
        PyronautDevMain.DirectSourceInvocation invocation = invocations.getFirst();
        assertTrue(invocation.test());
        assertEquals(List.of(tempDir), invocation.sources());
        assertEquals(List.of(testSource), invocation.testSources());
    }

    @Test
    void implicitPythonTestDiscoveryMatchesDocumentedPatternsAndPrunesToolDirectories(@TempDir Path tempDir) throws IOException {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path pythonTest = nested.resolve("NestedTest.py");
        Path pythonPytest = nested.resolve("test_nested.py");
        Files.writeString(pythonTest, "class NestedTest: pass\n");
        Files.writeString(pythonPytest, "def test_nested(): pass\n");
        Files.writeString(nested.resolve("conftest.py"), "pass\n");
        Files.writeString(nested.resolve("latest.py"), "pass\n");
        Files.writeString(nested.resolve("lowercasetest.py"), "pass\n");
        for (String pruned : List.of(".git", ".venv", "venv", "node_modules", "__pyronaut__", ".hidden")) {
            Path directory = Files.createDirectories(tempDir.resolve(pruned));
            Files.writeString(directory.resolve("test_pruned.py"), "def test_pruned(): pass\n");
            Files.writeString(directory.resolve("PrunedTest.java"), "class PrunedTest {}\n");
        }

        assertEquals(List.of(pythonTest, pythonPytest), PyronautDevMain.findImplicitTestSources(tempDir, PyronautDevMain.SourceType.PYTHON));
        assertEquals(List.of(), PyronautDevMain.findImplicitTestSources(tempDir, PyronautDevMain.SourceType.JAVA));
    }

    @Test
    void bareReportOptionUsesDefaultReportLocation() {
        PyronautDevMain.DirectSourceInvocation bare = PyronautDevMain.parseDirectTestSourceArgs(List.of("--report", "App.py"));
        PyronautDevMain.DirectSourceInvocation explicit = PyronautDevMain.parseDirectTestSourceArgs(List.of("--report", "out/reports", "App.py"));
        PyronautDevMain.DirectSourceInvocation none = PyronautDevMain.parseDirectTestSourceArgs(List.of("App.py"));

        assertEquals(none.report(), bare.report());
        assertEquals(List.of(Path.of("App.py")), bare.sources());
        assertEquals(Path.of("out/reports"), explicit.report());
    }

    @Test
    void directTestWithoutApplicationSourcesReturnsUsageError() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            assertEquals(2, PyronautDevMain.execute(new String[]{"test", "--", "FooTest.py"}, (command, arguments) -> 0));
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("application source"));
    }

    @Test
    void exclusionKeysUseGroupAndArtifactForVersionlessCoordinates() {
        assertEquals("io.micronaut:micronaut-http", PyronautDevMain.moduleKey("io.micronaut:micronaut-http"));
        assertEquals("io.micronaut:micronaut-http", PyronautDevMain.moduleKey("io.micronaut:micronaut-http:4.0.0"));
        assertEquals("io.micronaut:micronaut-http", PyronautDevMain.moduleKey("io.micronaut:micronaut-http:jar:4.0.0"));
        assertEquals("micronaut-http", PyronautDevMain.moduleKey("micronaut-http"));
    }

    @Test
    void javaTestClassDiscoveryIgnoresCommentedTypeNames() {
        String stripped = PyronautDevMain.stripJavaComments("""
            package demo;
            /** Docs mention class Ignored and interface AlsoIgnored. */
            // class LineIgnored
            class RealTest { String url = "http://example.com"; /* class Inline */ }
            """);

        assertTrue(stripped.contains("class RealTest"));
        assertTrue(stripped.contains("http://example.com"));
        assertFalse(stripped.contains("Ignored"));
        assertFalse(stripped.contains("Inline"));
    }

    @Test
    void explicitEmptyTestSourceSeparatorIsPreserved() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectTestSourceArgs(List.of("App.java", "--"));

        assertTrue(invocation.testSources().isEmpty());
        assertTrue(invocation.testSourceSeparator());
    }

    @Test
    void scopesVerboseLoggingToLoggerWhenValueIsSpecified() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of(
            "--verbose=io.micronaut.context", "App.java"
        ));

        assertTrue(invocation.verbose());
        assertEquals("io.micronaut.context", invocation.verboseLogger());
    }

    @Test
    void scopesVerboseLoggingToCommaSeparatedLoggersWhenValueIsSeparateArgument() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of(
            "--verbose", "regex,com.oracle.graal.python.runtime", "App.java"
        ));

        assertTrue(invocation.verbose());
        assertEquals("regex,com.oracle.graal.python.runtime", invocation.verboseLogger());
        assertEquals(List.of(Path.of("App.java")), invocation.sources());
    }

    @Test
    void detectsDirectSourceLanguage(@TempDir Path tempDir) throws IOException {
        Path javaSource = tempDir.resolve("Foo.java");
        Path pythonSource = tempDir.resolve("foo.py");
        Files.writeString(javaSource, "class Foo {}\n");
        Files.writeString(pythonSource, "print('ok')\n");

        assertEquals(PyronautDevMain.SourceType.JAVA, PyronautDevMain.sourceType(List.of(javaSource)));
        assertEquals(PyronautDevMain.SourceType.PYTHON, PyronautDevMain.sourceType(List.of(pythonSource)));
    }

    @Test
    void enablesOpenApiForDirectApplicationsButNotTests() {
        assertTrue(PyronautDevMain.directSourceCompilerOptions(false).stream()
            .noneMatch(option -> option.equals("-Amicronaut.openapi.enabled=false")));
        assertTrue(PyronautDevMain.directSourceCompilerOptions(true).contains("-Amicronaut.openapi.enabled=false"));

        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of("App.java"));
        assertEquals("swagger-ui.enabled=true,redoc.enabled=true", invocation.properties().get("micronaut.openapi.views.spec"));
    }

    @Test
    void directSourceCacheUsesWorkingDirectoryWithoutPyproject(@TempDir Path tempDir) {
        String previous = System.getProperty("pyronaut.dev.project.dir");
        try {
            System.setProperty("pyronaut.dev.project.dir", tempDir.toString());
            PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of("App.java"));
            assertEquals(tempDir.resolve("__pyronaut__"), PyronautDevMain.projectCacheDirectory(invocation, Path.of("/tmp/staging")));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previous);
        }
    }

    @Test
    void directSourceCacheUsesExternalBuildOutputDirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        String previous = System.getProperty("pyronaut.dev.project.dir");
        try {
            System.setProperty("pyronaut.dev.project.dir", tempDir.toString());
            PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of("App.java"));
            assertEquals(tempDir.resolve("build/pyronaut"), PyronautDevMain.projectCacheDirectory(invocation, Path.of("/tmp/staging")));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previous);
        }
    }

    @Test
    void stagesJavaSourcesUnderConfiguredProjectDirectory(@TempDir Path tempDir) throws IOException {
        Path projectDirectory = Files.createDirectories(tempDir.resolve("project"));
        Path sourceDirectory = Files.createDirectories(projectDirectory.resolve("example"));
        Path source = sourceDirectory.resolve("App.java");
        Files.writeString(source, "package example; class App {}\n");
        String previous = System.getProperty("pyronaut.dev.project.dir");
        try {
            System.setProperty("pyronaut.dev.project.dir", projectDirectory.toString());
            int exit = PyronautDevMain.execute(
                new String[]{source.toString()},
                (command, args) -> 0,
                (invocation, stagingRoot) -> {
                    assertEquals(projectDirectory.resolve("__pyronaut__"), stagingRoot);
                    assertTrue(Files.isRegularFile(stagingRoot.resolve("src/App.java")));
                    assertTrue(Files.notExists(sourceDirectory.resolve("__pyronaut__")));
                    return 0;
                }
            );
            assertEquals(0, exit);
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previous);
        }
    }

    @Test
    void collectsPythonDirectSourceDeclarationsFromAstMetadata() {
        DirectSourceDeclarationsVisitor visitor = new DirectSourceDeclarationsVisitor();
        visitor.visit(new PythonSource("app.py", "python", List.of(
            new PythonCall("Dependency", List.of(), Map.of("group", "org.apache.commons", "module", "commons-lang3", "version", "3.20.0")),
            new PythonCall("AppConfig", List.of(), Map.of("name", "example.value", "value", "ok"))
        )), null);

        DirectSourceDeclarationRequest request = assertThrows(DirectSourceDeclarationRequest.class, () -> visitor.finish(null));

        assertEquals("org.apache.commons:commons-lang3:3.20.0", request.declarations().dependencies().getFirst().coordinate());
        assertEquals("ok", request.declarations().runtimeProperties().get("example.value"));
    }

    @Test
    void retriesDirectSourceAfterResolutionAndReusesCachedManifests(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("App.java");
        Files.writeString(source, "class App {}\n");
        String previousProjectDirectory = System.getProperty("pyronaut.dev.project.dir");
        String previousRuntimeProperty = System.getProperty("example.value");
        String previousBuildProperty = System.getProperty("micronaut.processing.processor.option");
        AtomicInteger calls = new AtomicInteger();
        try {
            System.setProperty("pyronaut.dev.project.dir", tempDir.toString());
            System.setProperty("example.value", "original-runtime");
            System.setProperty("micronaut.processing.processor.option", "original-build");
            PyronautDevMain.DirectSourceRunner runner = (invocation, stagingRoot) -> {
                if (calls.incrementAndGet() % 2 == 1) {
                    throw new DirectSourceDeclarationRequest(new DirectSourceDeclarations(
                        List.of(),
                        List.of(),
                        Map.of("processor.option", "enabled"),
                        Map.of("example.value", "ok")
                    ));
                }
                assertEquals("enabled", System.getProperty("micronaut.processing.processor.option"));
                assertEquals("ok", DirectSourceDeclarationState.runtimeProperties().get("example.value"));
                return 0;
            };

            String[] args = {
                "-Dexample.value=command-runtime",
                "-Dmicronaut.processing.processor.option=command-build",
                source.toString()
            };
            assertEquals(0, PyronautDevMain.execute(args, (command, arguments) -> 0, runner));
            assertEquals("original-runtime", System.getProperty("example.value"));
            assertEquals("original-build", System.getProperty("micronaut.processing.processor.option"));
            Path runtimeManifest = tempDir.resolve("__pyronaut__/resolved-runtime-dependencies");
            assertTrue(Files.isRegularFile(runtimeManifest));
            long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();

            assertEquals(0, PyronautDevMain.execute(args, (command, arguments) -> 0, runner));
            assertEquals(firstModified, Files.getLastModifiedTime(runtimeManifest).toMillis());
            assertEquals("original-runtime", System.getProperty("example.value"));
            assertEquals("original-build", System.getProperty("micronaut.processing.processor.option"));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previousProjectDirectory);
            restoreProperty("example.value", previousRuntimeProperty);
            restoreProperty("micronaut.processing.processor.option", previousBuildProperty);
        }
    }

    @Test
    void directRunNeverActivatesInferredTestResources(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("App.java");
        Files.writeString(source, "class App {}\n");
        String previousProjectDirectory = System.getProperty("pyronaut.dev.project.dir");
        String previousTestResourcesBridge = System.getProperty(
            PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
        );
        AtomicInteger calls = new AtomicInteger();
        try {
            System.setProperty("pyronaut.dev.project.dir", tempDir.toString());
            System.setProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY, "true");
            PyronautDevMain.DirectSourceRunner runner = (invocation, stagingRoot) -> {
                if (calls.incrementAndGet() == 1) {
                    throw new DirectSourceDeclarationRequest(mysqlDeclarations());
                }
                assertEquals("false", System.getProperty(
                    PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
                ));
                assertNull(System.getProperty("pyronaut.dev.test.resources.client.classpath"));
                return 0;
            };

            assertEquals(0, PyronautDevMain.execute(
                new String[]{"run", source.toString()},
                (command, args) -> 0,
                runner
            ));
            assertFalse(Files.exists(tempDir.resolve(
                "__pyronaut__/resolved-test-resources-server-dependencies"
            )));
            assertFalse(Files.readAllLines(tempDir.resolve(
                "__pyronaut__/resolved-runtime-dependencies"
            )).stream().anyMatch(path -> path.contains("micronaut-test-resources-client")));
            assertEquals("true", System.getProperty(
                PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
            ));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previousProjectDirectory);
            restoreProperty(
                PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY,
                previousTestResourcesBridge
            );
        }
    }

    @Test
    void disableTestResourcesPreventsDirectDevInference(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("App.java");
        Files.writeString(source, "class App {}\n");
        String previousProjectDirectory = System.getProperty("pyronaut.dev.project.dir");
        String previousDirectCommand = System.getProperty("pyronaut.dev.direct.command");
        String previousTestResourcesBridge = System.getProperty(
            PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
        );
        AtomicInteger calls = new AtomicInteger();
        try {
            System.setProperty("pyronaut.dev.project.dir", tempDir.toString());
            System.setProperty("pyronaut.dev.direct.command", "dev");
            System.setProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY, "true");
            PyronautDevMain.DirectSourceRunner runner = (invocation, stagingRoot) -> {
                if (calls.incrementAndGet() == 1) {
                    throw new DirectSourceDeclarationRequest(mysqlDeclarations());
                }
                assertEquals("false", System.getProperty(
                    PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
                ));
                assertNull(System.getProperty("pyronaut.dev.test.resources.client.classpath"));
                return 0;
            };

            assertEquals(0, PyronautDevMain.execute(
                new String[]{"--disable-test-resources", source.toString()},
                (command, args) -> 0,
                runner
            ));
            assertFalse(Files.exists(tempDir.resolve(
                "__pyronaut__/resolved-test-resources-server-dependencies"
            )));
            assertEquals("true", System.getProperty(
                PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY
            ));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previousProjectDirectory);
            restoreProperty("pyronaut.dev.direct.command", previousDirectCommand);
            restoreProperty(
                PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY,
                previousTestResourcesBridge
            );
        }
    }

    private static DirectSourceDeclarations mysqlDeclarations() {
        return new DirectSourceDeclarations(
            List.of(
                new DirectSourceDeclarations.Dependency(
                    "io.micronaut.data:micronaut-data-jdbc",
                    false
                ),
                new DirectSourceDeclarations.Dependency(
                    "io.micronaut.sql:micronaut-jdbc-hikari",
                    false
                ),
                new DirectSourceDeclarations.Dependency(
                    "com.mysql:mysql-connector-j",
                    false
                )
            ),
            List.of(),
            Map.of(),
            Map.of("datasources.default.db-type", "mysql")
        );
    }

    @Test
    void disablesPythonForDirectJavaSourcesAndRestoresProperty(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Foo.java");
        Files.writeString(source, "class Foo {}\n");
        Path staleTestSource = tempDir.resolve("__pyronaut__/src/FooTest.java");
        Files.createDirectories(staleTestSource.getParent());
        Files.writeString(staleTestSource, "class FooTest {}\n");
        String previous = System.getProperty("micronaut.python.enabled");
        try {
            System.clearProperty("micronaut.python.enabled");
            int exit = PyronautDevMain.execute(
                new String[]{source.toString()},
                (command, args) -> 0,
                (invocation, stagingRoot) -> {
                    assertEquals("false", System.getProperty("micronaut.python.enabled"));
                    assertTrue(Files.exists(stagingRoot.resolve("src/Foo.java")));
                    assertTrue(Files.notExists(stagingRoot.resolve("src/FooTest.java")));
                    return 0;
                }
            );
            assertEquals(0, exit);
            assertEquals(previous, System.getProperty("micronaut.python.enabled"));
        } finally {
            restoreProperty("micronaut.python.enabled", previous);
        }
    }

    @Test
    void configuresNettyUnsafeDefaultsWithoutOverridingUserProperties() {
        String previousNettyNoUnsafe = System.getProperty("io.netty.noUnsafe");
        String previousUnsafeMemoryAccess = System.getProperty("sun.misc.unsafe.memory.access");
        try {
            System.clearProperty("io.netty.noUnsafe");
            System.clearProperty("sun.misc.unsafe.memory.access");

            PyronautDevMain.configureNativeRuntimeDefaults();

            assertEquals("false", System.getProperty("io.netty.noUnsafe"));
            assertEquals("allow", System.getProperty("sun.misc.unsafe.memory.access"));

            System.setProperty("io.netty.noUnsafe", "true");
            System.setProperty("sun.misc.unsafe.memory.access", "deny");

            PyronautDevMain.configureNativeRuntimeDefaults();

            assertEquals("true", System.getProperty("io.netty.noUnsafe"));
            assertEquals("deny", System.getProperty("sun.misc.unsafe.memory.access"));
        } finally {
            restoreProperty("io.netty.noUnsafe", previousNettyNoUnsafe);
            restoreProperty("sun.misc.unsafe.memory.access", previousUnsafeMemoryAccess);
        }
    }

    @Test
    void verifiesSystemClassAndClassResourceWhenRequested() {
        String previousClass = System.getProperty("pyronaut.dev.verify-system-class");
        String previousResource = System.getProperty("pyronaut.dev.verify-system-class-resource");
        try {
            System.setProperty("pyronaut.dev.verify-system-class", PyronautDevMainTest.class.getName());
            System.setProperty(
                "pyronaut.dev.verify-system-class-resource",
                "/" + PyronautDevMainTest.class.getName().replace('.', '/') + ".class"
            );

            assertEquals(0, PyronautDevMain.verifySystemClassIfRequested());
        } finally {
            restoreProperty("pyronaut.dev.verify-system-class", previousClass);
            restoreProperty("pyronaut.dev.verify-system-class-resource", previousResource);
        }
    }

    @Test
    void initializesQuietLauncherLoggingAndAllowsApplicationReconfiguration() {
        String previousSimpleLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        String previousStatusListener = System.getProperty("logback.statusListenerClass");
        try {
            System.clearProperty("org.slf4j.simpleLogger.defaultLogLevel");
            System.clearProperty("logback.statusListenerClass");

            PyronautDevMain.initializeLauncherLogging();

            assertEquals("warn", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
            assertEquals("ch.qos.logback.core.status.NopStatusListener", System.getProperty("logback.statusListenerClass"));
            Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.WARN, rootLogger.getLevel());
            assertTrue(rootLogger.iteratorForAppenders().hasNext());

            LogbackConfigurer.configure(Map.of(
                "root", Map.of("level", "DEBUG")
            ));

            assertEquals(Level.DEBUG, rootLogger.getLevel());
        } finally {
            restoreProperty("org.slf4j.simpleLogger.defaultLogLevel", previousSimpleLevel);
            restoreProperty("logback.statusListenerClass", previousStatusListener);
        }
    }

    @Test
    void initializesDirectApplicationLoggingAtInfoLevel() {
        String previousStatusListener = System.getProperty("logback.statusListenerClass");
        try {
            System.clearProperty("logback.statusListenerClass");

            PyronautDevLogging.initializeApplicationLogging(false);

            assertEquals("ch.qos.logback.core.status.NopStatusListener", System.getProperty("logback.statusListenerClass"));
            Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.INFO, rootLogger.getLevel());
            assertTrue(rootLogger.iteratorForAppenders().hasNext());
        } finally {
            restoreProperty("logback.statusListenerClass", previousStatusListener);
        }
    }

    @Test
    void directSourceTestRunsInMemoryRunnerWithoutInstall(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("hello.py");
        Path test = tempDir.resolve("HelloTest.py");
        Files.writeString(source, "class Hello: pass\n");
        Files.writeString(test, "from org.junit.jupiter.api import Test\nclass HelloTest:\n    @Test\n    def test_ok(self):\n        pass\n");
        List<PyronautDevMain.ToolCommand> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command);
            return 0;
        };

        int exit = PyronautDevMain.execute(
            new String[]{"test", "--port", "9090", source.toString(), "--", test.toString()},
            invoker,
            (invocation, stagingRoot) -> {
                assertTrue(invocation.test());
                assertTrue(Files.exists(stagingRoot.resolve("src").resolve("hello.py")));
                assertTrue(Files.exists(stagingRoot.resolve("src").resolve("HelloTest.py")));
                return 0;
            }
        );

        assertEquals(0, exit);
        assertEquals(List.of(), calls);
    }

    @Test
    void executesDirectJavaJUnitTestsInMemory(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("App.java");
        Path test = tempDir.resolve("AppTest.java");
        Files.writeString(source, "package demo;\nimport io.micronaut.http.annotation.Controller;\nimport io.micronaut.http.annotation.Get;\n@Controller class Routes { @Get(\"/hello\") String hello() { return \"Hello World\"; } }\n");
        Files.writeString(test, "package demo;\nimport io.micronaut.http.client.HttpClient;\nimport io.micronaut.http.client.annotation.Client;\nimport io.micronaut.test.extensions.junit5.annotation.MicronautTest;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n@MicronautTest class AppTest { @Test void passes(@Client(\"/\") HttpClient client) { assertEquals(\"Hello World\", client.toBlocking().retrieve(\"/hello\")); } }\n");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            int exit = PyronautDevMain.execute(
                new String[]{"test", source.toString(), "--", test.toString()}
            );
            assertEquals(0, exit);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("1 test passed"), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test
    void reportsDirectJavaContainerFailuresAndLinksTheReport(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("App.java");
        Path test = tempDir.resolve("AppTest.java");
        Files.writeString(source, "package demo; class App {}\n");
        Files.writeString(test, """
            package demo;
            import org.junit.jupiter.api.BeforeAll;
            import org.junit.jupiter.api.Test;
            class AppTest {
                @BeforeAll
                static void failContainer() {
                    throw new IllegalStateException("container startup exploded");
                }
                @Test
                void neverStarts() {
                }
            }
            """);
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            int exit = PyronautDevMain.execute(
                new String[]{"test", source.toString(), "--", test.toString()}
            );

            Path reportDirectory = tempDir.resolve("__pyronaut__/reports/tests");
            Path htmlReport = reportDirectory.resolve("index.html");
            Path xmlReport = reportDirectory.resolve("junit.xml");
            String testOutput = output.toString(StandardCharsets.UTF_8);
            assertEquals(1, exit, testOutput);
            assertTrue(testOutput.contains("No tests ran, 1 error"), testOutput);
            assertTrue(testOutput.contains("Test report:"), testOutput);
            assertTrue(testOutput.contains(htmlReport.toString()), testOutput);
            assertTrue(Files.isRegularFile(htmlReport));
            assertTrue(Files.isRegularFile(xmlReport));
            assertTrue(Files.readString(htmlReport).contains("container startup exploded"));
            assertTrue(Files.readString(htmlReport).contains("Errors: 1"));
            assertTrue(Files.readString(xmlReport).contains("errors=\"1\""));
            assertTrue(Files.readString(xmlReport).contains("java.lang.IllegalStateException: container startup exploded"));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test
    void executesDirectJavaTestsWithInlineDeclarations(@TempDir Path tempDir) throws IOException {
        Path projectDirectory = Files.createDirectories(tempDir.resolve("project"));
        Path sourceDirectory = Files.createDirectories(tempDir.resolve("src/example"));
        Path testDirectory = Files.createDirectories(tempDir.resolve("test"));
        Files.writeString(sourceDirectory.resolve("App.java"), """
            package example;
            @pyronaut.build.Dependency(group = "org.apache.commons", module = "commons-lang3", version = "3.20.0")
            @pyronaut.build.AppConfig(name = "example.value", value = "configured")
            class App {}
            """);
        Files.writeString(testDirectory.resolve("AppTest.java"), """
            package example;
            import io.micronaut.context.ApplicationContext;
            import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
            import org.apache.commons.lang3.StringUtils;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            @MicronautTest class AppTest {
                @Test void declarationsWork(ApplicationContext context) {
                    assertEquals("OK", StringUtils.upperCase("ok"));
                    assertEquals("configured", context.getProperty("example.value", String.class).orElseThrow());
                }
            }
            """);
        String previousProjectDirectory = System.getProperty("pyronaut.dev.project.dir");
        try {
            System.setProperty("pyronaut.dev.project.dir", projectDirectory.toString());
            assertEquals(0, PyronautDevMain.execute(new String[]{
                "test", sourceDirectory.resolve("App.java").toString(),
                "--", testDirectory.resolve("AppTest.java").toString()
            }));
            assertTrue(Files.isRegularFile(projectDirectory.resolve("__pyronaut__/resolved-runtime-dependencies")));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previousProjectDirectory);
        }
    }

    @Test
    void reportFlagUsesDefaultDirectoryWhenFollowedByApplicationSource() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectTestSourceArgs(List.of(
            "--report", "App.java", "--", "AppTest.java"
        ));

        // A bare --report resolves to the same default as no --report (under the direct-source cache directory).
        assertNull(invocation.report());
        assertEquals(List.of(Path.of("App.java")), invocation.sources());
        assertEquals(List.of(Path.of("AppTest.java")), invocation.testSources());
    }

    @Test
    void executesDirectPythonJUnitTestsInMemory(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("app.py");
        Path test = tempDir.resolve("AppTest.py");
        Files.writeString(source, "class App:\n    pass\n");
        Files.writeString(test, "from micronaut.test.extensions.junit5.annotation import MicronautTest\nfrom org.junit.jupiter.api import Test\n@MicronautTest\nclass AppTest:\n    @Test\n    def passes(self):\n        assert True\n");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            int exit = PyronautDevMain.execute(
                new String[]{"test", source.toString(), "--", test.toString()}
            );
            String testOutput = output.toString(StandardCharsets.UTF_8);
            assertEquals(0, exit, testOutput);
            assertTrue(testOutput.contains("PASSED AppTest.passes()"), testOutput);
            assertTrue(testOutput.contains("1 test passed in"), testOutput);
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test
    void executesDirectPythonTestsWithInlineDeclarations(@TempDir Path tempDir) throws IOException {
        Path projectDirectory = Files.createDirectories(tempDir.resolve("project"));
        Path source = tempDir.resolve("app.py");
        Path test = tempDir.resolve("AppTest.py");
        Files.writeString(source, """
            from pyronaut.build import Dependency, AppConfig
            Dependency(group="org.apache.commons", module="commons-lang3", version="3.20.0")
            AppConfig(name="example.value", value="configured")
            class App:
                pass
            """);
        Files.writeString(test, """
            from micronaut.test.extensions.junit5.annotation import MicronautTest
            from org.apache.commons.lang3 import StringUtils
            from org.junit.jupiter.api import Test
            @MicronautTest
            class AppTest:
                @Test
                def declarations_work(self):
                    assert StringUtils.upperCase("ok") == "OK"
            """);
        String previousProjectDirectory = System.getProperty("pyronaut.dev.project.dir");
        try {
            System.setProperty("pyronaut.dev.project.dir", projectDirectory.toString());
            assertEquals(0, PyronautDevMain.execute(new String[]{"test", source.toString(), "--", test.toString()}));
            assertTrue(Files.isRegularFile(projectDirectory.resolve("__pyronaut__/resolved-runtime-dependencies")));
        } finally {
            restoreProperty("pyronaut.dev.project.dir", previousProjectDirectory);
        }
    }

    @Test
    void directSourceRunsInMemoryRunnerWithoutDefaultPyproject(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("hello.py");
        Path config = tempDir.resolve("application.toml");
        Files.writeString(source, "print('ok')\n");
        Files.writeString(config, "example.value = 'ok'\n");
        List<PyronautDevMain.ToolCommand> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command);
            return 0;
        };
        String previousPort = System.getProperty("micronaut.server.port");
        String previousTestResourcesBridge = System.getProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY);

        int exit = PyronautDevMain.execute(
            new String[]{"--port", "9090", "--config", config.toString(), source.toString()},
            invoker,
            (invocation, stagingRoot) -> {
                assertEquals("9090", System.getProperty("micronaut.server.port"));
                assertEquals("false", System.getProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY));
                assertEquals(List.of(config), invocation.configs());
                assertTrue(Files.notExists(stagingRoot.resolve("pyproject.toml")));
                assertTrue(Files.exists(stagingRoot.resolve("src").resolve("hello.py")));
                assertTrue(Files.exists(stagingRoot.resolve("config").resolve("application.toml")));
                return 0;
            }
        );

        assertEquals(0, exit);
        assertEquals(List.of(), calls);
        assertEquals(previousPort, System.getProperty("micronaut.server.port"));
        assertEquals(previousTestResourcesBridge, System.getProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY));
    }

    @Test
    void directSourceRunsInstallWhenSetupIsProvided(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("hello.py");
        Path setup = tempDir.resolve("pyproject.toml");
        Files.writeString(source, "print('ok')\n");
        Files.writeString(setup, "[project]\nname = 'demo'\nversion = '0.1.0'\n");
        List<PyronautDevMain.ToolCommand> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command);
            return 0;
        };

        int exit = PyronautDevMain.execute(
            new String[]{"--setup", setup.toString(), source.toString()},
            invoker,
            (invocation, stagingRoot) -> {
                assertEquals(setup, invocation.setup());
                assertTrue(Files.exists(stagingRoot.resolve("pyproject.toml")));
                assertTrue(Files.exists(stagingRoot.resolve("src").resolve("hello.py")));
                return 0;
            }
        );

        assertEquals(0, exit);
        assertEquals(List.of(PyronautDevMain.ToolCommand.INSTALL), calls);
    }

    @Test
    void directSourceLauncherClassLoaderHidesJarBackedApplicationVfsFileslists(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("micronaut-context-python.jar");
        writeJar(
            jar,
            Map.of(
                "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt", "/META-INF/GRAALPY-VFS/micronaut-application/src/micronaut_asyncio.py\n",
                "META-INF/example.txt", "ok\n"
            )
        );

        try (URLClassLoader parent = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            ClassLoader classLoader = new PyronautDevMain.DirectSourceLauncherClassLoader(parent);

            assertEquals(
                List.of(),
                Collections.list(classLoader.getResources("META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt"))
            );
            assertEquals(1, Collections.list(classLoader.getResources("META-INF/example.txt")).size());
        }
    }

    private static void writeJar(Path jar, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
