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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Entry point for {@code pyronaut-install}.
 */
@CommandLine.Command(name = "pyronaut-install", mixinStandardHelpOptions = true, description = "Resolve and cache project dependencies")
public final class PyronautInstallMain implements Callable<Integer> {

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory containing pyproject.toml")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--scope", description = "Single scope to resolve: build|runtime|test")
    String scope;

    @CommandLine.Option(names = "--refresh", description = "Force dependency re-resolution and cache rewrite")
    boolean refresh;

    @CommandLine.Option(names = "--offline", description = "Use offline mode for repository access")
    boolean offline;

    private final PyprojectModelReader modelReader;
    private final MavenClasspathResolver resolver;

    public PyronautInstallMain() {
        this(new PyprojectModelReader(), new MavenClasspathResolver());
    }

    PyronautInstallMain(PyprojectModelReader modelReader, MavenClasspathResolver resolver) {
        this.modelReader = modelReader;
        this.resolver = resolver;
    }

    @Override
    public Integer call() {
        try {
            Path root = projectDir.toAbsolutePath().normalize();
            Path pyproject = root.resolve(PyprojectModelReader.FILE_NAME);
            List<InstallScope> scopes = selectedScopes();

            String hash = ResolutionCache.pyprojectHash(pyproject);
            Path cacheDir = root.resolve(".pytest_cache");
            if (!refresh && ResolutionCache.cacheHit(cacheDir, hash, scopes)) {
                return InstallExitCode.SUCCESS.code();
            }

            PyprojectModel model = modelReader.readFile(pyproject);
            Path localRepo = cacheDir.resolve("m2-repository");
            Map<InstallScope, List<String>> resolved = new EnumMap<>(InstallScope.class);
            for (InstallScope installScope : scopes) {
                List<String> classpath = resolver.resolveScope(model, installScope, localRepo, offline)
                    .stream()
                    .map(path -> path.toAbsolutePath().toString())
                    .toList();
                resolved.put(installScope, classpath);
            }
            ResolutionCache.write(cacheDir, hash, resolved);
            return InstallExitCode.SUCCESS.code();
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
            return List.of(InstallScope.BUILD, InstallScope.RUNTIME, InstallScope.TEST);
        }
        return List.of(InstallScope.fromCliValue(scope));
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautInstallMain()).execute(args);
        System.exit(exitCode);
    }
}
