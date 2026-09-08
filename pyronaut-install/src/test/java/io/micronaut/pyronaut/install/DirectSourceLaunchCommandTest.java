package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSourceLaunchCommandTest {

    @Test
    void onlyInspectsPathBelowProjectDirectory() {
        Path projectDir = Path.of("tests", "app").toAbsolutePath().normalize();

        assertFalse(DirectSourceLaunchCommand.isTestSource(projectDir, projectDir.resolve("src/app.py")));
        assertFalse(DirectSourceLaunchCommand.isTestSource(projectDir, projectDir.resolve("src/main/java/Application.java")));
        assertTrue(DirectSourceLaunchCommand.isTestSource(projectDir, projectDir.resolve("src/test/java/ApplicationSpec.java")));
        assertTrue(DirectSourceLaunchCommand.isTestSource(projectDir, projectDir.resolve("tests/helpers.py")));
    }

    @Test
    void projectUnderTestsDirectoryStillProducesDevelopmentCommand() {
        Path projectDir = Path.of("tests", "app").toAbsolutePath().normalize();
        List<Path> sources = List.of(projectDir.resolve("app.py"), projectDir.resolve("service.py"));

        DirectSourceLaunchCommand.Commands commands = DirectSourceLaunchCommand.build(projectDir, sources);

        assertTrue(commands.development().startsWith("pyronaut dev "), commands.development());
        assertTrue(commands.development().contains("'app.py'"), commands.development());
        assertNull(commands.test());
    }
}
