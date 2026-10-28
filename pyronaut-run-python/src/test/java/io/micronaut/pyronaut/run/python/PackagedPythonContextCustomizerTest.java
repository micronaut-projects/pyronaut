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
import org.graalvm.python.embedding.VirtualFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedPythonContextCustomizerTest {
    @TempDir
    Path temporary;

    @Test
    void registersCustomizer() {
        assertTrue(ServiceLoader.load(GraalPyContextCustomizer.class).stream()
            .anyMatch(provider -> provider.type() == PackagedPythonContextCustomizer.class));
    }

    @Test
    void preservesTheDevelopmentEnvironment() throws Exception {
        try (var loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, null)) {
            assertNull(PackagedPythonContextCustomizer.packagedExecutable(loader));
        }
    }

    @Test
    void selectsThePackagedEnvironment() throws Exception {
        String resources = "META-INF/GRAALPY-VFS/micronaut-application";
        Path root = temporary.resolve(resources);
        Files.createDirectories(root.resolve("venv"));
        Files.writeString(root.resolve("venv/pyronaut-packaged.txt"), "packaged\n");
        Files.writeString(root.resolve("fileslist.txt"), "/" + resources + "/venv/pyronaut-packaged.txt\n");
        try (var loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, null);
             var vfs = VirtualFileSystem.newBuilder().resourceDirectory(resources).resourceClassLoader(loader).build()) {
            assertEquals(Path.of(vfs.getMountPoint(), "venv", "bin", "python"),
                PackagedPythonContextCustomizer.packagedExecutable(loader));
        }
    }
}
