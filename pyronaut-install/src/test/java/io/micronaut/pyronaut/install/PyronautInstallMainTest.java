package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautInstallMainTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAndWritesScopedManifests() throws Exception {
        Path repository = tempDir.resolve("repo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

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
        assertEquals(1, buildEntries.size());
        assertEquals(1, runtimeEntries.size());
        assertEquals(2, testEntries.size());
        assertTrue(buildEntries.getFirst().contains("build-dep"));
        assertTrue(runtimeEntries.getFirst().contains("runtime-dep"));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")));
    }

    @Test
    void unchangedPyprojectUsesCache() throws Exception {
        Path repository = tempDir.resolve("repo-cache");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-cache");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path runtimeManifest = project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies");
        long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        Thread.sleep(25);

        PyronautInstallMain secondRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), secondRun.call());
        long secondModified = Files.getLastModifiedTime(runtimeManifest).toMillis();

        assertEquals(firstModified, secondModified);
    }

    @Test
    void refreshForcesManifestRewrite() throws Exception {
        Path repository = tempDir.resolve("repo-refresh");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-refresh");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path runtimeManifest = project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies");
        long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        Thread.sleep(25L);

        PyronautInstallMain refreshRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        refreshRun.projectDir = project;
        refreshRun.refresh = true;
        assertEquals(InstallExitCode.SUCCESS.code(), refreshRun.call());

        long refreshedModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        assertTrue(refreshedModified > firstModified);
    }

    @Test
    void unresolvedDependencyReturnsResolutionError() throws Exception {
        Path project = tempDir.resolve("project-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:missing:1.0.0"]
            build = []
            test = []
            """.formatted(project.resolve("empty-repo").toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void malformedTomlReturnsConfigError() throws Exception {
        Path project = tempDir.resolve("project-malformed-toml");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project
            name = "broken"
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void invalidDependencyCoordinateReturnsConfigError() throws Exception {
        Path project = tempDir.resolve("project-invalid-coordinate");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "invalid-coordinate"

            [tool.pyronaut]
            repositories = ["mavenLocal"]

            [tool.pyronaut.dependencies]
            runtime = ["invalid-coordinate"]
            build = []
            test = []
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void resolvesVersionlessDependenciesUsingManagedBoms() throws Exception {
        Path repository = tempDir.resolve("repo-managed");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0"),
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0",
            List.of(new ManagedDependency("io.micronaut.test", "micronaut-test-junit5", "5.0.0"))
        );

        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0");
        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0");
        writeArtifact(repository, "io.micronaut.test", "micronaut-test-junit5", "5.0.0");

        Path project = tempDir.resolve("project-managed");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-test"

            [tool.pyronaut]
            version = "5.0.0"
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = ["io.micronaut:micronaut-context-python"]
            test = ["io.micronaut.test:micronaut-test-junit5"]
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> buildEntries = Files.readAllLines(cacheDir.resolve("resolved-build-dependencies"), StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertEquals(1, buildEntries.size());
        assertTrue(buildEntries.getFirst().contains("micronaut-context-python"));

        assertEquals(1, runtimeEntries.size());
        assertTrue(runtimeEntries.getFirst().contains("micronaut-inject-python"));

        assertEquals(2, testEntries.size());
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python")));
    }

    @Test
    void usesPyronautSnapshotVersionForPlatformManagedDependencies() throws Exception {
        Path repository = tempDir.resolve("repo-managed-snapshot");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0-SNAPSHOT",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0-SNAPSHOT",
            List.of(new ManagedDependency("io.micronaut.test", "micronaut-test-junit5", "5.0.0-SNAPSHOT"))
        );

        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "io.micronaut.test", "micronaut-test-junit5", "5.0.0-SNAPSHOT");

        Path project = tempDir.resolve("project-managed-snapshot");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-snapshot-test"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = []
            test = ["io.micronaut.test:micronaut-test-junit5"]
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0-SNAPSHOT")));
    }

    @Test
    void dependenciesModeDefaultsToRuntimeScope() throws Exception {
        Path repository = tempDir.resolve("repo-runtime-default");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");

        Path project = tempDir.resolve("project-runtime-default");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertFalse(output.contains("Dependency tree (build):"));
        assertFalse(output.contains("Dependency tree (test):"));

        Path cacheDir = project.resolve("__pyronaut__");
        assertFalse(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
    }

    @Test
    void scopeAllResolvesAllScopes() throws Exception {
        Path repository = tempDir.resolve("repo-all-scope");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-all-scope");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.scope = "all";

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        assertTrue(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
    }

    @Test
    void invalidScopeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-scope");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.scope = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void invalidProgressModeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-progress");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.progress = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void invalidColorModeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-color");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.color = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void dependenciesModePrintsTreeWithoutWritingManifests() throws Exception {
        Path repository = tempDir.resolve("repo-dependencies-tree");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");

        Path project = tempDir.resolve("project-dependencies-tree");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertTrue(output.contains("com.example:runtime-dep:1.0.0"));

        Path cacheDir = project.resolve("__pyronaut__");
        assertFalse(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
    }

    @Test
    void dependenciesModePrintsResolutionErrorAndReturnsResolutionCode() throws Exception {
        Path repository = tempDir.resolve("repo-tree-failure");
        writeArtifactWithDependencies(
            repository,
            "com.example",
            "runtime-root",
            "1.0.0",
            List.of(new DependencyCoordinate("com.example", "missing-child", "1.0.0"))
        );

        Path project = tempDir.resolve("project-tree-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken-tree"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-root:1.0.0"]
            build = []
            test = []
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertTrue(output.contains("ERROR: Dependency resolution failed for scope 'runtime'"));
        assertTrue(output.contains("com.example:missing-child:1.0.0 [runtime] [ERROR]"));
        assertTrue(output.contains("ERROR path: com.example:runtime-root:1.0.0 -> com.example:missing-child:1.0.0"));
    }

    @Test
    void resolutionErrorIncludesProxySourceSummaryWhenProxyConfigured() throws Exception {
        Path project = tempDir.resolve("project-proxy-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken-proxy-tree"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:missing:1.0.0"]
            build = []
            test = []
            """.formatted(project.resolve("empty-repo").toUri()));

        ProxyConfigurationLoader proxyConfigurationLoader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://proxy.example:3128"),
            tempDir.resolve("missing-pyronaut-settings.toml"),
            tempDir.resolve("missing-m2-settings.xml")
        );
        MavenClasspathResolver resolver = new MavenClasspathResolver(proxyConfigurationLoader);
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("proxy environment -> http://proxy.example:3128"));
    }

    @Test
    void installSucceedsWithProxyConfigurationPresent() throws Exception {
        Path repository = tempDir.resolve("repo-proxy-success");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-proxy-success");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        ProxyConfigurationLoader proxyConfigurationLoader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://proxy.example:3128"),
            tempDir.resolve("missing-pyronaut-settings.toml"),
            tempDir.resolve("missing-m2-settings.xml")
        );
        MavenClasspathResolver resolver = new MavenClasspathResolver(proxyConfigurationLoader);
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        Path cacheDir = project.resolve("__pyronaut__");
        assertTrue(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
    }

    private static String pyproject(Path repository) {
        return """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version) throws IOException {
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

    private static void writeArtifactWithDependencies(Path repository,
                                                      String groupId,
                                                      String artifactId,
                                                      String version,
                                                      List<DependencyCoordinate> dependencies) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        StringBuilder dependencyBlock = new StringBuilder();
        for (DependencyCoordinate dependency : dependencies) {
            dependencyBlock
                .append("    <dependency>\n")
                .append("      <groupId>").append(dependency.groupId()).append("</groupId>\n")
                .append("      <artifactId>").append(dependency.artifactId()).append("</artifactId>\n")
                .append("      <version>").append(dependency.version()).append("</version>\n")
                .append("    </dependency>\n");
        }

        Files.writeString(artifactDir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <dependencies>
            %s  </dependencies>
            </project>
            """.formatted(groupId, artifactId, version, dependencyBlock), StandardCharsets.UTF_8);

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, new byte[]{0});
    }

    private static void writeBom(Path repository,
                                 String groupId,
                                 String artifactId,
                                 String version,
                                 List<ManagedDependency> managedDependencies) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        StringBuilder dependencyManagement = new StringBuilder();
        for (ManagedDependency dependency : managedDependencies) {
            dependencyManagement
                .append("      <dependency>\n")
                .append("        <groupId>").append(dependency.groupId()).append("</groupId>\n")
                .append("        <artifactId>").append(dependency.artifactId()).append("</artifactId>\n")
                .append("        <version>").append(dependency.version()).append("</version>\n")
                .append("      </dependency>\n");
        }

        Files.writeString(
            artifactDir.resolve(artifactId + "-" + version + ".pom"),
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
            %s        </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(groupId, artifactId, version, dependencyManagement),
            StandardCharsets.UTF_8
        );
    }

    private record ManagedDependency(String groupId, String artifactId, String version) {
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }
}
