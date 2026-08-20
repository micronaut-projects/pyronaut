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
import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.core.reflect.ClassUtils;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Combines native-image static bean introspections with introspections loaded from a runtime application classloader.
 */
public final class ContextClassLoaderBeanIntrospectionsProvider implements BeanIntrospectionsProvider {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanIntrospectionReference.class.getName();
    private static final String MEMORY_CLASS_OUTPUT_PREFIX = "mem:/CLASS_OUTPUT/";

    @Override
    public List<BeanIntrospectionReference<Object>> provide(ClassLoader classLoader) {
        Map<String, BeanIntrospectionReference<Object>> references = new LinkedHashMap<>();
        ClassLoader launcherClassLoader = ContextClassLoaderBeanIntrospectionsProvider.class.getClassLoader();
        for (BeanIntrospectionReference<Object> reference : discoverLauncherReferences(launcherClassLoader)) {
            references.put(reference.getName(), reference);
        }
        for (BeanIntrospectionReference<Object> reference : discoverRuntimeReferences(classLoader)) {
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

    private static List<BeanIntrospectionReference<Object>> discoverRuntimeReferences(ClassLoader classLoader) {
        List<BeanIntrospectionReference<Object>> references = new ArrayList<>();
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
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
}
