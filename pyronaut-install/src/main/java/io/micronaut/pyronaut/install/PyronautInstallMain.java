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

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Entry point for {@code pyronaut-install}.
 */
@SuppressWarnings({"checkstyle:InnerTypeLast", "checkstyle:MissingSwitchDefault", "checkstyle:LeftCurly"})
@CommandLine.Command(name = "pyronaut-install", mixinStandardHelpOptions = true, description = "Resolve and cache project dependencies")
public final class PyronautInstallMain implements Callable<Integer> {
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String BUNDLED_PYTEST_ARTIFACT_PREFIX = "micronaut-pyronaut-pytest-";

    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project root for descriptors, sources, and generated files")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = {"--local-repository", "--local-repo"}, description = "Local Maven repository directory for resolved dependency artifacts")
    Path localRepository;

    @CommandLine.Option(names = "--scope", description = "Scope to resolve: build|runtime|development-runtime|test|test-resources-server|all")
    String scope;

    @CommandLine.Option(names = "--dependencies", description = "Render dependency output instead of install-focused progress")
    boolean dependencies;

    @CommandLine.Option(names = "--progress", defaultValue = "auto", description = "Progress output mode: auto|on|off")
    String progress = "auto";

    @CommandLine.Option(names = "--color", defaultValue = "auto", description = "Color output mode: auto|always|never")
    String color = "auto";

    @CommandLine.Option(names = "--refresh", description = "Force dependency re-resolution and manifest rewrite without clearing the selected local repository")
    boolean refresh;

    @CommandLine.Option(names = "--no-cache", description = "Bypass manifest cache reads and rewrite manifests")
    boolean noCache;

    @CommandLine.Option(names = "--offline", description = "Use offline mode for repository access")
    boolean offline;

    @CommandLine.Option(names = "--resolve-tools-only", hidden = true, description = "Resolve SDK launcher classpaths without installing a project")
    boolean resolveToolsOnly;

    @CommandLine.Option(names = "--native-classpaths-dir", hidden = true, description = "Directory containing portable native launcher classpath descriptors")
    Path nativeClasspathsDir;

    @CommandLine.Option(names = "--repository", hidden = true, description = "Repository used for SDK setup resolution")
    List<String> setupRepositories = List.of();

    @CommandLine.Parameters(arity = "0..*", paramLabel = "SOURCE", description = "Direct .java/.py source files, directories, or glob patterns")
    List<Path> sources = List.of();

    private final PyprojectModelReader modelReader;
    private final MavenClasspathResolver resolver;
    private final PyprojectEditorSupport editorSupport;
    private final PythonEditorSupport pythonEditorSupport;
    private final ExternalBuildResolver externalBuildResolver;
    private final ToolRuntimeInstaller toolRuntimeInstaller;

    public PyronautInstallMain() {
        this(new PyprojectModelReader(), new MavenClasspathResolver(), new PyprojectEditorSupport(), new PythonEditorSupport(), new ExternalBuildResolver());
    }

    PyronautInstallMain(PyprojectModelReader modelReader, MavenClasspathResolver resolver) {
        this(modelReader, resolver, new PyprojectEditorSupport(), new PythonEditorSupport(), new ExternalBuildResolver());
    }

    PyronautInstallMain(PyprojectModelReader modelReader, MavenClasspathResolver resolver, PyprojectEditorSupport editorSupport) {
        this(modelReader, resolver, editorSupport, new PythonEditorSupport(), new ExternalBuildResolver());
    }

    PyronautInstallMain(PyprojectModelReader modelReader,
                        MavenClasspathResolver resolver,
                        PyprojectEditorSupport editorSupport,
                        PythonEditorSupport pythonEditorSupport) {
        this(modelReader, resolver, editorSupport, pythonEditorSupport, new ExternalBuildResolver());
    }

    PyronautInstallMain(PyprojectModelReader modelReader,
                        MavenClasspathResolver resolver,
                        PyprojectEditorSupport editorSupport,
                        PythonEditorSupport pythonEditorSupport,
                        ExternalBuildResolver externalBuildResolver) {
        this(modelReader, resolver, editorSupport, pythonEditorSupport, externalBuildResolver,
            new ToolClasspathInstaller()::install);
    }

    PyronautInstallMain(PyprojectModelReader modelReader,
                        MavenClasspathResolver resolver,
                        PyprojectEditorSupport editorSupport,
                        PythonEditorSupport pythonEditorSupport,
                        ExternalBuildResolver externalBuildResolver,
                        ToolRuntimeInstaller toolRuntimeInstaller) {
        this.modelReader = modelReader;
        this.resolver = resolver;
        this.editorSupport = editorSupport;
        this.pythonEditorSupport = pythonEditorSupport;
        this.externalBuildResolver = externalBuildResolver;
        this.toolRuntimeInstaller = toolRuntimeInstaller;
    }

    @Override
    public Integer call() {
        try (ChecksumWarningFilter ignored = ChecksumWarningFilter.install()) {
            initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
            Path root = projectDir.toAbsolutePath().normalize();
            Path pyproject = root.resolve(PyprojectModelReader.FILE_NAME);
            if (resolveToolsOnly) {
                PyprojectModel setupModel = setupRepositories.isEmpty()
                    ? ToolClasspathInstaller.defaultModel()
                    : ToolClasspathInstaller.defaultModel(setupRepositories);
                try (InstallProgressReporter progressReporter = InstallProgressReporter.create(progress)) {
                    installToolClasspaths(root, setupModel, progressReporter);
                    if (nativeClasspathsDir != null) {
                        Path descriptors = nativeClasspathsDir.isAbsolute()
                            ? nativeClasspathsDir
                            : root.resolve(nativeClasspathsDir);
                        InstallScope progressScope = InstallScope.BUILD;
                        progressReporter.startScope(progressScope);
                        try {
                            new NativeClasspathInstaller(resolver).install(
                                setupModel,
                                descriptors.toAbsolutePath().normalize(),
                                resolveLocalRepository(root),
                                offline,
                                refresh || noCache,
                                progressListener(progressReporter, progressScope)
                            );
                            progressReporter.finishScope(progressScope);
                        } catch (RuntimeException | IOException e) {
                            progressReporter.failScope(progressScope);
                            throw e;
                        }
                    }
                }
                return InstallExitCode.SUCCESS.code();
            }
            if (nativeClasspathsDir != null) {
                throw new IllegalArgumentException("--native-classpaths-dir requires --resolve-tools-only");
            }
            if (!sources.isEmpty()) {
                if (Files.exists(pyproject) || ExternalProjectLayout.isExternal(root)) {
                    throw new IllegalArgumentException(
                        "Direct source arguments cannot be combined with pyproject.toml, Maven, or Gradle project configuration"
                    );
                }
                Path localRepo = resolveLocalRepository(root);
                try (InstallProgressReporter progressReporter = InstallProgressReporter.create(progress)) {
                    new DirectSourceInstaller().install(
                        root,
                        sources,
                        localRepo,
                        offline,
                        refresh || noCache,
                        progressReporter
                    );
                }
                installToolClasspaths(root, null);
                return InstallExitCode.SUCCESS.code();
            }
            if (ExternalProjectLayout.isExternal(root)) {
                Path cacheDir = ExternalProjectLayout.outputDirectory(root);
                Path localRepo = resolveLocalRepository(root);
                String hash = ResolutionCache.externalInstallHash(root, localRepo);
                Path hashFile = cacheDir.resolve("external-build.sha256");
                String buildName = ExternalProjectLayout.detect(root).name();
                boolean showProgress = !"off".equalsIgnoreCase(progress);
                if (!refresh && !noCache && Files.exists(hashFile) && Files.exists(ExternalProjectLayout.file(root))
                    && hash.equals(Files.readString(hashFile).trim())) {
                    boolean completeCache = false;
                    try {
                        ExternalProjectLayout cachedLayout = ExternalProjectLayout.read(root);
                        Path testResourcesManifest = cacheDir.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile());
                        completeCache = externalCacheComplete(cachedLayout, testResourcesManifest);
                        if (completeCache) {
                            editorSupport.ensureExternalWritten(root, cacheDir, cachedLayout.testResourcesEnabled());
                            AnnotationProcessorOptionDiscovery.refresh(cacheDir,
                                cachedLayout.annotationProcessorClasspath().isEmpty() ? cachedLayout.buildClasspath() : cachedLayout.annotationProcessorClasspath());
                            editorSupport.ensureExternalApplicationSchema(cacheDir, cachedLayout.mainResources(),
                                cachedLayout.runtimeClasspath().stream().map(Path::toString).toList());
                        }
                    } catch (Exception discoveryFailure) {
                        // Option discovery is best effort; dependency installation remains usable.
                    }
                    if (completeCache) {
                        installToolClasspaths(root, null);
                        if (showProgress) {
                            System.err.println(buildName + " dependencies already configured (cache hit). Use --refresh to resolve again.");
                        }
                        return InstallExitCode.SUCCESS.code();
                    }
                }
                if (showProgress) {
                    System.err.println("Resolving dependencies for " + buildName + " project...");
                }
                ExternalProjectLayout layout = externalBuildResolver.resolve(root, offline, localRepo);
                layout.write(root);
                editorSupport.ensureExternalWritten(root, cacheDir, layout.testResourcesEnabled());
                hash = ResolutionCache.externalInstallHash(root, localRepo);
                editorSupport.ensureExternalApplicationSchema(cacheDir, layout.mainResources(),
                    layout.runtimeClasspath().stream().map(Path::toString).toList());
                AnnotationProcessorOptionDiscovery.refresh(cacheDir,
                    layout.annotationProcessorClasspath().isEmpty() ? layout.buildClasspath() : layout.annotationProcessorClasspath());
                if (layout.testResourcesEnabled()) {
                    Files.write(cacheDir.resolve("resolved-test-resources-server-dependencies"),
                        layout.testResourcesClasspath().stream().map(Path::toString).toList());
                } else {
                    Files.deleteIfExists(cacheDir.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile()));
                }
                Files.createDirectories(cacheDir);
                Files.writeString(hashFile, hash);
                if (showProgress) {
                    System.err.println(buildName + " Dependencies Configured.");
                }
                installToolClasspaths(root, null);
                return InstallExitCode.SUCCESS.code();
            }
            InstallProgressReporter.ProgressMode.fromCliValue(progress);
            DependencyTreeRenderer.ColorMode.fromCliValue(color);
            List<InstallScope> scopes = selectedScopes();

            if (!Files.isRegularFile(pyproject)) {
                throw new IllegalArgumentException(
                    "Missing pyproject.toml. Pass direct sources, for example: pyronaut install App.java"
                );
            }
            PyprojectModel model = modelReader.readFile(pyproject);
            Path cacheDir = root.resolve(DEFAULT_PYRONAUT_DIR);
            Path localRepo = resolveLocalRepository(root);
            boolean testResourcesServerEnabled = resolver.resolvesTestResourcesServer(model);
            List<InstallScope> activeScopes = testResourcesServerEnabled
                ? scopes
                : scopes.stream().filter(installScope -> installScope != InstallScope.TEST_RESOURCES_SERVER).toList();
            if (!testResourcesServerEnabled) {
                Files.deleteIfExists(cacheDir.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile()));
            }
            editorSupport.ensureWritten(root, cacheDir, model.pyronaut().sources());
            String hash = ResolutionCache.installHash(pyproject, localRepo);
            try (InstallProgressReporter progressReporter = InstallProgressReporter.create(progress)) {
                if (dependencies) {
                    return renderDependencyTrees(root, activeScopes, progressReporter);
                }
                boolean bypassRequested = refresh || noCache;
                if (!bypassRequested && ResolutionCache.cacheHit(cacheDir, hash, activeScopes)) {
                    try {
                        AnnotationProcessorOptionDiscovery.refresh(cacheDir,
                            readManifestClasspath(cacheDir.resolve(InstallScope.BUILD.manifestFile())));
                    } catch (Exception discoveryFailure) {
                        // Option discovery is best effort; dependency installation remains usable.
                    }
                    progressReporter.cacheHit();
                    if (activeScopes.contains(InstallScope.RUNTIME)) {
                        editorSupport.ensureApplicationSchema(root, cacheDir, model.pyronaut().sources(),
                            readManifestClasspath(cacheDir.resolve(InstallScope.RUNTIME.manifestFile())).stream().map(Path::toString).toList(),
                            AnnotationProcessorOptionDiscovery.readSchemaOptions(cacheDir));
                    }
                    emitEditorSupportBestEffort(
                        progressReporter,
                        () -> pythonEditorSupport.ensureWrittenFromManifests(root, cacheDir, model.pyronaut().ideStubs())
                    );
                    installToolClasspaths(root, model, progressReporter);
                    return InstallExitCode.SUCCESS.code();
                }
                if (bypassRequested) {
                    progressReporter.cacheBypass();
                }

                Map<InstallScope, List<String>> resolved = new EnumMap<>(InstallScope.class);
                Map<InstallScope, List<MavenClasspathResolver.ResolvedEditorArtifact>> resolvedEditorArtifacts = new EnumMap<>(InstallScope.class);
                Map<InstallScope, Future<MavenClasspathResolver.ResolvedScopeDetails>> futures = new EnumMap<>(InstallScope.class);
                for (InstallScope installScope : activeScopes) {
                    progressReporter.startScope(installScope);
                }
                RuntimeException firstFailure = null;
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (InstallScope installScope : activeScopes) {
                        futures.put(installScope, executor.submit(() -> resolver.resolveScopeDetails(
                            model,
                            installScope,
                            localRepo,
                            offline,
                            bypassRequested,
                            progressListener(progressReporter, installScope),
                            root
                        )));
                    }
                    for (InstallScope installScope : activeScopes) {
                        try {
                            MavenClasspathResolver.ResolvedScopeDetails details = futures.get(installScope).get();
                            List<String> classpath = manifestClasspath(installScope, details.classpath());
                            progressReporter.finishScope(installScope, classpath.size());
                            resolved.put(installScope, classpath);
                            resolvedEditorArtifacts.put(installScope, details.editorArtifacts());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Dependency resolution was interrupted", e);
                        } catch (ExecutionException e) {
                            progressReporter.failScope(installScope);
                            RuntimeException failure = e.getCause() instanceof RuntimeException runtime
                                ? runtime
                                : new IllegalStateException("Dependency resolution failed", e.getCause());
                            if (firstFailure == null) {
                                firstFailure = failure;
                            } else {
                                firstFailure.addSuppressed(failure);
                            }
                        }
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
                AnnotationProcessorOptionDiscovery.refresh(cacheDir,
                    resolved.getOrDefault(InstallScope.BUILD, List.of()).stream().map(Path::of).toList());
                if (resolved.containsKey(InstallScope.RUNTIME)) {
                    var schemaResult = editorSupport.ensureApplicationSchema(root, cacheDir, model.pyronaut().sources(),
                        resolved.get(InstallScope.RUNTIME), AnnotationProcessorOptionDiscovery.readSchemaOptions(cacheDir));
                    if (schemaResult.status() == MicronautApplicationJsonSchemaBundler.Status.GENERATED) {
                        progressReporter.generatedApplicationSchema(schemaResult.mergedSchemas());
                    }
                }
                emitEditorSupportBestEffort(
                    progressReporter,
                    () -> pythonEditorSupport.ensureWrittenFromResolvedArtifacts(
                        root,
                        cacheDir,
                        model.pyronaut().ideStubs(),
                        resolvedEditorArtifacts.getOrDefault(InstallScope.RUNTIME, List.of()),
                        resolvedEditorArtifacts.getOrDefault(InstallScope.TEST, List.of())
                    )
                );
                ResolutionCache.write(cacheDir, hash, resolved);
                installToolClasspaths(root, model, progressReporter);
            }
            return InstallExitCode.SUCCESS.code();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return InstallExitCode.CONFIG_ERROR.code();
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            if (e.getCause() instanceof org.eclipse.aether.resolution.DependencyResolutionException
                || e.getCause() instanceof org.eclipse.aether.resolution.ArtifactResolutionException) {
                return InstallExitCode.RESOLUTION_ERROR.code();
            }
            return InstallExitCode.CONFIG_ERROR.code();
        } catch (IOException e) {
            System.err.println("I/O error during installation: " + e.getMessage());
            return InstallExitCode.INTERNAL_ERROR.code();
        } catch (Exception e) {
            System.err.println("Unexpected install failure: " + e.getMessage());
            return InstallExitCode.INTERNAL_ERROR.code();
        }
    }

    private void installToolClasspaths(Path root, PyprojectModel model) throws IOException {
        try (InstallProgressReporter progressReporter = InstallProgressReporter.create(progress)) {
            installToolClasspaths(root, model, progressReporter);
        }
    }

    private void installToolClasspaths(Path root,
                                       PyprojectModel model,
                                       InstallProgressReporter progressReporter) throws IOException {
        InstallScope progressScope = InstallScope.DEVELOPMENT_RUNTIME;
        progressReporter.startScope(progressScope);
        try {
            Path installed = toolRuntimeInstaller.install(
                model,
                resolveLocalRepository(root),
                offline,
                refresh || noCache,
                progressListener(progressReporter, progressScope)
            );
            progressReporter.finishScope(progressScope);
            if (installed != null && !"off".equalsIgnoreCase(progress)) {
                progressReporter.toolRuntimeReady(installed);
            }
        } catch (RuntimeException | IOException e) {
            progressReporter.failScope(progressScope);
            throw e;
        }
    }

    @FunctionalInterface
    interface ToolRuntimeInstaller {
        Path install(PyprojectModel model,
                     Path localRepository,
                     boolean offline,
                     boolean refresh,
                     DependencyProgressListener progressListener) throws IOException;
    }

    static boolean externalCacheComplete(ExternalProjectLayout layout, Path testResourcesManifest) throws IOException {
        if (layout.testResourcesEnabled()) {
            return Files.isRegularFile(testResourcesManifest);
        }
        Files.deleteIfExists(testResourcesManifest);
        return true;
    }

    private static DependencyProgressListener progressListener(InstallProgressReporter reporter, InstallScope scope) {
        return new DependencyProgressListener() {
            @Override
            public void artifactPlanned(String name) { reporter.artifactPlanned(scope, name); }

            @Override
            public void reset() { reporter.resetScope(scope); }

            @Override
            public void begin() { reporter.beginScope(scope); }

            @Override
            public void artifactStarted(String name) { reporter.artifactStarted(scope, name); }

            @Override
            public void artifactTransferFinished(String name) { reporter.artifactTransferFinished(scope, name); }

            @Override
            public void artifactCompleted(String name) { reporter.artifactCompleted(scope, name); }

            @Override
            public void artifactFailed(String name) { reporter.artifactFailed(scope, name); }
        };
    }

    private static List<String> manifestClasspath(InstallScope installScope, List<Path> resolvedClasspath) {
        return resolvedClasspath.stream()
            .filter(path -> includeInManifest(installScope, path))
            .map(path -> path.toAbsolutePath().toString())
            .toList();
    }

    private static List<Path> readManifestClasspath(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        return Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .map(Path::of)
            .toList();
    }

    private static boolean includeInManifest(InstallScope installScope, Path artifact) {
        if (installScope != InstallScope.TEST || artifact == null || artifact.getFileName() == null) {
            return true;
        }
        return !artifact.getFileName().toString().startsWith(BUNDLED_PYTEST_ARTIFACT_PREFIX);
    }

    private List<InstallScope> selectedScopes() {
        if (scope == null || scope.isBlank()) {
            if (dependencies) {
                return List.of(InstallScope.RUNTIME);
            }
            return List.of(
                InstallScope.BUILD,
                InstallScope.RUNTIME,
                InstallScope.DEVELOPMENT_RUNTIME,
                InstallScope.TEST,
                InstallScope.TEST_RESOURCES_SERVER
            );
        }
        if ("all".equals(scope)) {
            return Arrays.asList(InstallScope.values());
        }
        return List.of(InstallScope.fromCliValue(scope));
    }

    private int renderDependencyTrees(Path root,
                                      List<InstallScope> scopes,
                                      InstallProgressReporter progressReporter) throws IOException {
        Path pyproject = root.resolve(PyprojectModelReader.FILE_NAME);
        PyprojectModel model = modelReader.readFile(pyproject);
        Path localRepo = resolveLocalRepository(root);
        DependencyTreeRenderer renderer = DependencyTreeRenderer.create(color);
        boolean resolutionFailure = false;
        Map<InstallScope, Future<MavenClasspathResolver.ResolvedScopeDetails>> futures = new EnumMap<>(InstallScope.class);
        Map<InstallScope, RuntimeException> failures = new EnumMap<>(InstallScope.class);
        for (InstallScope installScope : scopes) {
            progressReporter.startScope(installScope);
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (InstallScope installScope : scopes) {
                futures.put(installScope, executor.submit(() -> resolver.resolveScopeDetails(
                    model,
                    installScope,
                    localRepo,
                    offline,
                    refresh || noCache,
                    progressListener(progressReporter, installScope),
                    root
                )));
            }
            for (InstallScope installScope : scopes) {
                try {
                    MavenClasspathResolver.ResolvedScopeDetails details = futures.get(installScope).get();
                    progressReporter.finishScope(installScope, details.classpath().size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Dependency resolution was interrupted", e);
                } catch (ExecutionException e) {
                    RuntimeException failure = e.getCause() instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Dependency resolution failed", e.getCause());
                    failures.put(installScope, failure);
                    progressReporter.failScope(installScope);
                }
            }
        }
        for (InstallScope installScope : scopes) {
            RuntimeException failure = failures.get(installScope);
            if (failure == null) {
                renderer.renderScope(installScope, futures.get(installScope).resultNow().root());
            } else if (failure instanceof PyprojectModelException e
                && e.getCause() instanceof DependencyResolutionException dependencyResolutionException) {
                resolutionFailure = true;
                renderer.renderResolutionError(
                    installScope,
                    DependencyTreeRenderer.ResolutionFailure.fromException(e.getMessage(), dependencyResolutionException)
                );
            } else {
                throw failure;
            }
        }
        return resolutionFailure ? InstallExitCode.RESOLUTION_ERROR.code() : InstallExitCode.SUCCESS.code();
    }

    private Path resolveLocalRepository(Path root) {
        Path repository = localRepository == null ? MavenClasspathResolver.resolveLocalMavenRepository() : localRepository;
        if (repository.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid value for --local-repository. Value cannot be empty");
        }
        Path resolved = repository.isAbsolute() ? repository : root.resolve(repository);
        return resolved.toAbsolutePath().normalize();
    }

    public static void main(String[] args) {
        PyronautLauncherLogging.initialize();
        int exitCode = new CommandLine(new PyronautInstallMain()).execute(args);
        System.exit(exitCode);
    }

    static void initializeJavaHomeIfMissing(Supplier<String> javaHomeSupplier) {
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            return;
        }
        String javaHome = javaHomeSupplier.get();
        if (javaHome != null && !javaHome.isBlank()) {
            System.setProperty("java.home", javaHome);
        }
    }

    private static void emitEditorSupport(InstallProgressReporter progressReporter,
                                          PythonEditorSupport.EditorSupportResult result) {
        if (result == null) {
            return;
        }
        switch (result.status()) {
            case GENERATED -> progressReporter.generatedEditorStubs(result.packageCount(), result.symbolCount());
            case CACHED -> progressReporter.cachedEditorStubs();
            case NONE -> {
            }
        }
        if (result.warningCount() > 0) {
            progressReporter.editorStubsWarnings(result.warningCount(), result.warningReport());
        }
    }

    private static void emitEditorSupportBestEffort(InstallProgressReporter progressReporter,
                                                    EditorSupportAction action) {
        try {
            emitEditorSupport(progressReporter, action.execute());
        } catch (Exception e) {
            progressReporter.warn("Python editor stub generation failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface EditorSupportAction {
        PythonEditorSupport.EditorSupportResult execute() throws Exception;
    }

}
