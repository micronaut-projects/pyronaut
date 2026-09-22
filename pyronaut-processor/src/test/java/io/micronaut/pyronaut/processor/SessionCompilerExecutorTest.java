package io.micronaut.pyronaut.processor;

import io.micronaut.python.compiler.PythonIncrementalMode;
import io.micronaut.python.processing.PythonAstParser;
import io.micronaut.python.processing.PythonProcessingSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiler daemon keeps the Python processing session, and with it the GraalPy context, from one
 * compilation of a project to the next: the context is half the cost of a cold compilation.
 */
class SessionCompilerExecutorTest {

    @TempDir
    Path directory;

    @Test
    void reusesTheSessionAndItsContextAcrossCompilationsOfAProject() throws Exception {
        Path python = Files.createDirectories(directory.resolve("src/main/python"));
        Path java = Files.createDirectories(directory.resolve("src/main/java"));
        Path target = directory.resolve("classes");
        Path cache = directory.resolve("incremental");
        Path source = python.resolve("greeter.py");
        Files.writeString(source, """
            from jakarta.inject import Singleton

            @Singleton
            class Greeter:
                def greet(self, name: str) -> str:
                    return "Hello " + name
            """, StandardCharsets.UTF_8);
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .filter(entry -> !entry.isBlank())
            .map(Path::of)
            .toList();

        try (CompilerDaemon.SessionCompilerExecutor executor = new CompilerDaemon.SessionCompilerExecutor()) {
            executor.compile(request(python, java, target, cache, classpath));
            assertEquals(1, executor.sessions().size());
            PythonProcessingSession session = executor.sessions().values().iterator().next();
            assertTrue(session.initialized());
            PythonAstParser parser = session.parser(getClass().getClassLoader(), false);

            Files.writeString(source, Files.readString(source).replace("Hello", "Hi"), StandardCharsets.UTF_8);
            executor.compile(request(python, java, target, cache, classpath));

            assertEquals(1, executor.sessions().size(), "the same project compiles through the same session");
            assertSame(session, executor.sessions().values().iterator().next());
            assertSame(parser, session.parser(getClass().getClassLoader(), false), "the GraalPy context stayed warm");
            assertTrue(Files.readString(target.resolve("META-INF/GRAALPY-VFS/micronaut-application/src/greeter.py")).contains("Hi"));
        }
    }

    private static PyronautCompilerExecutor.CompileRequest request(Path python, Path java, Path target, Path cache, List<Path> classpath) {
        return new PyronautCompilerExecutor.CompileRequest(
            python, java, target, classpath, classpath, false, false,
            PythonIncrementalMode.CONSERVATIVE, cache, List.of(), null
        );
    }
}
