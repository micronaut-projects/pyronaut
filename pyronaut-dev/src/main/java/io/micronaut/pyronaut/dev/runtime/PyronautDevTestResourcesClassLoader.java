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

import io.micronaut.core.annotation.Internal;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Resolves the isolated native test-resources client classloader.
 */
@Internal
final class PyronautDevTestResourcesClassLoader {
    static final String CLIENT_CLASSPATH_PROPERTY = "pyronaut.dev.test.resources.client.classpath";
    // by name: this class also runs on the classpath of a plain development run, which does not hold micronaut-dev
    private static final String GENERATION_LOADER_CLASS = "io.micronaut.dev.loader.GenerationClassLoader";

    private static final ConcurrentMap<String, ClassLoader> CLASS_LOADERS = new ConcurrentHashMap<>();

    private PyronautDevTestResourcesClassLoader() {
    }

    static ClassLoader resolve(ClassLoader fallback) {
        String classpath = System.getProperty(CLIENT_CLASSPATH_PROPERTY);
        if (classpath == null || classpath.isBlank()) {
            return fallback;
        }
        return CLASS_LOADERS.computeIfAbsent(classpath, value -> buildClassLoader(value, withoutGenerations(fallback)));
    }

    /**
     * The loader the client's own is parented on. There is one client loader per process, while a loader of the
     * development runtime's generations serves one restart only: parented on the generation that first asked, the client
     * loader would keep that generation, and the classes of the application it holds, for as long as the process lives,
     * and hand those old classes to whatever looks them up through it. The client needs only the parent tier's.
     */
    private static ClassLoader withoutGenerations(ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null && GENERATION_LOADER_CLASS.equals(current.getClass().getName())) {
            current = current.getParent();
        }
        return current;
    }

    private static ClassLoader buildClassLoader(String classpath, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
            } catch (Exception e) {
                throw new IllegalStateException("Invalid test resources client classpath entry: " + entry, e);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), parent);
    }
}
