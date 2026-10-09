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
import java.nio.charset.StandardCharsets;
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
        private static final String APPLICATION_VFS_FILES_LIST = "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt";

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
                if (request.incremental()) {
                    pruneMissingFilesListEntries(request.targetDir());
                }
            } finally {
                BeanIntrospectionProviders.set(previousProvider);
            }
        }

        /**
         * Drops the entries of the VFS file list whose files an incremental compilation removed.
         * The incremental compiler rebuilds the list before it deletes the replaced Python outputs,
         * such as the Java imports manifest named by the hash of its content, and GraalPy refuses to
         * mount a VFS listing a missing file.
         *
         * @param targetDir The compilation output
         */
        static void pruneMissingFilesListEntries(Path targetDir) {
            Path filesList = targetDir.resolve(APPLICATION_VFS_FILES_LIST);
            if (!Files.isRegularFile(filesList)) {
                return;
            }
            try {
                List<String> entries = Files.readAllLines(filesList, StandardCharsets.UTF_8);
                List<String> present = entries.stream()
                    .filter(entry -> !entry.isBlank())
                    .filter(entry -> Files.isRegularFile(targetDir.resolve(entry.startsWith("/") ? entry.substring(1) : entry)))
                    .toList();
                if (present.size() != entries.size()) {
                    Files.writeString(filesList, present.isEmpty() ? "" : String.join("\n", present) + '\n', StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new PyronautProcessorException("Failed to update the VFS file list: " + filesList, e);
            }
        }

        private static List<File> toFiles(List<Path> paths) {
            return paths.stream().map(Path::toFile).toList();
        }
    }
}
