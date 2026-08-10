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
package io.micronaut.pyronaut.processor;

import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.python.compiler.PyronautCompiler;
import io.micronaut.python.compiler.PythonIncrementalMode;
import io.micronaut.python.processing.PythonProcessingSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bridge to {@link PyronautCompiler} for testability.
 */
interface PyronautCompilerExecutor {

    void compile(CompileRequest request);

    record CompileRequest(Path pythonSrc,
                          Path javaSrc,
                          Path targetDir,
                          List<Path> annotationProcessorPath,
                          List<Path> classpath,
                          boolean compilePythonBytecode,
                          boolean incremental,
                          PythonIncrementalMode pythonIncrementalMode,
                          Path incrementalCacheDirectory,
                          List<String> options,
                          Consumer<PyronautCompiler.IncrementalCompilationPlan>
                              incrementalCompilationPlanCallback) {
    }

    final class Default implements PyronautCompilerExecutor {
        private final PythonProcessingSession processingSession;

        Default() {
            this(null);
        }

        Default(PythonProcessingSession processingSession) {
            this.processingSession = processingSession;
        }

        @Override
        public void compile(CompileRequest request) {
            try {
                Files.createDirectories(request.targetDir());
            } catch (IOException e) {
                throw new PyronautProcessorException("Failed to create target directory: " + request.targetDir(), e);
            }
            BeanIntrospectionsProvider previousProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            try {
                PyronautCompiler.Builder builder = PyronautCompiler.builder()
                    .pythonSrc(request.pythonSrc().toString())
                    .javaSrc(request.javaSrc().toString())
                    .targetDir(request.targetDir().toFile())
                    .annotationProcessorPath(toFiles(request.annotationProcessorPath()))
                    .classpath(toFiles(request.classpath()))
                    .compilePythonBytecode(request.compilePythonBytecode())
                    .incremental(request.incremental())
                    .pythonIncrementalMode(request.pythonIncrementalMode())
                    .incrementalCacheDirectory(request.incrementalCacheDirectory().toFile())
                    .options(request.options());
                if (request.incrementalCompilationPlanCallback() != null) {
                    builder.incrementalCompilationPlanCallback(
                        request.incrementalCompilationPlanCallback()
                    );
                }
                if (processingSession != null) {
                    builder.pythonProcessingSession(processingSession);
                }
                builder.build().compile();
            } finally {
                BeanIntrospectionProviders.set(previousProvider);
            }
        }

        private static List<File> toFiles(List<Path> paths) {
            return paths.stream().map(Path::toFile).toList();
        }
    }
}
