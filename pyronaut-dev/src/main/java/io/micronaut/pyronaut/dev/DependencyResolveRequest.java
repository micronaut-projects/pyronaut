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
package io.micronaut.pyronaut.dev;

import io.micronaut.python.compiler.PyronautCompilerException;
import java.util.List;
import java.util.Map;

/** Requests direct-source dependency resolution before compilation is retried. */
final class DependencyResolveRequest extends PyronautCompilerException {
    private final List<Declaration> dependencies;
    private final List<String> repositories;
    private final Map<String, String> buildProperties;
    private final Map<String, String> runtimeProperties;

    /**
     * @param dependencies declared Maven dependencies
     * @param repositories declared Maven repositories
     * @param buildProperties build-time application properties
     * @param runtimeProperties runtime application properties
     */
    DependencyResolveRequest(List<Declaration> dependencies, List<String> repositories,
                             Map<String, String> buildProperties, Map<String, String> runtimeProperties) {
        super("Direct source dependencies require resolution");
        this.dependencies = List.copyOf(dependencies);
        this.repositories = List.copyOf(repositories);
        this.buildProperties = Map.copyOf(buildProperties);
        this.runtimeProperties = Map.copyOf(runtimeProperties);
    }
    /** @return declared dependencies */
    List<Declaration> dependencies() { return dependencies; }
    /** @return declared repositories */
    List<String> repositories() { return repositories; }
    /** @return build-time properties */
    Map<String, String> buildProperties() { return buildProperties; }
    /** @return runtime properties */
    Map<String, String> runtimeProperties() { return runtimeProperties; }

    /**
     * A Maven coordinate requested by a direct-source declaration.
     *
     * @param coordinate Maven coordinate
     * @param build whether the dependency is build-scoped
     */
    record Declaration(String coordinate, boolean build) {
    }
}
