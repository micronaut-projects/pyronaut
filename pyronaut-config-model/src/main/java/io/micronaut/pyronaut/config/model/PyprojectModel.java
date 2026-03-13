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
package io.micronaut.pyronaut.config.model;

import java.util.List;

/**
 * Immutable model for {@code pyproject.toml}.
 *
 * @param project project table
 * @param buildSystem build-system table
 * @param pyronaut tool.pyronaut table
 */
public record PyprojectModel(Project project,
                             BuildSystem buildSystem,
                             Pyronaut pyronaut) {

    /**
     * Project table.
     *
     * @param name project name
     * @param version project version
     * @param dynamic dynamic project fields
     */
    public record Project(String name,
                          String version,
                          List<String> dynamic) {
    }

    /**
     * Build-system table.
     *
     * @param requires build requirements
     * @param buildBackend build backend value
     */
    public record BuildSystem(List<String> requires,
                              String buildBackend) {
    }

    /**
     * tool.pyronaut table.
     *
     * @param version pyronaut version
     * @param repositories configured repositories
     * @param dependencies dependency scopes
     * @param build build defaults/settings
     */
    public record Pyronaut(String version,
                           List<String> repositories,
                           Dependencies dependencies,
                           Build build) {
    }

    /**
     * tool.pyronaut.dependencies table.
     *
     * @param runtime runtime dependencies
     * @param build build dependencies
     * @param test test dependencies
     */
    public record Dependencies(List<String> runtime,
                               List<String> build,
                               List<String> test) {
    }

    /**
     * tool.pyronaut.build table.
     *
     * @param mode default build mode (for example jvm or native)
     */
    public record Build(String mode) {
    }
}
