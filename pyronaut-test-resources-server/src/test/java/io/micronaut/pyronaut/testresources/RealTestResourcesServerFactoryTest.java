package io.micronaut.pyronaut.testresources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealTestResourcesServerFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsLaunchSpecFromManifestAndRequest() throws Exception {
        Path firstJar = tempDir.resolve("libs/one.jar").toAbsolutePath().normalize();
        Path secondJar = tempDir.resolve("libs/two.jar").toAbsolutePath().normalize();
        Files.createDirectories(firstJar.getParent());

        CapturingStarter starter = new CapturingStarter();
        PyronautTestResourcesServerMain.ServerStartRequest request = new PyronautTestResourcesServerMain.ServerStartRequest(
            tempDir.resolve(".micronaut/test-resources"),
            tempDir.resolve(".micronaut/test-resources/logs"),
            tempDir.resolve(".micronaut/test-resources/server.port"),
            tempDir.resolve("manifest-not-used-here"),
            null,
            "token-123",
            null,
            30,
            15,
            Map.of("custom.flag", "yes"),
            Map.of("PYRONAUT_TRACE", "true"),
            true,
            "java"
        );
        RealTestResourcesServerFactory factory = new RealTestResourcesServerFactory(request, starter);

        factory.startServer(new StubProcessParameters(tempDir.resolve("port.file"), firstJar.toFile(), secondJar.toFile()));
        factory.waitFor(Duration.ofMillis(1));

        List<String> command = starter.command;
        assertEquals("java", command.getFirst());
        assertTrue(command.contains("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"));
        assertTrue(command.contains("-Dpyronaut.test-resources.logs-dir=" + tempDir.resolve(".micronaut/test-resources/logs").toAbsolutePath().normalize()));
        assertTrue(command.stream().anyMatch(token -> token.startsWith("-Dlogback.configurationFile=") && token.contains("pyronaut-test-resources-logback.xml")));
        assertTrue(command.contains("-Dmicronaut.server.port=18080"));
        assertTrue(command.contains("-Dserver.access-token=token-123"));
        assertTrue(command.contains("-Dserver.idle.timeout.minutes=15"));
        assertTrue(command.contains("-Dcustom.flag=yes"));
        assertTrue(command.contains("io.micronaut.testresources.server.TestResourcesService"));
        assertTrue(command.contains("--port-file=" + tempDir.resolve("port.file").toAbsolutePath()));

        int cpIndex = command.indexOf("-cp");
        assertTrue(cpIndex > 0);
        assertEquals(firstJar + File.pathSeparator + secondJar, command.get(cpIndex + 1));
        assertEquals("true", starter.environment.get("PYRONAUT_TRACE"));
    }

    @Test
    void deadProcessFailsWait() throws Exception {
        PyronautTestResourcesServerMain.ServerStartRequest request = new PyronautTestResourcesServerMain.ServerStartRequest(
            tempDir.resolve(".micronaut/test-resources"),
            tempDir.resolve(".micronaut/test-resources/logs"),
            tempDir.resolve(".micronaut/test-resources/server.port"),
            tempDir.resolve("unused"),
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of(),
            false,
            "java"
        );
        CapturingStarter starter = new CapturingStarter();
        starter.returnDeadProcess = true;
        RealTestResourcesServerFactory factory = new RealTestResourcesServerFactory(request, starter);
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> factory.startServer(new StubProcessParameters(tempDir.resolve("port.file"), tempDir.resolve("libs/one.jar").toFile()))
        );
        assertTrue(error.getMessage().contains("terminated"));
    }

    private static final class CapturingStarter implements RealTestResourcesServerFactory.ProcessStarter {
        private List<String> command = new ArrayList<>();
        private Map<String, String> environment = new HashMap<>();
        private boolean returnDeadProcess;

        @Override
        public Process start(List<String> command, Map<String, String> environment) {
            this.command = new ArrayList<>(command);
            this.environment = new HashMap<>(environment);
            return new StubProcess(!returnDeadProcess);
        }
    }

    private static final class StubProcessParameters implements io.micronaut.testresources.buildtools.ServerUtils.ProcessParameters {
        private final List<File> classpath;
        private final Path portFile;

        private StubProcessParameters(Path portFile, File... classpath) {
            this.portFile = portFile;
            this.classpath = List.of(classpath);
        }

        @Override
        public String getMainClass() {
            return "io.micronaut.testresources.server.TestResourcesService";
        }

        @Override
        public Map<String, String> getSystemProperties() {
            return Map.of("micronaut.server.port", "18080", "server.access-token", "token-123", "server.idle.timeout.minutes", "15");
        }

        @Override
        public List<File> getClasspath() {
            return classpath;
        }

        @Override
        public List<String> getArguments() {
            return List.of("--port-file=" + portFile.toAbsolutePath());
        }

        @Override
        public List<String> getJvmArguments() {
            return List.of("-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1");
        }
    }

    private static final class StubProcess extends Process {
        private boolean alive;

        private StubProcess(boolean alive) {
            this.alive = alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
