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
package io.micronaut.pyronaut.directsource;

import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.python.processing.PythonCall;
import io.micronaut.python.processing.PythonSource;
import io.micronaut.python.processing.PythonSourceVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects inline declarations from direct-source Python calls.
 */
public final class DirectSourceDeclarationsVisitor implements PythonSourceVisitor {
    private static final String BUILD_SCOPE = "BUILD";
    private static final String SCOPE_SEPARATOR = ".";
    private final List<DirectSourceDeclarations.Dependency> dependencies = new ArrayList<>();
    private final List<String> repositories = new ArrayList<>();
    private final Map<String, String> build = new LinkedHashMap<>();
    private final Map<String, String> runtime = new LinkedHashMap<>();

    @Override
    public void visit(PythonSource source, VisitorContext context) {
        if (DirectSourceDeclarationState.isResolved()) {
            return;
        }
        for (PythonCall call : source.calls()) {
            Map<String, String> values = call.keywordArguments();
            boolean buildScope = isBuildScope(values.get("scope"));
            switch (call.name()) {
                case "Dependency" -> addDependency(values, buildScope);
                case "MavenRepository" -> addRepository(call, values);
                case "AppConfig" -> addProperty(values, buildScope);
                default -> {
                    // The AST visitor deliberately exposes all calls. Ignore unrelated ones.
                }
            }
        }
    }

    @Override
    public void finish(VisitorContext context) {
        var declarations = declarations();
        if (!declarations.isEmpty()) {
            throw new DirectSourceDeclarationRequest(declarations);
        }
    }

    private void addDependency(Map<String, String> values, boolean buildScope) {
        String group = values.get("group");
        String module = values.get("module");
        if (group != null && module != null) {
            String version = values.get("version");
            dependencies.add(new DirectSourceDeclarations.Dependency(
                group + ":" + module + (version == null || version.isBlank() ? "" : ":" + version),
                buildScope
            ));
        }
    }

    private void addRepository(PythonCall call, Map<String, String> values) {
        String repository = values.get("value");
        if (repository == null && !call.arguments().isEmpty()) {
            repository = call.arguments().getFirst();
        }
        if (repository != null && !repository.isBlank()) {
            repositories.add(repository);
        }
    }

    private void addProperty(Map<String, String> values, boolean buildScope) {
        String name = values.get("name");
        String value = values.get("value");
        if (name != null && value != null) {
            (buildScope ? build : runtime).put(name, value);
        }
    }

    private static boolean isBuildScope(String scope) {
        return scope != null && (scope.equals(BUILD_SCOPE) || scope.endsWith(SCOPE_SEPARATOR + BUILD_SCOPE));
    }

    private DirectSourceDeclarations declarations() {
        return new DirectSourceDeclarations(dependencies, repositories, build, runtime);
    }
}
