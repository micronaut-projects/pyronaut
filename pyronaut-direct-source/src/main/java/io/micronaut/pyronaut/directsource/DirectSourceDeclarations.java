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

import java.util.List;
import java.util.Map;

/**
 * Declarations collected from direct Java or Python sources.
 *
 * @param dependencies declared Maven dependencies
 * @param repositories declared Maven repositories
 * @param buildProperties build-time application properties
 * @param runtimeProperties runtime application properties
 */
public record DirectSourceDeclarations(
    List<Dependency> dependencies,
    List<String> repositories,
    Map<String, String> buildProperties,
    Map<String, String> runtimeProperties
) {
    /**
     * Creates an immutable declaration set.
     */
    public DirectSourceDeclarations {
        dependencies = List.copyOf(dependencies);
        repositories = List.copyOf(repositories);
        buildProperties = Map.copyOf(buildProperties);
        runtimeProperties = Map.copyOf(runtimeProperties);
    }

    /**
     * @return whether no direct declarations were found
     */
    public boolean isEmpty() {
        return dependencies.isEmpty() && repositories.isEmpty()
            && buildProperties.isEmpty() && runtimeProperties.isEmpty();
    }

    /**
     * A Maven coordinate requested by a direct-source declaration.
     *
     * @param coordinate Maven coordinate
     * @param build whether the dependency is build-scoped
     */
    public record Dependency(String coordinate, boolean build) {
    }
}
