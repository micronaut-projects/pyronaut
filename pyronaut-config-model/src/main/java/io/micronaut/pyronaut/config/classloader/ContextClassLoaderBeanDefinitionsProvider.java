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

import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.BeanDefinitionReference;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Combines native-image static bean definitions with definitions loaded from a runtime application classloader.
 */
public final class ContextClassLoaderBeanDefinitionsProvider implements BeanDefinitionsProvider {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanDefinitionReference.class.getName();

    @Override
    public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
        ClassLoader runtimeClassLoader = Thread.currentThread().getContextClassLoader();
        if (runtimeClassLoader == null) {
            runtimeClassLoader = classLoader;
        }
        Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
        ClassLoader launcherClassLoader = ContextClassLoaderBeanDefinitionsProvider.class.getClassLoader();
        for (BeanDefinitionReference<?> reference : discoverLauncherReferences(launcherClassLoader)) {
            references.put(reference.getBeanDefinitionName(), reference);
        }
        for (BeanDefinitionReference<?> reference : discoverRuntimeReferences(runtimeClassLoader)) {
            references.put(reference.getBeanDefinitionName(), reference);
        }
        return List.copyOf(references.values());
    }

    private static List<BeanDefinitionReference<?>> discoverLauncherReferences(ClassLoader classLoader) {
        List<BeanDefinitionReference<?>> references = new ArrayList<>();
        try {
            for (String className : MicronautMetaServiceLoaderUtils.findMicronautMetaServiceEntries(classLoader, BeanDefinitionReference.class.getName())) {
                if (className.startsWith("io.micronaut.controlpanel.")) {
                    continue;
                }
                addReference(className, classLoader, references, false);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover bean definitions from launcher classloader", e);
        }
        return references;
    }

    private static List<BeanDefinitionReference<?>> discoverRuntimeReferences(ClassLoader classLoader) {
        List<BeanDefinitionReference<?>> references = new ArrayList<>();
        Set<String> discoveredResources = new HashSet<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(SERVICE_PATH);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                discoveredResources.add(resource.toExternalForm());
                collectReferences(resource, classLoader, references);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover bean definitions from runtime classloader", e);
        }
        discoverClasspathReferences(classLoader, references, discoveredResources);
        return references;
    }

    private static void discoverClasspathReferences(ClassLoader classLoader,
                                                    List<BeanDefinitionReference<?>> references,
                                                    Set<String> discoveredResources) {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry);
                URL resource;
                if (Files.isDirectory(path)) {
                    Path serviceDirectory = path.resolve(SERVICE_PATH);
                    if (!Files.isDirectory(serviceDirectory)) {
                        continue;
                    }
                    resource = serviceDirectory.toUri().toURL();
                } else if (Files.isRegularFile(path) && entry.endsWith(".jar")) {
                    resource = new URL("jar:" + path.toUri().toURL() + "!/" + SERVICE_PATH);
                } else {
                    continue;
                }
                if (discoveredResources.add(resource.toExternalForm())) {
                    collectReferences(resource, classLoader, references);
                }
            } catch (InvalidPathException | IOException ignored) {
                // The classloader remains the source of truth for malformed or inaccessible entries.
            }
        }
    }

    private static void collectReferences(URL resource, ClassLoader classLoader, List<BeanDefinitionReference<?>> references) throws IOException {
        if ("file".equals(resource.getProtocol())) {
            collectFileReferences(resource, classLoader, references);
        } else if ("jar".equals(resource.getProtocol())) {
            collectJarReferences(resource, classLoader, references);
        }
    }

    private static void collectFileReferences(URL resource, ClassLoader classLoader, List<BeanDefinitionReference<?>> references) throws IOException {
        try {
            Path directory = Path.of(resource.toURI());
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (Stream<Path> children = Files.list(directory)) {
                children
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(className -> addReference(className, classLoader, references, true));
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bean definition service URL: " + resource, e);
        }
    }

    private static void collectJarReferences(URL resource, ClassLoader classLoader, List<BeanDefinitionReference<?>> references) throws IOException {
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
                        addReference(className, classLoader, references, true);
                    }
                }
            }
        }
    }

    private static void addReference(String className, ClassLoader classLoader, List<BeanDefinitionReference<?>> references, boolean requirePresent) {
        Object instance = instantiateReference(className, classLoader);
        if (instance instanceof BeanDefinitionReference<?> reference) {
            try {
                if (!requirePresent || reference.isPresent()) {
                    references.add(reference);
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
