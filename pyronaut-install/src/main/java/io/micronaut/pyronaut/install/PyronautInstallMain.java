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
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Entry point for {@code pyronaut-install}.
 */
@CommandLine.Command(name = "pyronaut-install", mixinStandardHelpOptions = true, description = "Resolve and cache project dependencies")
public final class PyronautInstallMain implements Callable<Integer> {
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";

    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory containing pyproject.toml")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--scope", description = "Scope to resolve: build|runtime|development-runtime|test|test-resources-server|all")
    String scope;

    @CommandLine.Option(names = "--dependencies", description = "Render dependency output instead of install-focused progress")
    boolean dependencies;

    @CommandLine.Option(names = "--progress", defaultValue = "auto", description = "Progress output mode: auto|on|off")
    String progress = "auto";

    @CommandLine.Option(names = "--color", defaultValue = "auto", description = "Color output mode: auto|always|never")
    String color = "auto";

    @CommandLine.Option(names = "--refresh", description = "Force dependency re-resolution and manifest rewrite without clearing the local dependency cache")
    boolean refresh;

    @CommandLine.Option(names = "--no-cache", description = "Bypass cache reads/writes and clear the local dependency cache before resolving")
    boolean noCache;

    @CommandLine.Option(names = "--offline", description = "Use offline mode for repository access")
    boolean offline;

    private final PyprojectModelReader modelReader;
    private final MavenClasspathResolver resolver;
    private final PyprojectEditorSupport editorSupport;
    private final PythonEditorSupport pythonEditorSupport;

    public PyronautInstallMain() {
        this(new PyprojectModelReader(), new MavenClasspathResolver(), new PyprojectEditorSupport(), new PythonEditorSupport());
    }

    PyronautInstallMain(PyprojectModelReader modelReader, MavenClasspathResolver resolver) {
        this(modelReader, resolver, new PyprojectEditorSupport(), new PythonEditorSupport());
    }

    PyronautInstallMain(PyprojectModelReader modelReader, MavenClasspathResolver resolver, PyprojectEditorSupport editorSupport) {
        this(modelReader, resolver, editorSupport, new PythonEditorSupport());
    }

    PyronautInstallMain(PyprojectModelReader modelReader,
                        MavenClasspathResolver resolver,
                        PyprojectEditorSupport editorSupport,
                        PythonEditorSupport pythonEditorSupport) {
        this.modelReader = modelReader;
        this.resolver = resolver;
        this.editorSupport = editorSupport;
        this.pythonEditorSupport = pythonEditorSupport;
    }

    @Override
    public Integer call() {
        try (ChecksumWarningFilter ignored = ChecksumWarningFilter.install()) {
            Path root = projectDir.toAbsolutePath().normalize();
            Path pyproject = root.resolve(PyprojectModelReader.FILE_NAME);
            InstallProgressReporter.ProgressMode.fromCliValue(progress);
            DependencyTreeRenderer.ColorMode.fromCliValue(color);
            List<InstallScope> scopes = selectedScopes();

            PyprojectModel model = modelReader.readFile(pyproject);
            Path cacheDir = root.resolve(DEFAULT_PYRONAUT_DIR);
            editorSupport.ensureWritten(root, cacheDir);
            String hash = ResolutionCache.pyprojectHash(pyproject);
            try (InstallProgressReporter progressReporter = InstallProgressReporter.create(progress)) {
                if (dependencies) {
                    return renderDependencyTrees(root, scopes, progressReporter);
                }
                boolean bypassRequested = refresh || noCache;
                if (!bypassRequested && ResolutionCache.cacheHit(cacheDir, hash, scopes)) {
                    progressReporter.cacheHit();
                    emitEditorSupportBestEffort(
                        progressReporter,
                        () -> pythonEditorSupport.ensureWrittenFromManifests(root, cacheDir, model.pyronaut().ideStubs())
                    );
                    return InstallExitCode.SUCCESS.code();
                }
                if (bypassRequested) {
                    progressReporter.cacheBypass();
                }

                Path localRepo = cacheDir.resolve("m2-repository");
                if (noCache) {
                    deleteDirectoryIfExists(localRepo);
                }
                Map<InstallScope, List<String>> resolved = new EnumMap<>(InstallScope.class);
                Map<InstallScope, List<MavenClasspathResolver.ResolvedEditorArtifact>> resolvedEditorArtifacts = new EnumMap<>(InstallScope.class);
                for (InstallScope installScope : scopes) {
                    progressReporter.startScope(installScope);
                    MavenClasspathResolver.ResolvedScopeDetails details = resolver.resolveScopeDetails(model, installScope, localRepo, offline);
                    List<String> classpath = details.classpath().stream()
                        .map(path -> path.toAbsolutePath().toString())
                        .toList();
                    progressReporter.finishScope(installScope, classpath.size());
                    resolved.put(installScope, classpath);
                    resolvedEditorArtifacts.put(installScope, details.editorArtifacts());
                }
                if (resolved.containsKey(InstallScope.RUNTIME)) {
                    var schemaResult = editorSupport.ensureApplicationSchema(root, cacheDir, resolved.get(InstallScope.RUNTIME));
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
            }
            return InstallExitCode.SUCCESS.code();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return InstallExitCode.CONFIG_ERROR.code();
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            if (e.getCause() instanceof org.eclipse.aether.resolution.DependencyResolutionException) {
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
        Path localRepo = root.resolve(DEFAULT_PYRONAUT_DIR).resolve("m2-repository");
        DependencyTreeRenderer renderer = DependencyTreeRenderer.create(color);
        boolean resolutionFailure = false;
        for (InstallScope installScope : scopes) {
            progressReporter.startScope(installScope);
            try {
                MavenClasspathResolver.ResolvedScopeDetails details = resolver.resolveScopeDetails(model, installScope, localRepo, offline);
                progressReporter.finishScope(installScope, details.classpath().size());
                renderer.renderScope(installScope, details.root());
            } catch (PyprojectModelException e) {
                progressReporter.finishScope(installScope, 0);
                if (e.getCause() instanceof DependencyResolutionException dependencyResolutionException) {
                    resolutionFailure = true;
                    renderer.renderResolutionError(
                        installScope,
                        DependencyTreeRenderer.ResolutionFailure.fromException(e.getMessage(), dependencyResolutionException)
                    );
                    continue;
                }
                throw e;
            }
        }
        return resolutionFailure ? InstallExitCode.RESOLUTION_ERROR.code() : InstallExitCode.SUCCESS.code();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautInstallMain()).execute(args);
        System.exit(exitCode);
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

    private static void deleteDirectoryIfExists(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
