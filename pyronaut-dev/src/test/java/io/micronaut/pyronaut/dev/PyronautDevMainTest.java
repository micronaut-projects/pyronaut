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
import io.micronaut.pyronaut.logback.LogbackConfigurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void parsesDirectSourceInvocation() {
        PyronautDevMain.DirectSourceInvocation invocation = PyronautDevMain.parseDirectSourceArgs(List.of(
            "--test",
            "--port", "8081",
            "--property", "a.b=c",
            "-Dmicronaut.environments=dev",
            "--config", "config/application.toml",
            "--setup", "pyproject.toml",
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

            PyronautDevLogging.initializeApplicationLogging();

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
        Files.writeString(source, "print('ok')\n");
        List<PyronautDevMain.ToolCommand> calls = new ArrayList<>();
        PyronautDevMain.DelegateInvoker invoker = (command, args) -> {
            calls.add(command);
            return 0;
        };

        int exit = PyronautDevMain.execute(
            new String[]{"--test", "--port", "9090", source.toString()},
            invoker,
            (invocation, stagingRoot) -> {
                assertTrue(invocation.test());
                assertTrue(Files.exists(stagingRoot.resolve("src").resolve("hello.py")));
                return 0;
            }
        );

        assertEquals(0, exit);
        assertEquals(List.of(), calls);
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
