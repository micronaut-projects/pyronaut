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

import com.github.javaparser.StaticJavaParser;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarations;
import io.micronaut.pyronaut.directsource.DirectSourceDiscovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs dependencies and editor metadata for direct source selections.
 */
final class DirectSourceInstaller {
    static final String CLASSPATH_PROPERTY = "pyronaut.install.direct.class.path";
    static final String CLASSPATH_ENV = "PYRONAUT_DIRECT_CLASSPATH";
    private static final String DEV_COMPILER_CLASSPATH_PROPERTY = "pyronaut.dev.compiler.class.path";
    private static final Set<String> IGNORED_SOURCE_DIRECTORIES = Set.of(
        ".git", ".gradle", ".idea", ".venv", "__pycache__", "__pyronaut__", "build", "target", "venv"
    );

    private final DirectSourceDiscovery discovery;
    private final DirectSourceDependencyResolver dependencyResolver;
    private final JavaEditorSupport javaEditorSupport;
    private final PythonEditorSupport pythonEditorSupport;

    DirectSourceInstaller() {
        this(
            new DirectSourceDiscovery(),
            new DirectSourceDependencyResolver(),
            new JavaEditorSupport(),
            new PythonEditorSupport()
        );
    }

    DirectSourceInstaller(DirectSourceDiscovery discovery,
                          DirectSourceDependencyResolver dependencyResolver,
                          JavaEditorSupport javaEditorSupport,
                          PythonEditorSupport pythonEditorSupport) {
        this.discovery = discovery;
        this.dependencyResolver = dependencyResolver;
        this.javaEditorSupport = javaEditorSupport;
        this.pythonEditorSupport = pythonEditorSupport;
    }

    void install(Path projectDir,
                 List<Path> selectors,
                 Path localRepository,
                 boolean offline,
                 boolean bypassCache,
                 InstallProgressReporter progressReporter) throws Exception {
        Selection selection = select(projectDir, selectors);
        String language = selection.language() == DirectSourceDiscovery.Language.JAVA ? "Java" : "Python";
        progressReporter.directSourceSelection(language, selection.files().size());
        Path cacheDir = projectDir.resolve("__pyronaut__");
        Files.createDirectories(cacheDir);
        List<Path> discoveryClasspath = discoveryClasspath();
        Path staging = Files.createTempDirectory(cacheDir, "direct-source-discovery-");
        try {
            stage(selection.files(), projectDir, staging);
            DirectSourceDeclarations declarations;
            try {
                declarations = discovery.discover(
                    selection.language(),
                    staging,
                    discoveryClasspath
                );
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Direct source declaration discovery failed: " + e.getMessage(), e);
            }
            List<String> build = declarations.dependencies().stream()
                .filter(DirectSourceDeclarations.Dependency::build)
                .map(DirectSourceDeclarations.Dependency::coordinate)
                .toList();
            List<String> runtime = declarations.dependencies().stream()
                .filter(dependency -> !dependency.build())
                .map(DirectSourceDeclarations.Dependency::coordinate)
                .toList();
            progressReporter.directSourceDeclarations(
                build.size(),
                runtime.size(),
                declarations.repositories().size()
            );
            DirectSourceDependencyResolver.DetailedResult resolved = dependencyResolver.resolveDetailed(
                cacheDir,
                build,
                runtime,
                declarations.repositories(),
                localRepository,
                offline,
                bypassCache,
                fingerprintInputs(selection.files(), discoveryClasspath)
            );
            LinkedHashSet<String> resolvedArtifacts = new LinkedHashSet<>(resolved.build());
            resolvedArtifacts.addAll(resolved.runtime());
            progressReporter.directSourceDependencies(resolvedArtifacts.size());
            if (selection.language() == DirectSourceDiscovery.Language.JAVA) {
                List<Path> ideClasspath = jarClasspath(discoveryClasspath, resolved.build(), resolved.runtime());
                javaEditorSupport.ensureWritten(
                    projectDir,
                    selection.files(),
                    selection.sourceRoots(),
                    javaLibraries(ideClasspath, resolved)
                );
            } else {
                pythonEditorSupport.ensureWrittenFromResolvedArtifacts(
                    projectDir,
                    cacheDir,
                    defaultIdeStubs(),
                    pythonArtifacts(discoveryClasspath, resolved),
                    List.of()
                );
            }
            progressReporter.directSourceEditorSupport(language);
        } finally {
            deleteDirectory(staging);
        }
    }

    private static Selection select(Path projectDir, List<Path> selectors) throws IOException {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path selector : selectors) {
            for (Path expanded : expandSelector(projectDir, selector)) {
                selectPath(projectDir, selector, expanded, files, roots);
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Direct source selection contains no .java or .py files");
        }
        boolean java = files.stream().anyMatch(path -> extension(path).equals("java"));
        boolean python = files.stream().anyMatch(path -> extension(path).equals("py"));
        if (java && python) {
            throw new IllegalArgumentException("Direct source installation cannot mix Java and Python sources");
        }
        return new Selection(
            java ? DirectSourceDiscovery.Language.JAVA : DirectSourceDiscovery.Language.PYTHON,
            List.copyOf(files),
            java ? javaSourceRoots(files) : minimalRoots(roots)
        );
    }

    private static void selectPath(Path projectDir,
                                   Path selector,
                                   Path resolved,
                                   LinkedHashSet<Path> files,
                                   LinkedHashSet<Path> roots) throws IOException {
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Direct source does not exist: " + selector);
        }
        if (Files.isDirectory(resolved)) {
            roots.add(resolved.toAbsolutePath().normalize());
            try (var paths = Files.walk(resolved)) {
                paths.filter(path -> !isIgnoredGeneratedPath(resolved, path))
                    .filter(Files::isRegularFile)
                    .filter(DirectSourceInstaller::isSource)
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .forEach(files::add);
            }
        } else if (isSource(resolved)) {
            Path normalized = resolved.toAbsolutePath().normalize();
            files.add(normalized);
            roots.add(normalized.getParent());
        } else {
            throw new IllegalArgumentException("Direct source must be a .java or .py file: " + selector);
        }
    }

    private static List<Path> expandSelector(Path projectDir, Path selector) throws IOException {
        String pattern = selector.toString();
        if (!containsGlob(pattern)) {
            return List.of(selector.isAbsolute() ? selector.normalize() : projectDir.resolve(selector).normalize());
        }
        Path searchRoot = selector.isAbsolute() ? globSearchRoot(selector) : projectDir;
        PathMatcher matcher = selector.getFileSystem().getPathMatcher("glob:" + pattern);
        try (var paths = Files.walk(searchRoot)) {
            List<Path> matches = paths
                .filter(path -> !isIgnoredGeneratedPath(searchRoot, path))
                .filter(path -> matcher.matches(selector.isAbsolute() ? path : projectDir.relativize(path)))
                .map(Path::normalize)
                .sorted()
                .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Direct source pattern matched no paths: " + selector);
            }
            return matches;
        }
    }

    private static Path globSearchRoot(Path selector) {
        Path root = selector.getRoot();
        Path searchRoot = root;
        for (Path segment : selector) {
            if (containsGlob(segment.toString())) {
                break;
            }
            searchRoot = searchRoot == null ? segment : searchRoot.resolve(segment);
        }
        return searchRoot == null ? Path.of(".").toAbsolutePath().normalize() : searchRoot;
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0
            || value.indexOf('?') >= 0
            || value.indexOf('[') >= 0
            || value.indexOf('{') >= 0;
    }

    private static List<Path> javaSourceRoots(LinkedHashSet<Path> files) throws IOException {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            String packageName = javaPackageName(file);
            roots.add(packageRoot(parent, packageName));
        }
        return minimalRoots(roots);
    }

    private static String javaPackageName(Path file) throws IOException {
        try {
            return StaticJavaParser.parse(file)
                .getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        } catch (RuntimeException ignored) {
            String source = Files.readString(file);
            Matcher matcher = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;").matcher(source);
            if (matcher.find()) {
                return matcher.group(1).replaceAll("\\s+", "");
            }
            throw new IllegalArgumentException("Cannot parse Java source package declaration: " + file, ignored);
        }
    }

    private static Path packageRoot(Path sourceDirectory, String packageName) {
        if (packageName.isBlank()) {
            return sourceDirectory;
        }
        Path candidate = sourceDirectory;
        String[] segments = packageName.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (candidate == null
                || candidate.getFileName() == null
                || !candidate.getFileName().toString().equals(segments[i])) {
                return sourceDirectory;
            }
            candidate = candidate.getParent();
        }
        return candidate == null ? sourceDirectory : candidate;
    }

    private static List<Path> minimalRoots(LinkedHashSet<Path> roots) {
        return roots.stream()
            .filter(candidate -> roots.stream().noneMatch(other -> !candidate.equals(other) && candidate.startsWith(other)))
            .sorted()
            .toList();
    }

    private static void stage(List<Path> files, Path projectDir, Path staging) throws IOException {
        Map<Path, Path> destinations = new LinkedHashMap<>();
        for (Path source : files) {
            Path relative = source.startsWith(projectDir)
                ? projectDir.relativize(source)
                : Path.of(source.getFileName().toString());
            Path destination = staging.resolve(relative).normalize();
            Path collision = destinations.putIfAbsent(destination, source);
            if (collision != null && !collision.equals(source)) {
                throw new IllegalArgumentException(
                    "Direct source files have the same staged path: " + collision + " and " + source
                );
            }
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> discoveryClasspath() {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        addClasspath(entries, System.getProperty(CLASSPATH_PROPERTY));
        addClasspath(entries, System.getProperty(DEV_COMPILER_CLASSPATH_PROPERTY));
        addClasspath(entries, System.getenv(CLASSPATH_ENV));
        addClasspath(entries, System.getProperty("java.class.path"));
        return entries.stream()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(Files::exists)
            .toList();
    }

    private static void addClasspath(LinkedHashSet<Path> entries, String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry));
            }
        }
    }

    private static List<Path> jarClasspath(List<Path> base,
                                           List<String> firstResolved,
                                           List<String> secondResolved) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        base.stream().filter(DirectSourceInstaller::isJar).forEach(paths::add);
        for (List<String> classpath : List.of(firstResolved, secondResolved)) {
            for (String entry : classpath) {
                Path path = Path.of(entry);
                if (!Files.exists(path)) {
                    System.err.println("Warning: unavailable direct-source classpath entry: " + path);
                } else if (isJar(path)) {
                    paths.add(path);
                }
            }
        }
        return paths.stream().map(Path::toAbsolutePath).map(Path::normalize).sorted().toList();
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path)
            && path.getFileName() != null
            && path.getFileName().toString().endsWith(".jar");
    }

    private static List<JavaEditorSupport.Library> javaLibraries(
        List<Path> classpath,
        DirectSourceDependencyResolver.DetailedResult resolved
    ) {
        Map<Path, Path> sources = new LinkedHashMap<>();
        for (DirectSourceDependencyResolver.ResolvedArtifact artifact
            : concat(resolved.buildArtifacts(), resolved.runtimeArtifacts())) {
            if (artifact.sourceJar() != null) {
                sources.put(
                    Path.of(artifact.binaryJar()).toAbsolutePath().normalize(),
                    Path.of(artifact.sourceJar()).toAbsolutePath().normalize()
                );
            }
        }
        return classpath.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .map(path -> new JavaEditorSupport.Library(path, sources.get(path)))
            .toList();
    }

    private static List<MavenClasspathResolver.ResolvedEditorArtifact> pythonArtifacts(
        List<Path> bundledClasspath,
        DirectSourceDependencyResolver.DetailedResult resolved
    ) {
        Map<Path, MavenClasspathResolver.ResolvedEditorArtifact> artifacts = new LinkedHashMap<>();
        bundledClasspath.stream()
            .filter(DirectSourceInstaller::isJar)
            .map(path -> path.toAbsolutePath().normalize())
            .forEach(path -> artifacts.put(path, new MavenClasspathResolver.ResolvedEditorArtifact(
                null, null, null, path, sourceJar(path)
            )));
        for (DirectSourceDependencyResolver.ResolvedArtifact artifact : resolved.runtimeArtifacts()) {
            Path binary = Path.of(artifact.binaryJar()).toAbsolutePath().normalize();
            Path sources = artifact.sourceJar() == null
                ? sourceJar(binary)
                : Path.of(artifact.sourceJar()).toAbsolutePath().normalize();
            artifacts.put(binary, new MavenClasspathResolver.ResolvedEditorArtifact(
                artifact.groupId(), artifact.artifactId(), artifact.version(), binary, sources
            ));
        }
        return List.copyOf(artifacts.values());
    }

    private static Path sourceJar(Path binary) {
        String name = binary.getFileName().toString();
        if (!name.endsWith(".jar") || name.endsWith("-sources.jar")) {
            return null;
        }
        Path source = binary.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
        return Files.isRegularFile(source) ? source : null;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }

    private static List<String> fingerprintInputs(List<Path> files, List<Path> classpath) throws IOException {
        List<String> inputs = new ArrayList<>();
        for (Path file : files) {
            inputs.add("source=" + file + ":" + sha256(file));
        }
        for (Path path : classpath) {
            inputs.add("classpath=" + path.toAbsolutePath().normalize()
                + ":" + Files.size(path)
                + ":" + Files.getLastModifiedTime(path).toMillis());
        }
        return List.copyOf(inputs);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static PyprojectModel.IdeStubs defaultIdeStubs() {
        return new PyprojectModel.IdeStubs(
            true,
            "vscode",
            List.of("io.micronaut", "jakarta"),
            List.of("*ModuleInfo"),
            "__pyronaut__/ide-stubs"
        );
    }

    private static boolean isSource(Path path) {
        String extension = extension(path);
        return extension.equals("java") || extension.equals("py");
    }

    private static boolean isIgnoredGeneratedPath(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (IGNORED_SOURCE_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator + 1);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Selection(
        DirectSourceDiscovery.Language language,
        List<Path> files,
        List<Path> sourceRoots
    ) {
    }
}
