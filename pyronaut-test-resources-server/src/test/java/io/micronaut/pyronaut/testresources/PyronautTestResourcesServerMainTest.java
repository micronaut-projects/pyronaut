package io.micronaut.pyronaut.testresources;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautTestResourcesServerMainTest {

    @TempDir
    Path tempDir;

    @Test
    void startDelegatesAndUsesProjectSettingsDirectoryByDefault() throws Exception {
        Path project = prepareProject("""
            [tool.pyronaut.testResources]
            startupOptimization = "none"
            """);

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute("start", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertEquals(project.resolve(".micronaut/test-resources").toAbsolutePath().normalize(), manager.lastStartRequest.settingsDir());
        assertEquals(project.resolve(".micronaut/test-resources/logs").toAbsolutePath().normalize(), manager.lastStartRequest.logsDir());
        assertEquals(
            project.resolve("__pyronaut__/resolved-test-resources-server-dependencies").toAbsolutePath().normalize(),
            manager.lastStartRequest.classpathManifest()
        );
    }

    @Test
    void statusReportsStoppedWhenNoServerSettings() throws Exception {
        Path project = prepareProject("");
        RecordingServerManager manager = new RecordingServerManager();
        manager.status = new PyronautTestResourcesServerMain.ServerStatus(false, -1, "");

        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);
        int exit = new CommandLine(command).execute("status", "--project-dir", project.toString());

        assertEquals(0, exit);
    }

    @Test
    void startDoesNotEnableCdsWhenTestResourcesAreUnconfigured() throws Exception {
        Path project = prepareProject("");

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute("start", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertNull(manager.lastStartRequest.cdsDir());
    }

    @Test
    void leydenModeFallsBackToCdsDirectory() throws Exception {
        Path project = prepareProject("""
            [tool.pyronaut.testResources]
            startupOptimization = "leyden"
            leydenJvmArgs = ["--enable-preview"]
            """);

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);
        int exit = new CommandLine(command).execute("start", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertTrue(manager.lastStartRequest.cdsDir().toString().contains("__pyronaut__/test-resources-cds"));
    }

    @Test
    void startUsesConfiguredLogsDirectoryWhenPresent() throws Exception {
        Path project = prepareProject("""
            [tool.pyronaut.testResources]
            startupOptimization = "none"
            logsDir = "var/custom-test-resources-logs"
            """);

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute("start", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertEquals(project.resolve("var/custom-test-resources-logs").toAbsolutePath().normalize(), manager.lastStartRequest.logsDir());
    }

    @Test
    void ownerTokenOptionIsAcceptedForOrchestratorCompatibility() throws Exception {
        Path project = prepareProject("");
        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute(
            "start",
            "--project-dir",
            project.toString(),
            "--owner-token",
            "owner-123"
        );

        assertEquals(0, exit);
        assertTrue(manager.lastStartRequest != null);
    }

    @Test
    void killSwitchSkipsServerStartup() throws Exception {
        Path project = prepareProject("");
        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(
            new PyprojectModelReader(),
            manager,
            name -> "PYRONAUT_TEST_RESOURCES_DISABLED".equals(name) ? "true" : null
        );

        int exit = new CommandLine(command).execute("start", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertTrue(manager.lastStartRequest == null);
    }

    @Test
    void stopSkipsWhenOwnerTokenDoesNotMatchPersistedSession() throws Exception {
        Path project = prepareProject("");
        Path sessionFile = project.resolve("__pyronaut__").resolve("test-resources-session.json");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(
            sessionFile,
            """
                {
                  "ownerToken": "owner-expected",
                  "ownerPid": 1,
                  "ownerCommand": "pyronaut run",
                  "startedAt": 1
                }
                """,
            java.nio.charset.StandardCharsets.UTF_8
        );

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);
        int exit = new CommandLine(command).execute("stop", "--project-dir", project.toString(), "--owner-token", "wrong-token");

        assertEquals(0, exit);
        assertEquals(0, manager.stopInvocations);
        assertTrue(Files.exists(sessionFile));
    }

    @Test
    void stopSkipsWhenOwnerTokenProvidedButNoSessionExists() throws Exception {
        Path project = prepareProject("");
        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute("stop", "--project-dir", project.toString(), "--owner-token", "owner-expected");

        assertEquals(0, exit);
        assertEquals(0, manager.stopInvocations);
    }

    @Test
    void stopWithoutOwnerTokenAllowsManualStopWithoutSessionFile() throws Exception {
        Path project = prepareProject("");
        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);

        int exit = new CommandLine(command).execute("stop", "--project-dir", project.toString());

        assertEquals(0, exit);
        assertEquals(1, manager.stopInvocations);
    }

    @Test
    void stopDeletesSessionWhenOwnerTokenMatches() throws Exception {
        Path project = prepareProject("");
        Path sessionFile = project.resolve("__pyronaut__").resolve("test-resources-session.json");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(
            sessionFile,
            """
                {
                  "ownerToken": "owner-expected",
                  "ownerPid": 1,
                  "ownerCommand": "pyronaut run",
                  "startedAt": 1
                }
                """,
            java.nio.charset.StandardCharsets.UTF_8
        );

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);
        int exit = new CommandLine(command).execute("stop", "--project-dir", project.toString(), "--owner-token", "owner-expected");

        assertEquals(0, exit);
        assertEquals(1, manager.stopInvocations);
        assertTrue(!Files.exists(sessionFile));
    }

    @Test
    void stopDeletesServerSettingsFilesWhenOwnerTokenMatches() throws Exception {
        Path project = prepareProject("");
        Path sessionFile = project.resolve("__pyronaut__").resolve("test-resources-session.json");
        Path settingsDir = project.resolve(".micronaut").resolve("test-resources");
        Path portFile = settingsDir.resolve("server.port");
        Path propertiesFile = settingsDir.resolve("test-resources.properties");
        Files.createDirectories(sessionFile.getParent());
        Files.createDirectories(settingsDir);
        Files.writeString(
            sessionFile,
            """
                {
                  "ownerToken": "owner-expected",
                  "ownerPid": 1,
                  "ownerCommand": "pyronaut run",
                  "startedAt": 1
                }
                """,
            java.nio.charset.StandardCharsets.UTF_8
        );
        Files.writeString(portFile, "18080\n", java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(propertiesFile, "server.uri=http\\://localhost\\:18080\n", java.nio.charset.StandardCharsets.UTF_8);

        RecordingServerManager manager = new RecordingServerManager();
        PyronautTestResourcesServerMain command = new PyronautTestResourcesServerMain(new PyprojectModelReader(), manager);
        int exit = new CommandLine(command).execute("stop", "--project-dir", project.toString(), "--owner-token", "owner-expected");

        assertEquals(0, exit);
        assertEquals(1, manager.stopInvocations);
        assertFalse(Files.exists(portFile));
        assertFalse(Files.exists(propertiesFile));
    }

    private Path prepareProject(String additionalPyprojectContent) throws Exception {
        Path project = tempDir.resolve("app");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """ + additionalPyprojectContent);
        return project;
    }

    private static final class RecordingServerManager implements PyronautTestResourcesServerMain.ServerManager {
        private PyronautTestResourcesServerMain.ServerStartRequest lastStartRequest;
        private PyronautTestResourcesServerMain.ServerStatus status = new PyronautTestResourcesServerMain.ServerStatus(true, 18080, "http://localhost:18080");
        private int stopInvocations;

        @Override
        public PyronautTestResourcesServerMain.ServerStatus start(PyronautTestResourcesServerMain.ServerStartRequest request) {
            this.lastStartRequest = request;
            return status;
        }

        @Override
        public PyronautTestResourcesServerMain.ServerStatus status(Path settingsDir) {
            return status;
        }

        @Override
        public boolean stop(Path settingsDir) {
            stopInvocations++;
            return true;
        }
    }
}
