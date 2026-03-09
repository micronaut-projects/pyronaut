package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyprojectModelReaderTest {

    @TempDir
    Path tempDir;

    private final PyprojectModelReader reader = new PyprojectModelReader();

    @Test
    void parseCanonicalPyproject() throws URISyntaxException {
        var fixture = PyprojectModelReaderTest.class.getResource("/fixtures/pyproject.toml");
        assertNotNull(fixture);
        PyprojectModel model = reader.readFile(Path.of(fixture.toURI()));

        assertEquals("pyronaut-demo", model.project().name());
        assertEquals("1.0.0", model.project().version());
        assertEquals("setuptools.build_meta", model.buildSystem().buildBackend());
        assertEquals("5.0.0-SNAPSHOT", model.pyronaut().version());
        assertEquals(3, model.pyronaut().repositories().size());
        assertEquals(8, model.pyronaut().dependencies().runtime().size());
        assertEquals(2, model.pyronaut().dependencies().build().size());
        assertEquals(4, model.pyronaut().dependencies().test().size());
    }

    @Test
    void rejectInvalidFilename() throws IOException {
        Path file = tempDir.resolve("pyroject.toml");
        Files.writeString(file, "[project]\nname='x'\n");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid config filename 'pyroject.toml'. Expected 'pyproject.toml'", exception.getMessage());
    }

    @Test
    void rejectMissingFile() {
        Path file = tempDir.resolve("pyproject.toml");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Missing required file 'pyproject.toml' at: " + file, exception.getMessage());
    }

    @Test
    void parseLegacyDependencyKeys() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            annotationProcessor = ["io.micronaut:micronaut-context-python"]

            [tool.pyronaut.dependencies]
            compile = ["io.micronaut:micronaut-inject-python"]
            test = ["org.junit.jupiter:junit-jupiter-engine"]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(1, model.pyronaut().dependencies().runtime().size());
        assertEquals("io.micronaut:micronaut-inject-python", model.pyronaut().dependencies().runtime().getFirst());
        assertEquals(1, model.pyronaut().dependencies().build().size());
        assertEquals("io.micronaut:micronaut-context-python", model.pyronaut().dependencies().build().getFirst());
    }

    @Test
    void rejectInvalidToml() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, "[project\nname = \"broken\"\n");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertTrue(exception.getMessage().startsWith("Invalid TOML in "));
    }

    @Test
    void rejectInvalidRepositoriesType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            repositories = "mavenCentral"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.repositories': expected array", exception.getMessage());
    }
}
