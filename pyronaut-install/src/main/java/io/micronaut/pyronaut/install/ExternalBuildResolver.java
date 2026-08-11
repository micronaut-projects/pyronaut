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

import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout.ProjectKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;

/** Resolves the root Java source layout exposed by Maven or Gradle. */
@SuppressWarnings("checkstyle:InnerTypeLast")
final class ExternalBuildResolver {
    private final MavenClasspathResolver managedResolver;

    ExternalBuildResolver() {
        this(new MavenClasspathResolver());
    }

    ExternalBuildResolver(MavenClasspathResolver managedResolver) {
        this.managedResolver = managedResolver;
    }

    ExternalProjectLayout resolve(Path root, boolean offline) throws IOException {
        return resolve(root, offline, null);
    }

    ExternalProjectLayout resolve(Path root, boolean offline, Path localRepository) throws IOException {
        ProjectKind kind = ExternalProjectLayout.detect(root);
        if (kind == ProjectKind.PYPROJECT) {
            return new ExternalProjectLayout(kind, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Path effectivePom = kind == ProjectKind.MAVEN ? writeEffectiveMavenPom(root, offline, localRepository) : null;
        try {
            org.w3c.dom.Document mavenModel = kind == ProjectKind.MAVEN
                ? parseMavenModel(effectivePom == null ? root.resolve("pom.xml") : effectivePom) : null;
            System.err.println("Resolving " + kind.name() + " source sets and resources...");
            GradleSourceSets gradleSourceSets = kind == ProjectKind.GRADLE ? resolveGradleSourceSets(root, offline) : null;
            List<List<Path>> sourceSets = gradleSourceSets == null ? List.of(
                mavenSourceDirectory(root, "project.build.sourceDirectory", "sourceDirectory", "src/main/java", offline, localRepository),
                mavenSourceDirectory(root, "project.build.testSourceDirectory", "testSourceDirectory", "src/test/java", offline, localRepository),
                directories(root, "src/main/resources"), directories(root, "src/test/resources")
            ) : gradleSourceSets.sourceSets();
            List<Path> mainSources = sourceSets.get(0).isEmpty() ? directories(root, "src/main/java") : sourceSets.get(0);
            List<Path> testSources = sourceSets.get(1).isEmpty() ? directories(root, "src/test/java") : sourceSets.get(1);
            List<Path> mainResources = kind == ProjectKind.MAVEN ? mavenResources(mavenModel, root, "main", "src/main/resources") : (sourceSets.get(2).isEmpty() ? resourceDirectory(root, "src/main/resources") : sourceSets.get(2));
            List<Path> testResources = kind == ProjectKind.MAVEN ? mavenResources(mavenModel, root, "test", "src/test/resources") : (sourceSets.get(3).isEmpty() ? resourceDirectory(root, "src/test/resources") : sourceSets.get(3));
            System.err.println("Resolving " + kind.name() + " compile dependencies...");
            List<Path> build = resolveClasspath(root, kind, "build", offline, localRepository);
            System.err.println("Resolving " + kind.name() + " runtime dependencies...");
            List<Path> runtime = resolveClasspath(root, kind, "runtime", offline, localRepository);
            System.err.println("Resolving " + kind.name() + " test dependencies...");
            List<Path> test = resolveClasspath(root, kind, "test", offline, localRepository);
            System.err.println("Resolving " + kind.name() + " annotation processors...");
            List<Path> annotationProcessors = kind == ProjectKind.GRADLE
                ? resolveClasspath(root, kind, "annotationProcessor", offline, localRepository)
                : resolveMavenAnnotationProcessors(root, mavenModel, build, offline, localRepository);
            boolean testResourcesEnabled = kind == ProjectKind.GRADLE
                ? gradleSourceSets.testResourcesEnabled() : testResourcesEnabled(mavenModel);
            List<Path> testResourcesClasspath = testResourcesEnabled
                ? resolveClasspath(root, kind, "testResources", offline, localRepository) : List.of();
            List<Path> managedDevelopmentSupport = managedResolver.resolveManagedDevelopmentSupport(
                localRepository == null ? MavenClasspathResolver.resolveLocalMavenRepository() : localRepository,
                offline
            );
            List<Path> developmentRuntime = mergeClasspath(runtime, managedDevelopmentSupport);
            return new ExternalProjectLayout(kind, mainSources, testSources, mainResources, testResources,
                build, runtime, developmentRuntime, test, annotationProcessors, testResourcesEnabled, testResourcesClasspath);
        } finally {
            if (effectivePom != null) {
                Files.deleteIfExists(effectivePom);
            }
        }
    }

    static boolean testResourcesEnabled(Path pom) {
        return testResourcesEnabled(parseMavenModel(pom));
    }

    private static boolean testResourcesEnabled(org.w3c.dom.Document document) {
        if (document == null) {
            return false;
        }
        NodeList values = document.getElementsByTagName("micronaut.test.resources.enabled");
        for (int index = 0; index < values.getLength(); index++) {
            if (Boolean.parseBoolean(values.item(index).getTextContent().trim())) {
                return true;
            }
        }
        return false;
    }

    private static org.w3c.dom.Document parseMavenModel(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        } catch (Exception ignored) {
            // A malformed Maven model cannot enable Test Resources implicitly.
            return null;
        }
    }

    static boolean testResourcesEnabled(Path root, ProjectKind kind, Path effectivePom) {
        return kind == ProjectKind.GRADLE
            ? resolveGradleSourceSets(root, false).testResourcesEnabled()
            : testResourcesEnabled(effectivePom == null ? root.resolve("pom.xml") : effectivePom);
    }

    private static List<Path> directories(Path root, String relative) {
        Path directory = root.resolve(relative);
        return Files.isDirectory(directory) ? List.of(directory) : List.of();
    }

    static List<Path> mavenSources(Path root, String elementName, String fallback) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return directories(root, fallback);
        }
        try {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            NodeList elements = document.getElementsByTagName(elementName);
            if (elements.getLength() > 0) {
                Path path = resolveMavenPath(root, elements.item(0).getTextContent().trim());
                return Files.isDirectory(path) ? List.of(path) : List.of();
            }
        } catch (Exception ignored) {
            // Fall back to Maven's standard source layout when the POM is not parseable.
        }
        return directories(root, fallback);
    }

    private static List<Path> mavenSourceDirectory(Path root,
                                                   String expression,
                                                   String elementName,
                                                   String fallback,
                                                   boolean offline,
                                                   Path localRepository) {
        String resolved = evaluateMavenExpression(root, expression, offline, localRepository);
        if (resolved != null && !resolved.isBlank()) {
            Path path = resolveMavenPath(root, resolved.trim());
            return Files.isDirectory(path) ? List.of(path) : List.of();
        }
        return mavenSources(root, elementName, fallback);
    }

    private static String evaluateMavenExpression(Path root, String expression, boolean offline, Path localRepository) {
        try {
            Path wrapper = root.resolve("mvnw");
            List<String> command = new ArrayList<>(List.of(
                Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn",
                "help:evaluate", "-q", "-DforceStdout", "-Dexpression=" + expression
            ));
            if (offline) {
                command.add("-o");
            }
            if (localRepository != null) {
                command.add("-Dmaven.repo.local=" + localRepository.toAbsolutePath().normalize());
            }
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !output.isBlank() && !output.startsWith("[")) {
                return output;
            }
        } catch (Exception ignored) {
            // Fall back to the POM source directory when help:evaluate is unavailable.
        }
        return null;
    }

    private static GradleSourceSets resolveGradleSourceSets(Path root, boolean offline) {
        Path cache = ExternalProjectLayout.outputDirectory(root);
        try {
            Files.createDirectories(cache);
            Path output = cache.resolve("external-gradle-sources.txt");
            Path script = cache.resolve("external-gradle-sources.gradle");
            Files.deleteIfExists(output);
            Files.deleteIfExists(script);
            String target = output.toString().replace("\\", "\\\\");
            String scriptText = """
                gradle.beforeProject { p ->
                    if (p.parent == null) {
                        p.tasks.register('__pyronautWriteSourceLayout') {
                            doLast {
                                def sets = p.extensions.findByName('sourceSets')
                                if (sets != null) {
                                    def o = p.file('__PYRONAUT_TARGET__')
                                    ['main', 'test'].each { n ->
                                        def s = sets.findByName(n)
                                        if (s != null) {
                                            o << n + '.java=' + s.java.srcDirs.collect { it.absolutePath }.join(File.pathSeparator) + '\\n'
                                            o << n + '.resources=' + s.resources.srcDirs.collect { it.absolutePath }.join(File.pathSeparator) + '\\n'
                                        }
                                    }
                                    o << 'testResources.enabled=' + p.plugins.hasPlugin('io.micronaut.test-resources') + '\\n'
                                }
                            }
                        }
                    }
                }
                """.replace("__PYRONAUT_TARGET__", target);
            Files.writeString(script, scriptText, StandardCharsets.UTF_8);
            Path wrapper = root.resolve("gradlew");
            List<String> command = new ArrayList<>(List.of(Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle", "--no-daemon", "--init-script", script.toString(), "__pyronautWriteSourceLayout"));
            if (offline) {
                command.add("--offline");
            }
            Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            if (process.waitFor() != 0) {
                Files.deleteIfExists(output);
                Files.deleteIfExists(script);
                return new GradleSourceSets(List.of(List.of(), List.of(), List.of(), List.of()), false);
            }
            List<Path> mainJava = List.of(), testJava = List.of(), mainResources = List.of(), testResources = List.of();
            boolean testResourcesEnabled = false;
            for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                List<Path> paths = java.util.Arrays.stream(line.substring(equals + 1).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                    .filter(s -> !s.isBlank()).map(Path::of).toList();
                switch (line.substring(0, equals)) {
                    case "main.java" -> mainJava = paths;
                    case "test.java" -> testJava = paths;
                    case "main.resources" -> mainResources = paths;
                    case "test.resources" -> testResources = paths;
                    case "testResources.enabled" -> testResourcesEnabled = Boolean.parseBoolean(line.substring(equals + 1));
                    default -> { }
                }
            }
            Files.deleteIfExists(output);
            Files.deleteIfExists(script);
            return new GradleSourceSets(List.of(mainJava, testJava, mainResources, testResources), testResourcesEnabled);
        } catch (Exception ignored) {
            deleteQuietly(cache.resolve("external-gradle-sources.txt"));
            deleteQuietly(cache.resolve("external-gradle-sources.gradle"));
            return new GradleSourceSets(List.of(List.of(), List.of(), List.of(), List.of()), false);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup of generated external-build inputs.
        }
    }

    static List<Path> mavenResources(Path root, String scope, String fallback) {
        return mavenResources(root.resolve("pom.xml"), root, scope, fallback);
    }

    private static List<Path> mavenResources(Path pom, Path root, String scope, String fallback) {
        return mavenResources(parseMavenModel(pom), root, scope, fallback);
    }

    private static List<Path> mavenResources(org.w3c.dom.Document document, Path root, String scope, String fallback) {
        if (document == null) {
            return resourceDirectory(root, fallback);
        }
        List<Path> result = new ArrayList<>();
        try {
            NodeList resources = document.getElementsByTagName("test".equals(scope) ? "testResource" : "resource");
            for (int i = 0; i < resources.getLength(); i++) {
                var resource = resources.item(i);
                var parent = resource.getParentNode();
                if (parent == null || (!"resources".equals(parent.getNodeName()) && !"testResources".equals(parent.getNodeName()))) {
                    continue;
                }
                var directory = resource.getChildNodes();
                for (int j = 0; j < directory.getLength(); j++) {
                    var child = directory.item(j);
                    if ("directory".equals(child.getNodeName()) && child.getTextContent() != null) {
                        Path path = resolveMavenPath(root, child.getTextContent().trim());
                        result.add(path);
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to Maven's standard source layout when the POM is not parseable.
        }
        return result.isEmpty() ? resourceDirectory(root, fallback) : result;
    }

    private static List<Path> resourceDirectory(Path root, String relative) {
        return List.of(root.resolve(relative).toAbsolutePath().normalize());
    }

    private static Path writeEffectiveMavenPom(Path root, boolean offline, Path localRepository) throws IOException {
        Path cache = ExternalProjectLayout.outputDirectory(root);
        Files.createDirectories(cache);
        Path output = cache.resolve("external-effective-pom.xml");
        try {
            List<String> command = new ArrayList<>(List.of(mavenCommand(root), "help:effective-pom", "-Doutput=" + output));
            if (offline) {
                command.add("-o");
            }
            if (localRepository != null) {
                command.add("-Dmaven.repo.local=" + localRepository.toAbsolutePath().normalize());
            }
            Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            if (process.waitFor() != 0 || !Files.isRegularFile(output)) {
                Files.deleteIfExists(output);
                return null;
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving Maven effective model", e);
        }
    }

    private static List<Path> resolveMavenAnnotationProcessors(Path root,
                                                                org.w3c.dom.Document effectiveDocument,
                                                                List<Path> buildClasspath,
                                                                boolean offline,
                                                                Path localRepository) throws IOException {
        if (effectiveDocument == null) {
            return buildClasspath;
        }
        List<String[]> coordinates = new ArrayList<>();
        try {
            NodeList plugins = effectiveDocument.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                var plugin = plugins.item(i);
                if (!"maven-compiler-plugin".equals(childText(plugin, "artifactId"))) {
                    continue;
                }
                NodeList paths = ((org.w3c.dom.Element) plugin).getElementsByTagName("path");
                for (int j = 0; j < paths.getLength(); j++) {
                    var path = paths.item(j);
                    var parent = path.getParentNode();
                    if (parent == null || !"annotationProcessorPaths".equals(parent.getNodeName())) {
                        continue;
                    }
                    String group = childText(path, "groupId");
                    String artifact = childText(path, "artifactId");
                    String version = childText(path, "version");
                    if (group != null && artifact != null && version != null && !version.contains("${")) {
                        coordinates.add(new String[] {group, artifact, version});
                    }
                }
            }
        } catch (Exception ignored) {
            return buildClasspath;
        }
        if (coordinates.isEmpty()) {
            return buildClasspath;
        }

        Path processorPom = ExternalProjectLayout.outputDirectory(root).resolve("external-annotation-processors.pom");
        Path output = ExternalProjectLayout.outputDirectory(root).resolve("external-annotation-processors.classpath");
        StringBuilder pom = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>io.micronaut.pyronaut</groupId><artifactId>external-annotation-processors</artifactId><version>1</version><dependencies>");
        for (String[] coordinate : coordinates) {
            pom.append("<dependency><groupId>").append(coordinate[0]).append("</groupId><artifactId>").append(coordinate[1]).append("</artifactId><version>").append(coordinate[2]).append("</version></dependency>");
        }
        pom.append("</dependencies>");
        appendMavenRepositories(pom, effectiveDocument);
        pom.append("</project>");
        Files.writeString(processorPom, pom, StandardCharsets.UTF_8);
        try {
            List<String> command = new ArrayList<>(List.of(mavenCommand(root), "-f", processorPom.toString(), "dependency:build-classpath", "-Dmdep.outputFile=" + output, "-Dmdep.includeScope=compile"));
            if (offline) {
                command.add("-o");
            }
            if (localRepository != null) {
                command.add("-Dmaven.repo.local=" + localRepository.toAbsolutePath().normalize());
            }
            Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            if (process.waitFor() != 0 || !Files.isRegularFile(output)) {
                return buildClasspath;
            }
            List<Path> processors = java.util.Arrays.stream(Files.readString(output, StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(Path::of).filter(Files::isRegularFile).toList();
            return processors.isEmpty() ? buildClasspath : processors;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving Maven annotation processors", e);
        } finally {
            Files.deleteIfExists(processorPom);
            Files.deleteIfExists(output);
        }
    }

    private static String mavenCommand(Path root) {
        Path wrapper = root.resolve("mvnw");
        return Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn";
    }

    private static String childText(org.w3c.dom.Node parent, String name) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var child = children.item(i);
            if (name.equals(child.getNodeName()) && child.getTextContent() != null) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static void appendMavenRepositories(StringBuilder pom, org.w3c.dom.Document document) {
        NodeList repositories = document.getElementsByTagName("repository");
        java.util.Set<String> urls = new java.util.LinkedHashSet<>();
        for (int i = 0; i < repositories.getLength(); i++) {
            String url = childText(repositories.item(i), "url");
            if (url != null && !url.isBlank()) {
                urls.add(url.trim());
            }
        }
        if (urls.isEmpty()) {
            return;
        }
        pom.append("<repositories>");
        int index = 0;
        for (String url : urls) {
            pom.append("<repository><id>external-").append(index++).append("</id><url>")
                .append(xmlEscape(url)).append("</url></repository>");
        }
        pom.append("</repositories>");
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static Path resolveMavenPath(Path root, String value) {
        String projectBasedir = "${project.basedir}";
        if (value.startsWith(projectBasedir)) {
            String suffix = value.substring(projectBasedir.length());
            return root.resolve(suffix.startsWith("/") || suffix.startsWith("\\") ? suffix.substring(1) : suffix);
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path : root.resolve(path);
    }

    private static List<Path> resolveClasspath(Path root, ProjectKind kind, String scope, boolean offline, Path localRepository) throws IOException {
        Path cache = ExternalProjectLayout.outputDirectory(root);
        Files.createDirectories(cache);
        Path output = cache.resolve("external-" + scope + ".classpath");
        Files.deleteIfExists(output);
        Path initScript = null;
        try {
            List<String> command = new ArrayList<>();
            if (kind == ProjectKind.MAVEN) {
                Path wrapper = root.resolve("mvnw");
                command.add(Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn");
                command.add("dependency:build-classpath");
                command.add("-Dmdep.outputFile=" + output);
                command.add("-DincludeScope=" + ("test".equals(scope) ? "test" : ("build".equals(scope) ? "compile" : "runtime")));
            } else {
                Path wrapper = root.resolve("gradlew");
                command.add(Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle");
                command.add("--no-daemon");
                initScript = cache.resolve("external-" + scope + ".gradle");
                Files.deleteIfExists(initScript);
                String configuration = "test".equals(scope) ? "testRuntimeClasspath" : "testResources".equals(scope) ? "testResourcesService" : "build".equals(scope) ? "compileClasspath" : "annotationProcessor".equals(scope) ? "annotationProcessor" : "runtimeClasspath";
                String outputPath = output.toString().replace("\\", "\\\\");
                Files.writeString(initScript, "gradle.beforeProject { p -> if (p.parent == null) { p.tasks.register('__pyronautWriteClasspath') { doLast { def c = p.configurations.findByName('" + configuration + "'); if (c == null) { c = p.configurations.findByName('compileClasspath') }; if (c != null) { p.file('" + outputPath + "').text = c.resolve().collect { it.absolutePath }.join(File.pathSeparator) } } } } }", StandardCharsets.UTF_8);
                command.add("--init-script");
                command.add(initScript.toString());
                command.add("__pyronautWriteClasspath");
            }
            if (offline) {
                command.add(kind == ProjectKind.MAVEN ? "-o" : "--offline");
            }
            if (localRepository != null) {
                command.add("-Dmaven.repo.local=" + localRepository.toAbsolutePath().normalize());
            }
            Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("External " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + " dependency resolution failed (exit code " + exitCode + ")");
            }
            String value = Files.readString(output, StandardCharsets.UTF_8);
            return java.util.Arrays.stream(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(s -> !s.isBlank()).map(Path::of).filter(Files::exists).distinct().toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving external build classpath", e);
        } finally {
            Files.deleteIfExists(output);
            if (initScript != null) {
                Files.deleteIfExists(initScript);
            }
        }
    }

    private static List<Path> mergeClasspath(List<Path> primary, List<Path> additional) {
        Map<String, Path> artifacts = new LinkedHashMap<>();
        primary.forEach(path -> artifacts.putIfAbsent(mavenArtifactKey(path), path));
        additional.forEach(path -> artifacts.putIfAbsent(mavenArtifactKey(path), path));
        return List.copyOf(artifacts.values());
    }

    private static String mavenArtifactKey(Path path) {
        Path version = path.getParent();
        Path artifact = version == null ? null : version.getParent();
        return artifact == null ? path.toAbsolutePath().normalize().toString() : artifact.getFileName().toString();
    }

    private record GradleSourceSets(List<List<Path>> sourceSets, boolean testResourcesEnabled) {
    }
}
