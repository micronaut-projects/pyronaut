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
package io.micronaut.test.pytest.extension;

import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.inject.BeanDefinitionReference;

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
 * Combines native-image static bean definitions with definitions loaded from a runtime application classloader.
 */
final class ContextClassLoaderBeanDefinitionsProvider implements BeanDefinitionsProvider {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanDefinitionReference.class.getName();

    private final BeanDefinitionsProvider delegate = new DefaultBeanDefinitionsProvider();

    @Override
    public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
        Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
        for (BeanDefinitionReference<?> reference : delegate.provide(classLoader)) {
            references.put(reference.getName(), reference);
        }
        for (BeanDefinitionReference<?> reference : discoverRuntimeReferences(classLoader)) {
            references.put(reference.getName(), reference);
        }
        return List.copyOf(references.values());
    }

    private static List<BeanDefinitionReference<?>> discoverRuntimeReferences(ClassLoader classLoader) {
        List<BeanDefinitionReference<?>> references = new ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(SERVICE_PATH);
            while (resources.hasMoreElements()) {
                collectReferences(resources.nextElement(), classLoader, references);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover bean definitions from runtime classloader", e);
        }
        return references;
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
                    .forEach(className -> addReference(className, classLoader, references));
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bean definition service URL: " + resource, e);
        }
    }

    private static void collectJarReferences(URL resource, ClassLoader classLoader, List<BeanDefinitionReference<?>> references) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
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

    private static void addReference(String className, ClassLoader classLoader, List<BeanDefinitionReference<?>> references) {
        Object instance = InstantiationUtils.tryInstantiate(className, classLoader).orElse(null);
        if (instance instanceof BeanDefinitionReference<?> reference && reference.isPresent()) {
            references.add(reference);
        }
    }
}
