/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.cli.commands;

/**
 * Constants for dependency scopes used in pyproject.toml and Gradle build scripts.
 * The constant names refer to typical Java ecosystem scopes, and mapped to names
 * which would make more sense in Python context (e.g. "compile" -> "runtime", "annotationProcessor" -> "build").
 */
final class DependencyScopes {
    static final String COMPILE = "runtime";
    static final String TEST = "test";
    static final String ANNOTATION_PROCESSOR = "build";
}
