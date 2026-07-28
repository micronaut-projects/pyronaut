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

import io.micronaut.python.compiler.PyronautCompiler;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the same declaration visitors used by direct execution without launching the application.
 */
public final class DirectSourceDiscovery {
    /**
     * Supported source languages.
     */
    public enum Language {
        JAVA,
        PYTHON
    }

    /**
     * Discovers declarations from a staged source directory.
     *
     * @param language source language
     * @param sourceDirectory directory containing only the selected sources
     * @param classpath compiler classpath
     * @return collected declarations, or an empty result
     */
    public DirectSourceDeclarations discover(Language language,
                                             Path sourceDirectory,
                                             List<Path> classpath) {
        DirectSourceDeclarationsVisitor visitor = new DirectSourceDeclarationsVisitor();
        PyronautCompiler.Builder builder = PyronautCompiler.builder()
            .annotationProcessorPath(toFiles(classpath))
            .classpath(toFiles(classpath))
            .runtimeClasspath(toFiles(classpath))
            .annotationProcessors(List.of(new DirectSourceDeclarationsProcessor()))
            .options(List.of("-proc:only", "-Amicronaut.openapi.enabled=false"));
        if (language == Language.JAVA) {
            builder.javaSrc(sourceDirectory.toString());
        } else {
            builder.pythonSrc(sourceDirectory.toString())
                .pythonSourceVisitors(List.of(visitor));
        }
        try {
            builder.build().buildClassLoader();
            return new DirectSourceDeclarations(List.of(), List.of(), java.util.Map.of(), java.util.Map.of());
        } catch (DirectSourceDeclarationRequest request) {
            return request.declarations();
        }
    }

    private static List<File> toFiles(List<Path> paths) {
        return paths.stream().map(Path::toFile).toList();
    }
}
