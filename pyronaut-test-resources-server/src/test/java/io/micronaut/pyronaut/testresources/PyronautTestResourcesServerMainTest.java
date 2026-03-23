package io.micronaut.pyronaut.testresources;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            return true;
        }
    }
}
