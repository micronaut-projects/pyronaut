package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.install.native.binary", matches = ".+")
class PyronautInstallNativeSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryResolvesAndWritesManifests() throws Exception {
        String binaryPath = System.getProperty("pyronaut.install.native.binary");
        Path binary = Path.of(binaryPath);

        Path project = tempDir.resolve("project");
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(project);
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        Process process = new ProcessBuilder(binary.toString(), "--project-dir", project.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode, output);
        Path cacheDir = project.resolve("__pyronaut__");
        Path buildManifest = cacheDir.resolve("resolved-build-dependencies");
        Path runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies");
        Path testManifest = cacheDir.resolve("resolved-test-dependencies");
        assertTrue(Files.exists(buildManifest));
        assertTrue(Files.exists(runtimeManifest));
        assertTrue(Files.exists(testManifest));

        List<String> buildEntries = Files.readAllLines(buildManifest, StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(runtimeManifest, StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(testManifest, StandardCharsets.UTF_8);
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("build-dep")), output);
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")), output);
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")), output);
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")), output);
    }

    @Test
    void nativeBinaryResolvesBomManagedDependencies() throws Exception {
        String binaryPath = System.getProperty("pyronaut.install.native.binary");
        Path binary = Path.of(binaryPath);

        Path project = tempDir.resolve("bom-project");
        Path repository = tempDir.resolve("bom-repo");
        Files.createDirectories(project);
        writeManagedBom(repository, "io.micronaut", "micronaut-core-bom", "5.0.0-SNAPSHOT",
            List.of("io.micronaut:micronaut-http:5.0.0-SNAPSHOT"));
        writeManagedBom(repository, "io.micronaut.platform", "micronaut-platform", "5.0.0-SNAPSHOT", List.of());
        writeArtifact(repository, "io.micronaut", "micronaut-http", "5.0.0-SNAPSHOT");
        Files.writeString(project.resolve("pyproject.toml"), bomManagedPyproject(repository));

        Process process = new ProcessBuilder(binary.toString(), "--project-dir", project.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode, output);
        List<String> runtimeEntries = Files.readAllLines(
            project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"),
            StandardCharsets.UTF_8
        );
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-http-5.0.0-SNAPSHOT.jar")), output);
    }

    @Test
    void nativeBinaryLoadsRuntimeClassesForStubGeneration() throws Exception {
        String binaryPath = System.getProperty("pyronaut.install.native.binary");
        Path binary = Path.of(binaryPath);

        Path project = tempDir.resolve("stubs-project");
        Path repository = tempDir.resolve("stubs-repo");
        Files.createDirectories(project);
        writeCompiledArtifact(
            repository,
            "io.micronaut.demo",
            "demo-stub-artifact",
            "1.0.0",
            "io.micronaut.demo",
            "DemoEndpoint"
        );
        Files.writeString(project.resolve("pyproject.toml"), stubsPyproject(repository));

        Process process = new ProcessBuilder(binary.toString(), "--project-dir", project.toString(), "--no-cache")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode, output);
        Path stubFile = project.resolve("__pyronaut__")
            .resolve("ide-stubs")
            .resolve("micronaut")
            .resolve("demo")
            .resolve("__init__.pyi");
        assertTrue(Files.exists(stubFile), output);
        String stubs = Files.readString(stubFile, StandardCharsets.UTF_8);
        assertTrue(stubs.contains("class DemoEndpoint"), output);
    }

    @Test
    void nativeBinaryRetainsSourceDocstringsForStubGeneration() throws Exception {
        String binaryPath = System.getProperty("pyronaut.install.native.binary");
        Path binary = Path.of(binaryPath);

        Path project = tempDir.resolve("stub-docs-project");
        Path repository = tempDir.resolve("stub-docs-repo");
        Files.createDirectories(project);
        writeCompiledArtifact(
            repository,
            "io.micronaut.demo",
            "demo-stub-docs-artifact",
            "1.0.0",
            "io.micronaut.demo",
            "DemoDocEndpoint",
            """
                package io.micronaut.demo;

                /**
                 * Demo endpoint docs.
                 */
                public class DemoDocEndpoint {
                    /**
                     * Says hello.
                     *
                     * @return A greeting
                     */
                    public String hello() {
                        return "hi";
                    }
                }
                """
        );
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "native-stub-docs-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.test-resources]
            enabled = false

            [tool.pyronaut.ide-stubs]
            enabled = true

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut.demo:demo-stub-docs-artifact:1.0.0"]
            """.formatted(repository.toUri()));

        Process process = new ProcessBuilder(binary.toString(), "--project-dir", project.toString(), "--no-cache")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode, output);
        Path stubFile = project.resolve("__pyronaut__")
            .resolve("ide-stubs")
            .resolve("micronaut")
            .resolve("demo")
            .resolve("__init__.pyi");
        assertTrue(Files.exists(stubFile), output);
        String stubs = Files.readString(stubFile, StandardCharsets.UTF_8);
        assertTrue(stubs.contains("Demo endpoint docs."), output);
        assertTrue(stubs.contains("Says hello."), output);
    }

    private static String pyproject(Path repository) {
        return """
            [project]
            name = "native-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static String bomManagedPyproject(Path repository) {
        return """
            [project]
            name = "native-bom-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            repositories = ["%s"]

            [tool.pyronaut.test-resources]
            enabled = false

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-http"]
            """.formatted(repository.toUri());
    }

    private static String stubsPyproject(Path repository) {
        return """
            [project]
            name = "native-stubs-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.test-resources]
            enabled = false

            [tool.pyronaut.ide-stubs]
            enabled = true

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut.demo:demo-stub-artifact:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version) throws Exception {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        Path pomFile = artifactDir.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(pomFile, """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                     xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version));

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, new byte[]{0});
    }

    private static void writeManagedBom(Path repository,
                                        String groupId,
                                        String artifactId,
                                        String version,
                                        List<String> managedDependencies) throws Exception {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        StringBuilder dependencyManagement = new StringBuilder();
        if (!managedDependencies.isEmpty()) {
            dependencyManagement.append("  <dependencyManagement>\n");
            dependencyManagement.append("    <dependencies>\n");
            for (String coordinate : managedDependencies) {
                String[] parts = coordinate.split(":");
                dependencyManagement.append("""
                        <dependency>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </dependency>
                    """.formatted(parts[0], parts[1], parts[2]));
            }
            dependencyManagement.append("    </dependencies>\n");
            dependencyManagement.append("  </dependencyManagement>\n");
        }

        Path pomFile = artifactDir.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(pomFile, """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                     xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <packaging>pom</packaging>
            %s</project>
            """.formatted(groupId, artifactId, version, dependencyManagement));
    }

    private static void writeCompiledArtifact(Path repository,
                                              String groupId,
                                              String artifactId,
                                              String version,
                                              String packageName,
                                              String className) throws Exception {
        writeCompiledArtifact(
            repository,
            groupId,
            artifactId,
            version,
            packageName,
            className,
            """
                package %s;

                public class %s {
                    public String hello() {
                        return "hi";
                    }
                }
                """.formatted(packageName, className)
        );
    }

    private static void writeCompiledArtifact(Path repository,
                                              String groupId,
                                              String artifactId,
                                              String version,
                                              String packageName,
                                              String className,
                                              String source) throws Exception {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        Path sourceDir = artifactDir.resolve("sources");
        Path classesDir = artifactDir.resolve("classes");
        Path packageDir = sourceDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for native smoke test");
        int compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString());
        assertEquals(0, compileResult, "Failed to compile smoke test artifact");

        Path pomFile = artifactDir.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(pomFile, """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                     xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version), StandardCharsets.UTF_8);

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile))) {
            Path compiledClass = classesDir.resolve(packageName.replace('.', '/')).resolve(className + ".class");
            String entryName = packageName.replace('.', '/') + "/" + className + ".class";
            jarOutputStream.putNextEntry(new JarEntry(entryName));
            jarOutputStream.write(Files.readAllBytes(compiledClass));
            jarOutputStream.closeEntry();
        }

        Path sourceJarFile = artifactDir.resolve(artifactId + "-" + version + "-sources.jar");
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(sourceJarFile))) {
            String entryName = packageName.replace('.', '/') + "/" + className + ".java";
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(Files.readAllBytes(sourceFile));
            outputStream.closeEntry();
        }
    }
}
