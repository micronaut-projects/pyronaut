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
package io.micronaut.pyronaut.runtime;

import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinitionReference;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Shared Pyronaut bean definition reference loading for processed Python classes.
 */
@Internal
public final class PyronautBeanDefinitionReferences {
    public static final String BEAN_DEFINITION_REFERENCES_PATH = "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference";

    private PyronautBeanDefinitionReferences() {
    }

    /**
     * Create a provider that merges processed classes and normal Micronaut definitions.
     *
     * @param classesDirectory The processed classes directory.
     * @return The bean definitions provider.
     */
    public static BeanDefinitionsProvider processedClassesProvider(Path classesDirectory) {
        return new ProcessedClassesBeanDefinitionsProvider(classesDirectory);
    }

    /**
     * Create a provider that merges URL classpath entries and normal Micronaut definitions.
     *
     * @return The bean definitions provider.
     */
    public static BeanDefinitionsProvider classpathProvider() {
        return new ClasspathBeanDefinitionsProvider();
    }

    /**
     * Load bean definition references generated into a processed classes directory.
     *
     * @param classesDirectory The processed classes directory.
     * @param classLoader The class loader.
     * @return The bean definition references.
     */
    public static List<BeanDefinitionReference<?>> loadProcessed(Path classesDirectory, ClassLoader classLoader) {
        Path referencesDirectory = classesDirectory.resolve(BEAN_DEFINITION_REFERENCES_PATH);
        if (!Files.isDirectory(referencesDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(referencesDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.isBlank())
                .sorted()
                .<BeanDefinitionReference<?>>map(name -> loadBeanDefinitionReference(name, classLoader))
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read processed bean definition references from " + referencesDirectory,
                e
            );
        }
    }

    /**
     * Load bean definition references by scanning URL classpath entries.
     *
     * @param classLoader The class loader.
     * @return The bean definition references.
     */
    public static List<BeanDefinitionReference<?>> loadClasspath(ClassLoader classLoader) {
        LinkedHashSet<String> referenceNames = new LinkedHashSet<>();
        collectClasspathBeanDefinitionReferenceNames(classLoader, referenceNames);
        if (referenceNames.isEmpty()) {
            collectResourceBeanDefinitionReferenceNames(classLoader, referenceNames);
        }
        return referenceNames.stream()
            .<BeanDefinitionReference<?>>map(className -> loadBeanDefinitionReferenceIfPresent(className, classLoader))
            .filter(Objects::nonNull)
            .toList();
    }

    private static void collectClasspathBeanDefinitionReferenceNames(ClassLoader classLoader,
                                                                    LinkedHashSet<String> referenceNames) {
        ClassLoader current = classLoader;
        while (current != null) {
            if (current instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    collectUrlBeanDefinitionReferenceNames(url, referenceNames);
                }
            }
            current = current.getParent();
        }
    }

    private static void collectUrlBeanDefinitionReferenceNames(URL url, LinkedHashSet<String> referenceNames) {
        if (!"file".equals(url.getProtocol())) {
            return;
        }
        try {
            Path path = Path.of(url.toURI());
            if (Files.isDirectory(path)) {
                collectDirectoryBeanDefinitionReferenceNames(
                    path.resolve(BEAN_DEFINITION_REFERENCES_PATH),
                    referenceNames
                );
            } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                collectJarBeanDefinitionReferenceNames(
                    path,
                    BEAN_DEFINITION_REFERENCES_PATH + "/",
                    referenceNames
                );
            }
        } catch (Exception ignored) {
            // Ignore malformed or unreadable classpath entries; missing definitions fail during bean resolution.
        }
    }

    private static void collectResourceBeanDefinitionReferenceNames(ClassLoader classLoader,
                                                                   LinkedHashSet<String> referenceNames) {
        try {
            Enumeration<URL> resources = classLoader.getResources(BEAN_DEFINITION_REFERENCES_PATH);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    try {
                        collectDirectoryBeanDefinitionReferenceNames(Path.of(resource.toURI()), referenceNames);
                    } catch (Exception ignored) {
                        // Ignore malformed resource URLs.
                    }
                }
            }
        } catch (IOException ignored) {
            // Ignore resource lookup failures; missing definitions fail during bean resolution.
        }
    }

    private static void collectDirectoryBeanDefinitionReferenceNames(Path referencesDirectory,
                                                                    LinkedHashSet<String> referenceNames)
        throws IOException {
        if (!Files.isDirectory(referencesDirectory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(referencesDirectory)) {
            stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.isBlank())
                .sorted()
                .forEach(referenceNames::add);
        }
    }

    private static void collectJarBeanDefinitionReferenceNames(Path jarPath,
                                                              String referencePrefix,
                                                              LinkedHashSet<String> referenceNames)
        throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(referencePrefix)) {
                    continue;
                }
                String referenceName = entry.getName().substring(referencePrefix.length());
                if (!referenceName.isBlank() && !referenceName.contains("/")) {
                    referenceNames.add(referenceName);
                }
            }
        }
    }

    private static BeanDefinitionReference<?> loadBeanDefinitionReference(String className, ClassLoader classLoader) {
        try {
            Class<?> loadedClass = Class.forName(className, false, classLoader);
            if (!BeanDefinitionReference.class.isAssignableFrom(loadedClass)) {
                throw new IllegalStateException("Bean definition reference is not a BeanDefinitionReference: " + className);
            }
            Constructor<?> constructor = loadedClass.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return (BeanDefinitionReference<?>) constructor.newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Failed to load bean definition reference: " + className, e);
        }
    }

    private static BeanDefinitionReference<?> loadBeanDefinitionReferenceIfPresent(String className,
                                                                                  ClassLoader classLoader) {
        try {
            BeanDefinitionReference<?> reference = loadBeanDefinitionReference(className, classLoader);
            return reference.isPresent() ? reference : null;
        } catch (IllegalStateException | LinkageError e) {
            return null;
        }
    }

    private static void addProcessedReferences(Map<String, BeanDefinitionReference<?>> references,
                                               Path classesDirectory,
                                               ClassLoader classLoader) {
        for (BeanDefinitionReference<?> reference : loadProcessed(classesDirectory, classLoader)) {
            references.put(reference.getClass().getName(), reference);
        }
    }

    private static void addClasspathReferences(Map<String, BeanDefinitionReference<?>> references,
                                               ClassLoader classLoader) {
        for (BeanDefinitionReference<?> reference : loadClasspath(classLoader)) {
            references.putIfAbsent(reference.getClass().getName(), reference);
        }
    }

    private static void addDefaultReferences(Map<String, BeanDefinitionReference<?>> references,
                                             ClassLoader classLoader,
                                             BeanDefinitionsProvider delegate) {
        for (BeanDefinitionReference<?> reference : delegate.provide(classLoader)) {
            references.putIfAbsent(reference.getClass().getName(), reference);
        }
    }

    private static final class ProcessedClassesBeanDefinitionsProvider implements BeanDefinitionsProvider {
        private final BeanDefinitionsProvider delegate = new DefaultBeanDefinitionsProvider();
        private final Path classesDirectory;

        private ProcessedClassesBeanDefinitionsProvider(Path classesDirectory) {
            this.classesDirectory = classesDirectory;
        }

        @Override
        public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            addProcessedReferences(references, classesDirectory, classLoader);
            addDefaultReferences(references, classLoader, delegate);
            return new ArrayList<>(references.values());
        }
    }

    private static final class ClasspathBeanDefinitionsProvider implements BeanDefinitionsProvider {
        private final BeanDefinitionsProvider delegate = new DefaultBeanDefinitionsProvider();

        @Override
        public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            addClasspathReferences(references, classLoader);
            addDefaultReferences(references, classLoader, delegate);
            return new ArrayList<>(references.values());
        }
    }
}
