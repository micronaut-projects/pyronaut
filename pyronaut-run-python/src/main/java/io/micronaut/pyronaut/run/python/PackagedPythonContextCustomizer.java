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
import org.graalvm.python.embedding.VirtualFileSystem;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Keeps an activated host environment from overriding packaged Python dependencies.
 */
@Internal
public final class PackagedPythonContextCustomizer implements GraalPyContextCustomizer {
    private static final String RESOURCES = "META-INF/GRAALPY-VFS/micronaut-application";

    @Override
    public void customize(Context.Builder builder) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            Path executable = packagedExecutable(loader);
            if (executable != null) {
                builder.option("python.Executable", executable.toString());
            }
        }
    }

    static @Nullable Path packagedExecutable(ClassLoader loader) {
        if (loader.getResource(RESOURCES + "/venv/pyronaut-packaged.txt") == null) {
            return null;
        }
        try (var vfs = VirtualFileSystem.newBuilder().resourceDirectory(RESOURCES).resourceClassLoader(loader).build()) {
            return Path.of(vfs.getMountPoint(), "venv", "bin", "python");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot locate the packaged Python environment", e);
        }
    }
}
