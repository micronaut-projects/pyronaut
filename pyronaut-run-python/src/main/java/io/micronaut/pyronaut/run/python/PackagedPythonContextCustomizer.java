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
package io.micronaut.pyronaut.run.python;

import io.micronaut.context.python.GraalPyContextCustomizer;
import io.micronaut.core.annotation.Internal;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps an activated host environment from overriding packaged Python dependencies.
 */
@Internal
public final class PackagedPythonContextCustomizer implements GraalPyContextCustomizer {
    private static final String RESOURCES = "META-INF/GRAALPY-VFS/micronaut-application";
    private static final String MARKER = "pyronaut-packaged.txt";

    @Override
    public void customize(Context.Builder builder) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Path executable = loader == null ? null : packagedExecutable(loader);
        if (executable == null) {
            executable = stagedExecutable(GraalPyResources.getNativeExecutablePath());
        }
        if (executable != null) {
            builder.option("python.Executable", executable.toString());
        }
    }

    /**
     * A native base executable embeds no application resources and runs the
     * processed classes of its project directory, which no longer hold the
     * packaged environment after the build. The build stages that environment
     * beside the executable instead.
     *
     * @param nativeExecutable the running native executable, or {@code null} on the JVM
     * @return the interpreter of the environment staged beside it, if any
     */
    static @Nullable Path stagedExecutable(@Nullable Path nativeExecutable) {
        Path directory = nativeExecutable == null ? null : nativeExecutable.toAbsolutePath().getParent();
        if (directory == null || !Files.isRegularFile(directory.resolve("venv").resolve(MARKER))) {
            return null;
        }
        return directory.resolve("venv").resolve("bin").resolve("python");
    }

    static @Nullable Path packagedExecutable(ClassLoader loader) {
        if (loader.getResource(RESOURCES + "/venv/" + MARKER) == null) {
            return null;
        }
        try (var vfs = VirtualFileSystem.newBuilder().resourceDirectory(RESOURCES).resourceClassLoader(loader).build()) {
            return Path.of(vfs.getMountPoint(), "venv", "bin", "python");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot locate the packaged Python environment", e);
        }
    }
}
