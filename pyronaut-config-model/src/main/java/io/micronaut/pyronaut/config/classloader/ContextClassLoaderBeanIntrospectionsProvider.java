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
package io.micronaut.pyronaut.config.classloader;

import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.core.reflect.ClassUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Combines native-image static bean introspections with introspections loaded from a runtime application classloader.
 */
public final class ContextClassLoaderBeanIntrospectionsProvider implements BeanIntrospectionsProvider {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanIntrospectionReference.class.getName();
    private static final String MEMORY_CLASS_OUTPUT_PREFIX = "mem:/CLASS_OUTPUT/";

    /**
     * The discovered references, per class loader. Weakly keyed, so a class loader that goes away
     * takes its entry with it rather than being retained by this provider.
     */
    private final Map<ClassLoader, Discovered> cache = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Discovery is a classpath walk: every {@code META-INF/micronaut} service resource of every jar
     * on the class loader, every entry of each of those jars, and a reflective instantiation of each
     * reference class found. It answers from static metadata, so the result cannot change while the
     * class loaders do not, and repeating it is pure cost.
     *
     * <p>It was being repeated per call. {@code DefaultBeanIntrospector} caches introspections only
     * for its own class loader — a lookup against the thread's context class loader, which is what a
     * Pyronaut application runs with, takes an uncached path and arrives here. Anything that resolves
     * an introspection per request therefore walked the whole classpath per request: validating a
     * request body did it twice, once for the parameters and once for the return value, because
     * resolving a validation group's introspection misses and a miss is not remembered. Measured on
     * a create endpoint with a validated body, 32 concurrent clients: 769 req/s, of which about a
     * fifth of request time was in this class.
     *
     * @param classLoader The class loader to discover from
     * @return The references, discovered once per class loader
     */
    @Override
    public List<BeanIntrospectionReference<Object>> provide(ClassLoader classLoader) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Discovered discovered = cache.get(classLoader);
        if (discovered != null && discovered.matches(contextClassLoader)) {
            return discovered.references();
        }
        List<BeanIntrospectionReference<Object>> references = discover(classLoader, contextClassLoader);
        cache.put(classLoader, new Discovered(new WeakReference<>(contextClassLoader), references));
        return references;
    }

    private static List<BeanIntrospectionReference<Object>> discover(ClassLoader classLoader, @Nullable ClassLoader contextClassLoader) {
        Map<String, BeanIntrospectionReference<Object>> references = new LinkedHashMap<>();
        ClassLoader launcherClassLoader = ContextClassLoaderBeanIntrospectionsProvider.class.getClassLoader();
        for (BeanIntrospectionReference<Object> reference : discoverLauncherReferences(launcherClassLoader)) {
            references.put(reference.getName(), reference);
        }
        for (BeanIntrospectionReference<Object> reference : discoverRuntimeReferences(classLoader, contextClassLoader)) {
            references.put(reference.getName(), reference);
        }
        return List.copyOf(references.values());
    }

    private static List<BeanIntrospectionReference<Object>> discoverLauncherReferences(ClassLoader classLoader) {
        List<BeanIntrospectionReference<Object>> references = new ArrayList<>();
        try {
            for (String className : MicronautMetaServiceLoaderUtils.findMicronautMetaServiceEntries(classLoader, BeanIntrospectionReference.class.getName())) {
                addReference(className, classLoader, references);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover bean introspections from launcher classloader", e);
        }
        return references;
    }

    private static List<BeanIntrospectionReference<Object>> discoverRuntimeReferences(ClassLoader classLoader,
                                                                                      @Nullable ClassLoader contextClassLoader) {
        List<BeanIntrospectionReference<Object>> references = new ArrayList<>();
        try {
            discoverRuntimeReferences(classLoader, classLoader, references);
            if (contextClassLoader != null && contextClassLoader != classLoader) {
                discoverRuntimeReferences(contextClassLoader, contextClassLoader, references);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover bean introspections from runtime classloader", e);
        }
        return references;
    }

    private static void discoverRuntimeReferences(ClassLoader resourcesClassLoader,
                                                   ClassLoader referenceClassLoader,
                                                   List<BeanIntrospectionReference<Object>> references) throws IOException {
        Enumeration<URL> resources = resourcesClassLoader.getResources(SERVICE_PATH);
        while (resources.hasMoreElements()) {
            collectReferences(resources.nextElement(), referenceClassLoader, references);
        }
    }

    private static void collectReferences(URL resource, ClassLoader classLoader, List<BeanIntrospectionReference<Object>> references) throws IOException {
        if ("file".equals(resource.getProtocol())) {
            collectFileReferences(resource, classLoader, references);
        } else if ("jar".equals(resource.getProtocol())) {
            collectJarReferences(resource, classLoader, references);
        } else if ("mem".equals(resource.getProtocol())) {
            collectMemoryReference(resource, classLoader, references);
        }
    }

    private static void collectMemoryReference(URL resource, ClassLoader classLoader, List<BeanIntrospectionReference<Object>> references) {
        String prefix = MEMORY_CLASS_OUTPUT_PREFIX + SERVICE_PATH + "/";
        String value = resource.toString();
        if (value.startsWith(prefix)) {
            String className = value.substring(prefix.length());
            if (!className.isEmpty() && !className.contains("/")) {
                addReference(className, classLoader, references);
            }
        }
    }

    private static void collectFileReferences(URL resource, ClassLoader classLoader, List<BeanIntrospectionReference<Object>> references) throws IOException {
        try {
            Path directory = Path.of(resource.toURI());
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (Stream<Path> children = Files.list(directory)) {
                children
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(className -> addReference(className, classLoader, references));
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bean introspection service URL: " + resource, e);
        }
    }

    private static void collectJarReferences(URL resource, ClassLoader classLoader, List<BeanIntrospectionReference<Object>> references) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        String prefix = connection.getEntryName();
        if (prefix == null) {
            return;
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        try (JarFile jarFile = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(prefix)) {
                    String className = name.substring(prefix.length());
                    if (!className.isEmpty() && !className.contains("/")) {
                        addReference(className, classLoader, references);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addReference(String className, ClassLoader classLoader, List<BeanIntrospectionReference<Object>> references) {
        Object instance = instantiateReference(className, classLoader);
        if (instance instanceof BeanIntrospectionReference<?> reference) {
            try {
                if (reference.isPresent()) {
                    references.add((BeanIntrospectionReference<Object>) reference);
                }
            } catch (Throwable ignored) {
                // Optional integrations can reference classes absent from the application classpath.
            }
        }
    }

    private static Object instantiateReference(String className, ClassLoader classLoader) {
        try {
            Class<?> type = ClassUtils.forName(className, classLoader).orElse(null);
            return type == null ? null : type.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * A discovery result, valid while the context class loader it was made with is still the current
     * one. The context class loader is part of what was walked, so a different one means a different
     * answer; it is held weakly so that caching a result does not keep a class loader alive.
     *
     * @param contextClassLoader The context class loader the walk used, weakly
     * @param references The references found
     */
    private record Discovered(WeakReference<ClassLoader> contextClassLoader,
                              List<BeanIntrospectionReference<Object>> references) {
        boolean matches(@Nullable ClassLoader current) {
            return contextClassLoader.get() == current;
        }
    }
}
