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
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectSourceInstallTest {
    @Test
    void cliAcceptsPositionalDirectSource(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("cli-project");
        Path localRepository = tempDir.resolve("cli-maven-local");
        Files.createDirectories(project);
        writeEmptyPlatformBom(localRepository);
        Files.writeString(project.resolve("App.java"), "class App {}\n");

        int exitCode = new CommandLine(new PyronautInstallMain()).execute(
            "--project-dir", project.toString(),
            "--local-repository", localRepository.toString(),
            "App.java"
        );

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode);
        assertTrue(Files.isRegularFile(project.resolve(".classpath")));
        assertTrue(Files.readString(project.resolve(".vscode/launch.json"))
            .contains("\"command\":\"pyronaut dev 'App.java'\""));
        assertFalse(Files.exists(project.resolve(".idea/runConfigurations/Pyronaut_Test_Direct_Sources.xml")));
    }

    @Test
    void buildInstallResolvesDirectSourcesWithoutIdeSupport(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("build-project");
        Path localRepository = tempDir.resolve("build-maven-local");
        Files.createDirectories(project);
        writeEmptyPlatformBom(localRepository);
        Files.writeString(project.resolve("App.java"), "class App {}\n");

        int exitCode = new CommandLine(new PyronautInstallMain()).execute(
            "--project-dir", project.toString(),
            "--local-repository", localRepository.toString(),
            "--no-ide-support",
            "App.java"
        );

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode);
        assertTrue(Files.isRegularFile(project.resolve("__pyronaut__/resolved-runtime-dependencies")));
        assertFalse(Files.exists(project.resolve(".classpath")));
        assertFalse(Files.exists(project.resolve(".vscode")));
        assertFalse(Files.exists(project.resolve(".idea")));
    }

    @Test
    void javaSourcesUseManagedCoreWithoutPythonRuntime(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("core-project");
        Path localRepository = tempDir.resolve("core-maven-local");
        Files.createDirectories(project);
        writeEmptyBom(localRepository, "io.micronaut.platform", "micronaut-platform", PyronautManagedVersions.micronautPlatformVersion());
        String coreVersion = PyronautManagedVersions.micronautCoreVersion();
        Path coreBom = localRepository.resolve("io/micronaut/micronaut-core-bom").resolve(coreVersion);
        Files.createDirectories(coreBom);
        Files.writeString(coreBom.resolve("micronaut-core-bom-" + coreVersion + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.micronaut</groupId>
              <artifactId>micronaut-core-bom</artifactId>
              <version>%1$s</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.micronaut</groupId>
                    <artifactId>micronaut-context-python</artifactId>
                    <version>%1$s</version>
                  </dependency>
                  <dependency>
                    <groupId>io.micronaut</groupId>
                    <artifactId>micronaut-inject-python</artifactId>
                    <version>%1$s</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """.formatted(coreVersion), StandardCharsets.UTF_8);
        writeArtifact(localRepository, "io.micronaut", "micronaut-context-python", coreVersion);
        writeArtifact(localRepository, "io.micronaut", "micronaut-inject-python", coreVersion);
        Files.writeString(project.resolve("App.java"), "class App {}\n");

        int exitCode = new CommandLine(new PyronautInstallMain()).execute(
            "--project-dir", project.toString(),
            "--local-repository", localRepository.toString(),
            "--offline",
            "--no-ide-support",
            "App.java"
        );

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode);
        assertFalse(Files.readString(project.resolve("__pyronaut__/resolved-runtime-dependencies"))
            .contains("micronaut-context-python"));
        assertFalse(Files.readString(project.resolve("__pyronaut__/resolved-build-dependencies"))
            .contains("micronaut-inject-python"));
    }

    @Test
    void installsJavaSourcesAndMergesEditorMetadata(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path repository = tempDir.resolve("repository");
        Files.createDirectories(project);
        writeEmptyPlatformBom(repository);
        writeArtifact(repository, "com.example", "direct-dependency", "1.0.0");
        Files.writeString(project.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
              <classpathentry kind="src" path="user-source"/>
              <classpathentry kind="lib" path="/stale.jar">
                <attributes>
                  <attribute name="pyronaut.direct-source" value="true"/>
                </attributes>
              </classpathentry>
            </classpath>
            """);
        Files.createDirectories(project.resolve(".idea"));
        Files.writeString(project.resolve(".idea/modules.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectModuleManager">
                <modules>
                  <module fileurl="file://$PROJECT_DIR$/user.iml" filepath="$PROJECT_DIR$/user.iml"/>
                  <module fileurl="file://old/pyronaut-direct-source.iml" filepath="old/pyronaut-direct-source.iml"/>
                </modules>
              </component>
            </project>
            """);
        Files.createDirectories(project.resolve(".vscode"));
        Files.writeString(project.resolve(".vscode/settings.json"), """
            {
              "editor.formatOnSave": true,
              "java.project.sourcePaths": ["stale-source"],
              "java.project.referencedLibraries": ["/stale.jar"]
            }
            """);
        Files.writeString(project.resolve(".vscode/launch.json"), """
            {
              "version": "0.2.0",
              "configurations": [
                {
                  "name": "User Launch",
                  "type": "node-terminal",
                  "request": "launch",
                  "command": "user-command"
                },
                {
                  "name": "Pyronaut: Run Direct Sources",
                  "type": "node-terminal",
                  "request": "launch",
                  "command": "stale-command"
                }
              ]
            }
            """);
        Files.createDirectories(project.resolve(".idea/runConfigurations"));
        Files.writeString(project.resolve(".idea/runConfigurations/User.xml"), "<user-configuration/>\n");
        Files.writeString(project.resolve("App.java"), """
            @pyronaut.build.MavenRepository("%s")
            @pyronaut.build.Dependency(group = "com.example", module = "direct-dependency", version = "1.0.0")
            class App {
            }
            """.formatted(repository.toUri()));
        Files.writeString(project.resolve("AppTest.java"), "class AppTest {}\n");

        List<Path> toolRepositories = new java.util.ArrayList<>();
        PyronautInstallMain command = new PyronautInstallMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            new MavenClasspathResolver(),
            new PyprojectEditorSupport(),
            new PythonEditorSupport(),
            new ExternalBuildResolver(),
            (model, localRepository, offline, refresh, progressListener) -> {
                assertEquals(null, model);
                toolRepositories.add(localRepository);
                return tempDir.resolve("tool-cache/current");
            }
        );
        command.projectDir = project;
        command.localRepository = tempDir.resolve("maven-local");
        command.sources = List.of(Path.of("App.java"), Path.of("AppTest.java"));

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertEquals(List.of(tempDir.resolve("maven-local").toAbsolutePath().normalize()), toolRepositories);

        String eclipse = Files.readString(project.resolve(".classpath"));
        assertTrue(eclipse.contains("user-source"));
        assertFalse(eclipse.contains("/stale.jar"));
        assertTrue(eclipse.contains("direct-dependency-1.0.0.jar"));
        assertTrue(eclipse.contains("direct-dependency-1.0.0-sources.jar"));
        assertTrue(eclipse.contains("micronaut-pyronaut-build-annotations"));
        assertTrue(eclipse.contains("pyronaut.direct-source"));
        String idea = Files.readString(project.resolve(".idea/pyronaut-direct-source.iml"));
        assertTrue(idea.contains("direct-dependency-1.0.0.jar"));
        assertTrue(idea.contains("direct-dependency-1.0.0-sources.jar"));
        assertTrue(idea.contains(project.resolve("__pyronaut__/ide-classes").toUri().toString()));
        assertTrue(idea.contains("<content url=\"" + project.toUri() + "\">"));
        assertFalse(idea.contains("$MODULE_DIR$"));
        String vsCode = Files.readString(project.resolve(".vscode/settings.json"));
        assertTrue(vsCode.contains("\"editor.formatOnSave\":true"));
        assertTrue(vsCode.contains("\"java.project.sourcePaths\":[\".\"]"));
        assertTrue(vsCode.contains("direct-dependency-1.0.0.jar"));
        assertFalse(vsCode.contains("stale-source"));
        assertFalse(vsCode.contains("/stale.jar"));
        String launch = Files.readString(project.resolve(".vscode/launch.json"));
        assertTrue(launch.contains("\"name\":\"User Launch\""));
        assertTrue(launch.contains("\"name\":\"Pyronaut: Run Direct Sources\""));
        assertTrue(launch.contains("\"name\":\"Pyronaut: Test Direct Sources\""));
        assertTrue(launch.contains("\"command\":\"pyronaut dev 'App.java'\""));
        assertTrue(launch.contains("\"command\":\"pyronaut test 'App.java' -- 'AppTest.java'\""));
        assertFalse(launch.contains("stale-command"));
        String ideaRun = Files.readString(
            project.resolve(".idea/runConfigurations/Pyronaut_Run_Direct_Sources.xml")
        );
        String ideaTest = Files.readString(
            project.resolve(".idea/runConfigurations/Pyronaut_Test_Direct_Sources.xml")
        );
        assertTrue(ideaRun.contains("type=\"ShConfigurationType\""));
        assertTrue(ideaRun.contains("value=\"pyronaut dev 'App.java'\""));
        assertTrue(ideaTest.contains("value=\"pyronaut test 'App.java' -- 'AppTest.java'\""));
        assertTrue(Files.isRegularFile(project.resolve(".idea/runConfigurations/User.xml")));
        String modules = Files.readString(project.resolve(".idea/modules.xml"));
        assertTrue(modules.contains("user.iml"));
        assertFalse(modules.contains("filepath=\"old/pyronaut-direct-source.iml\""));
        assertTrue(modules.contains("pyronaut-direct-source.iml"));
        assertTrue(Files.isRegularFile(project.resolve("__pyronaut__/resolved-runtime-dependencies")));
        try (var paths = Files.list(project.resolve("__pyronaut__"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("direct-source-discovery-")));
        }

        String firstEclipse = Files.readString(project.resolve(".classpath"));
        String firstModules = Files.readString(project.resolve(".idea/modules.xml"));
        String firstIdea = Files.readString(project.resolve(".idea/pyronaut-direct-source.iml"));
        String firstVsCode = Files.readString(project.resolve(".vscode/settings.json"));
        String firstLaunch = Files.readString(project.resolve(".vscode/launch.json"));
        String firstIdeaRun = Files.readString(
            project.resolve(".idea/runConfigurations/Pyronaut_Run_Direct_Sources.xml")
        );
        String firstIdeaTest = Files.readString(
            project.resolve(".idea/runConfigurations/Pyronaut_Test_Direct_Sources.xml")
        );
        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertEquals(firstEclipse, Files.readString(project.resolve(".classpath")));
        assertEquals(firstModules, Files.readString(project.resolve(".idea/modules.xml")));
        assertEquals(firstIdea, Files.readString(project.resolve(".idea/pyronaut-direct-source.iml")));
        assertEquals(firstVsCode, Files.readString(project.resolve(".vscode/settings.json")));
        assertEquals(firstLaunch, Files.readString(project.resolve(".vscode/launch.json")));
        assertEquals(
            firstIdeaRun,
            Files.readString(project.resolve(".idea/runConfigurations/Pyronaut_Run_Direct_Sources.xml"))
        );
        assertEquals(
            firstIdeaTest,
            Files.readString(project.resolve(".idea/runConfigurations/Pyronaut_Test_Direct_Sources.xml"))
        );
        assertEquals(1, occurrences(firstModules, "filepath=\"$PROJECT_DIR$/.idea/pyronaut-direct-source.iml\""));
    }

    @Test
    void expandsGlobsAndCalculatesJavaPackageRoots(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("glob-project");
        Path sources = project.resolve("example");
        Path localRepository = tempDir.resolve("glob-maven-local");
        Files.createDirectories(sources);
        writeEmptyPlatformBom(localRepository);
        Files.writeString(sources.resolve("App.java"), """
            package example;

            class App {
            }
            """);
        Files.writeString(sources.resolve("AppTest.java"), """
            package example;

            class AppTest {
            }
            """);

        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.localRepository = localRepository;
        command.sources = List.of(Path.of("example/*.java"));

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String eclipse = Files.readString(project.resolve(".classpath"));
        assertTrue(eclipse.contains("kind=\"src\" path=\".\""));
        assertFalse(eclipse.contains("kind=\"src\" path=\"example\""));
        String idea = Files.readString(project.resolve(".idea/pyronaut-direct-source.iml"));
        assertTrue(idea.contains("<content url=\"" + project.toUri() + "\">"));
        assertTrue(idea.contains("<sourceFolder isTestSource=\"false\" url=\"" + project.toUri() + "\""));
        assertFalse(idea.contains("$PROJECT_DIR$/__pyronaut__"));
        assertFalse(idea.contains("$MODULE_DIR$"));
        String vsCode = Files.readString(project.resolve(".vscode/settings.json"));
        assertTrue(vsCode.contains("\"java.project.sourcePaths\":[\".\"]"));
        assertTrue(vsCode.contains("\"java.project.referencedLibraries\""));
        String launch = Files.readString(project.resolve(".vscode/launch.json"));
        assertTrue(launch.contains("pyronaut dev 'example/App.java'"));
        assertTrue(launch.contains("pyronaut test 'example/App.java' -- 'example/AppTest.java'"));
    }

    @Test
    void rejectsMixedDirectSourceLanguages(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("App.java"), "class App {}\n");
        Files.writeString(project.resolve("app.py"), "class App:\n    pass\n");
        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.sources = List.of(Path.of("App.java"), Path.of("app.py"));

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve(".classpath")));
        assertFalse(Files.exists(project.resolve(".vscode/settings.json")));
    }

    @Test
    void rejectsMissingSourcesAndDescriptorSourceCombinations(@TempDir Path project) throws Exception {
        PyronautInstallMain missing = new PyronautInstallMain();
        missing.projectDir = project;
        missing.sources = List.of(Path.of("Missing.java"));
        assertEquals(InstallExitCode.CONFIG_ERROR.code(), missing.call());

        PyronautInstallMain unmatchedGlob = new PyronautInstallMain();
        unmatchedGlob.projectDir = project;
        unmatchedGlob.sources = List.of(Path.of("*.java"));
        assertEquals(InstallExitCode.CONFIG_ERROR.code(), unmatchedGlob.call());

        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "descriptor-project"
            version = "1.0"
            """);
        Files.writeString(project.resolve("App.java"), "class App {}\n");
        PyronautInstallMain combined = new PyronautInstallMain();
        combined.projectDir = project;
        combined.sources = List.of(Path.of("App.java"));
        assertEquals(InstallExitCode.CONFIG_ERROR.code(), combined.call());
        assertFalse(Files.exists(project.resolve(".classpath")));
    }

    @Test
    void acceptsMultipleSourcesSelectedThroughDirectories(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("multi-source-project");
        Path first = project.resolve("first");
        Path second = project.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("First.java"), "class First {}\n");
        Files.writeString(second.resolve("Second.java"), "class Second {}\n");
        Files.createDirectories(first.resolve("__pyronaut__/ide-stubs"));
        Files.writeString(first.resolve("__pyronaut__/ide-stubs/generated.py"), "class Generated:\n    pass\n");
        Path localRepository = tempDir.resolve("multi-source-maven-local");
        writeEmptyPlatformBom(localRepository);

        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.localRepository = localRepository;
        command.sources = List.of(Path.of("first"), Path.of("second/Second.java"));

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String eclipse = Files.readString(project.resolve(".classpath"));
        assertTrue(eclipse.contains("path=\"first\""));
        assertTrue(eclipse.contains("path=\"second\""));
    }

    @Test
    void installsPythonSourcesAndConfiguresPylance(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("python-project");
        Path repository = tempDir.resolve("python-repository");
        Files.createDirectories(project);
        writeEmptyPlatformBom(repository);
        writeArtifact(repository, "com.example", "python-direct-dependency", "1.0.0");
        Files.writeString(project.resolve("app.py"), """
            from pyronaut.build import Dependency, MavenRepository

            MavenRepository("%s")
            Dependency(group="com.example", module="python-direct-dependency", version="1.0.0")

            class App:
                pass
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.localRepository = tempDir.resolve("python-maven-local");
        command.sources = List.of(Path.of("app.py"));

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        assertTrue(
            Files.readString(project.resolve("__pyronaut__/resolved-runtime-dependencies"))
                .contains("python-direct-dependency-1.0.0.jar")
        );
        assertTrue(Files.readString(project.resolve(".vscode/settings.json")).contains("__pyronaut__/ide-stubs"));
        assertTrue(Files.isDirectory(project.resolve("__pyronaut__/ide-stubs")));
        String editorArtifacts = Files.readString(project.resolve("__pyronaut__/resolved-editor-artifacts.json"));
        assertTrue(editorArtifacts.contains("python-direct-dependency"));
        assertTrue(editorArtifacts.contains("micronaut-context-python"));
    }

    @Test
    void resolvesDirectSourcesOfflineFromSelectedLocalRepository(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("offline-project");
        Path localRepository = tempDir.resolve("offline-maven-local");
        Files.createDirectories(project);
        writeEmptyPlatformBom(localRepository);
        writeArtifact(localRepository, "com.example", "offline-dependency", "1.0.0");
        Files.writeString(project.resolve("App.java"), """
            @pyronaut.build.Dependency(group = "com.example", module = "offline-dependency", version = "1.0.0")
            class App {
            }
            """);

        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.localRepository = localRepository;
        command.offline = true;
        command.sources = List.of(Path.of("App.java"));

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertTrue(Files.readString(project.resolve(".classpath")).contains("offline-dependency-1.0.0.jar"));
    }

    @Test
    void requiresSourceWhenProjectDescriptorIsMissing(@TempDir Path project) {
        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void rejectsMalformedDirectSourceDeclarations(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("App.java"), """
            @pyronaut.build.Dependency(group = "com.example")
            class App {
            }
            """);
        PyronautInstallMain command = new PyronautInstallMain();
        command.projectDir = project;
        command.sources = List.of(Path.of("App.java"));

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve(".classpath")));
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static void writeEmptyPlatformBom(Path repository) throws Exception {
        writeEmptyBom(repository, "io.micronaut.platform", "micronaut-platform", PyronautManagedVersions.micronautPlatformVersion());
        writeEmptyBom(repository, "io.micronaut", "micronaut-core-bom", PyronautManagedVersions.micronautCoreVersion());
    }

    private static void writeEmptyBom(Path repository, String group, String artifact, String version) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <packaging>pom</packaging>
            </project>
            """.formatted(group, artifact, version), StandardCharsets.UTF_8);
    }

    private static void writeArtifact(Path repository,
                                      String group,
                                      String artifact,
                                      String version) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(group, artifact, version), StandardCharsets.UTF_8);
        try (JarOutputStream ignored = new JarOutputStream(
            Files.newOutputStream(directory.resolve(artifact + "-" + version + ".jar")))) {
            // Empty but valid test artifact.
        }
        try (JarOutputStream ignored = new JarOutputStream(
            Files.newOutputStream(directory.resolve(artifact + "-" + version + "-sources.jar")))) {
            // Empty but valid test source artifact.
        }
    }
}
