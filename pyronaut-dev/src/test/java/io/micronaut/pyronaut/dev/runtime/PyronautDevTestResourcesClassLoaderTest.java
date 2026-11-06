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
package io.micronaut.pyronaut.dev.runtime;

import io.micronaut.dev.loader.DevClassLoader;
import io.micronaut.dev.loader.GenerationClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PyronautDevTestResourcesClassLoaderTest {

    @TempDir
    Path directory;

    @Test
    void theClientsLoaderIsNotParentedOnTheGenerationThatFirstAsksAndSeesNoneOfTheApplicationsClasses() throws Exception {
        Path processed = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(processed.resolve("application-class.txt"), "of the application");
        ClassLoader parentTier = getClass().getClassLoader();
        // a classpath of this test's own: the loaders are kept per classpath, for the life of the process
        Path client = Files.createDirectories(directory.resolve("client"));
        String previous = System.getProperty(PyronautDevTestResourcesClassLoader.CLIENT_CLASSPATH_PROPERTY);
        System.setProperty(PyronautDevTestResourcesClassLoader.CLIENT_CLASSPATH_PROPERTY, client.toString());
        try {
            GenerationClassLoader generation = new DevClassLoader(parentTier, List.of(), List.of(processed), directory.resolve("generations")).current();
            assertNotNull(generation.getResource("application-class.txt"));

            ClassLoader loader = PyronautDevTestResourcesClassLoader.resolve(generation);

            for (ClassLoader current = loader; current != null; current = current.getParent()) {
                assertFalse(current instanceof GenerationClassLoader, "kept by the client's loader: " + current);
            }
            assertEquals(parentTier, loader.getParent());
            assertNull(loader.getResource("application-class.txt"));
            // one loader per process, whichever generation asks next
            assertSame(loader, PyronautDevTestResourcesClassLoader.resolve(parentTier));
        } finally {
            if (previous == null) {
                System.clearProperty(PyronautDevTestResourcesClassLoader.CLIENT_CLASSPATH_PROPERTY);
            } else {
                System.setProperty(PyronautDevTestResourcesClassLoader.CLIENT_CLASSPATH_PROPERTY, previous);
            }
        }
    }
}
