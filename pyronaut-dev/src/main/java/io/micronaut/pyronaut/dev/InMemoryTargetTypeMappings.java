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
package io.micronaut.pyronaut.dev;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exposes the {@code TargetTypeMapping} services that direct-source processing generates in memory.
 *
 * <p>The processor registers each mapping as an entry below
 * {@code META-INF/micronaut/io.micronaut.context.python.TargetTypeMapping/}. Micronaut finds those
 * entries by walking {@code file:} and {@code jar:} directories, which cannot see the
 * {@code mem:} class output of an in-memory compilation. A GraalPy context bootstrapped from that
 * class loader therefore has no mappings, and Python objects reach erased Java parameters (such as
 * {@code CrudRepository.save}) unconverted. This class loader republishes the entries as a standard
 * {@code META-INF/services} file, which Micronaut reads through {@link ClassLoader#getResources}.</p>
 */
final class InMemoryTargetTypeMappings extends ClassLoader {
    static final String SERVICE_NAME = "io.micronaut.context.python.TargetTypeMapping";
    private static final String MICRONAUT_ENTRIES = "META-INF/micronaut/" + SERVICE_NAME;
    private static final String SERVICES_FILE = "META-INF/services/" + SERVICE_NAME;
    private static final String IN_MEMORY_ENTRY_PREFIX = "/CLASS_OUTPUT/" + MICRONAUT_ENTRIES + "/";

    private final URL servicesFile;

    private InMemoryTargetTypeMappings(ClassLoader parent, URL servicesFile) {
        super(parent);
        this.servicesFile = servicesFile;
    }

    /**
     * Wraps the class loader when it holds in-memory mappings.
     *
     * @param classLoader The class loader of the in-memory compilation
     * @return A class loader that also publishes the mappings as a services file, or the given one when there are none
     * @throws IOException If the class loader cannot list its resources
     */
    static ClassLoader expose(ClassLoader classLoader) throws IOException {
        Set<String> mappings = inMemoryMappings(classLoader);
        if (mappings.isEmpty()) {
            return classLoader;
        }
        byte[] content = (String.join("\n", mappings) + "\n").getBytes(StandardCharsets.UTF_8);
        URL servicesFile = URL.of(URI.create("pyronaut-services:/" + SERVICES_FILE), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                        connected = true;
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(content);
                    }
                };
            }
        });
        return new InMemoryTargetTypeMappings(classLoader, servicesFile);
    }

    static Set<String> inMemoryMappings(ClassLoader classLoader) throws IOException {
        Set<String> mappings = new LinkedHashSet<>();
        Enumeration<URL> entries = classLoader.getResources(MICRONAUT_ENTRIES);
        while (entries.hasMoreElements()) {
            String path = entries.nextElement().getPath();
            int start = path.indexOf(IN_MEMORY_ENTRY_PREFIX);
            if (start >= 0) {
                String name = URLDecoder.decode(path.substring(start + IN_MEMORY_ENTRY_PREFIX.length()), StandardCharsets.UTF_8);
                if (!name.isEmpty() && name.indexOf('/') < 0) {
                    mappings.add(name);
                }
            }
        }
        return mappings;
    }

    @Override
    protected URL findResource(String name) {
        return SERVICES_FILE.equals(name) ? servicesFile : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        return SERVICES_FILE.equals(name) ? Collections.enumeration(Set.of(servicesFile)) : Collections.emptyEnumeration();
    }
}
