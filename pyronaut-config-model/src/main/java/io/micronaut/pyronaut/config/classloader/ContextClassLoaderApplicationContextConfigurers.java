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

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.core.order.OrderUtil;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Applies application context configurers discovered from a runtime application classloader.
 *
 * <p>Native launchers use Micronaut static service metadata for services bundled into the image.
 * Application dependencies are loaded later by the runtime classloader, so Pyronaut has to scan
 * their {@code META-INF/micronaut} entries directly for service types that were intentionally kept
 * dynamic in the launcher image.</p>
 */
public final class ContextClassLoaderApplicationContextConfigurers {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + ApplicationContextConfigurer.class.getName();
    private static final String STANDARD_SERVICE_PATH = "META-INF/services/" + ApplicationContextConfigurer.class.getName();

    private ContextClassLoaderApplicationContextConfigurers() {
    }

    /**
     * Applies all runtime-discovered application context configurers to the builder.
     *
     * @param builder The application context builder
     * @param classLoader The runtime application classloader
     */
    public static void configure(ApplicationContextBuilder builder, ClassLoader classLoader) {
        for (ApplicationContextConfigurer configurer : discover(classLoader)) {
            configurer.configure(builder);
        }
    }

    static List<ApplicationContextConfigurer> discover(ClassLoader classLoader) {
        Set<String> classNames = new LinkedHashSet<>();
        collectMicronautServiceEntries(classLoader, classNames);
        collectStandardServiceEntries(classLoader, classNames);
        List<ApplicationContextConfigurer> configurers = new ArrayList<>(classNames.size());
        for (String className : classNames) {
            addConfigurer(className, classLoader, configurers);
        }
        OrderUtil.sortOrdered(configurers);
        return List.copyOf(configurers);
    }

    private static void collectMicronautServiceEntries(ClassLoader classLoader, Set<String> classNames) {
        try {
            Enumeration<URL> resources = classLoader.getResources(SERVICE_PATH);
            while (resources.hasMoreElements()) {
                collectMicronautServiceEntries(resources.nextElement(), classNames);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover application context configurers from runtime classloader", e);
        }
    }

    private static void collectMicronautServiceEntries(URL resource, Set<String> classNames) throws IOException {
        if ("file".equals(resource.getProtocol())) {
            collectFileMicronautServiceEntries(resource, classNames);
        } else if ("jar".equals(resource.getProtocol())) {
            collectJarMicronautServiceEntries(resource, classNames);
        }
    }

    private static void collectFileMicronautServiceEntries(URL resource, Set<String> classNames) throws IOException {
        try {
            Path directory = Path.of(resource.toURI());
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (Stream<Path> children = Files.list(directory)) {
                children
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(classNames::add);
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid application context configurer service URL: " + resource, e);
        }
    }

    private static void collectJarMicronautServiceEntries(URL resource, Set<String> classNames) throws IOException {
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
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String className = name.substring(prefix.length());
                if (!className.isBlank() && !className.contains("/")) {
                    classNames.add(className);
                }
            }
        }
    }

    private static void collectStandardServiceEntries(ClassLoader classLoader, Set<String> classNames) {
        try {
            Enumeration<URL> resources = classLoader.getResources(STANDARD_SERVICE_PATH);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                try (var input = resource.openStream();
                     var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        String className = (comment >= 0 ? line.substring(0, comment) : line).trim();
                        if (!className.isBlank()) {
                            classNames.add(className);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to discover application context configurers from runtime classloader", e);
        }
    }

    private static void addConfigurer(String className, ClassLoader classLoader, List<ApplicationContextConfigurer> configurers) {
        try {
            Class<?> loadedClass = Class.forName(className, false, classLoader);
            Object instance = loadedClass.getDeclaredConstructor().newInstance();
            if (instance instanceof ApplicationContextConfigurer configurer) {
                configurers.add(configurer);
            }
        } catch (NoClassDefFoundError | ClassNotFoundException ignored) {
            // Ignore optional configurers whose dependencies are unavailable in this runtime.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate application context configurer " + className, e);
        }
    }
}
