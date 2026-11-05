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

import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextClassLoaderBeanIntrospectionsProviderTest {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanIntrospectionReference.class.getName();

    @Test
    void discoversInMemoryIntrospectionReferences() throws Exception {
        URL memoryResource = new URL(
            null,
            "mem:/CLASS_OUTPUT/" + SERVICE_PATH + "/" + TestIntrospectionReference.class.getName(),
            new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    throw new IOException("mem resources are not opened");
                }
            }
        );
        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (SERVICE_PATH.equals(name)) {
                    return Collections.enumeration(List.of(memoryResource));
                }
                return super.getResources(name);
            }
        };

        List<BeanIntrospectionReference<Object>> references = new ContextClassLoaderBeanIntrospectionsProvider().provide(classLoader);

        assertTrue(references.stream().anyMatch(reference -> TestBean.class.getName().equals(reference.getName())));
    }

    @Test
    void discoversReferencesFromContextWhenRuntimeLoaderDiffers() throws Exception {
        URL memoryResource = new URL(
            null,
            "mem:/CLASS_OUTPUT/" + SERVICE_PATH + "/" + TestIntrospectionReference.class.getName(),
            new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    throw new IOException("mem resources are not opened");
                }
            }
        );
        ClassLoader contextClassLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (SERVICE_PATH.equals(name)) {
                    return Collections.enumeration(List.of(memoryResource));
                }
                return super.getResources(name);
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(contextClassLoader);
        try {
            List<BeanIntrospectionReference<Object>> references =
                new ContextClassLoaderBeanIntrospectionsProvider().provide(new ClassLoader(getClass().getClassLoader()) { });
            assertTrue(references.stream().anyMatch(reference -> TestBean.class.getName().equals(reference.getName())));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void keepsNoClassLoaderItDiscoveredFrom() throws Exception {
        // A reference's class holds the loader that defined it. What development mode retires has to
        // be collected, and DefaultBeanIntrospector, which keeps what this returns, forgets it then.
        ContextClassLoaderBeanIntrospectionsProvider provider = new ContextClassLoaderBeanIntrospectionsProvider();

        WeakReference<ClassLoader> generation = discoverFromGeneration(provider);

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (generation.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertNull(generation.get(), "the provider keeps the class loader it discovered from");
        // the provider is still in use, as it is for the whole life of a launcher
        assertFalse(provider.provide(getClass().getClassLoader()).stream().anyMatch(r -> TestBean.class.getName().equals(r.getName())));
    }

    /**
     * In a frame of its own, so that no local keeps the loader.
     */
    private WeakReference<ClassLoader> discoverFromGeneration(ContextClassLoaderBeanIntrospectionsProvider provider) throws Exception {
        URL memoryResource = memoryResource();
        ClassLoader generation = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (SERVICE_PATH.equals(name)) {
                    return Collections.enumeration(List.of(memoryResource));
                }
                return super.getResources(name);
            }

            // defines the reference itself, as the loader of a generation does the references of the application
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TestIntrospectionReference.class.getName()) && !name.equals(TestBean.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) {
                        return loaded;
                    }
                    try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] code = bytes.readAllBytes();
                        return defineClass(name, code, 0, code.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
            }
        };
        BeanIntrospectionReference<Object> reference = provider.provide(generation).stream()
            .filter(r -> TestBean.class.getName().equals(r.getName()))
            .findFirst()
            .orElseThrow();
        assertSame(generation, reference.getClass().getClassLoader(), "the reference is not defined by the loader");
        return new WeakReference<>(generation);
    }

    @Test
    void rediscoversWhenTheContextClassLoaderChanges() throws Exception {
        // The context class loader is part of what is walked, so a different one is a different answer.
        ClassLoader runtime = new ClassLoader(getClass().getClassLoader()) { };
        ContextClassLoaderBeanIntrospectionsProvider provider = new ContextClassLoaderBeanIntrospectionsProvider();
        URL memoryResource = memoryResource();
        ClassLoader withExtra = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (SERVICE_PATH.equals(name)) {
                    return Collections.enumeration(List.of(memoryResource));
                }
                return super.getResources(name);
            }
        };

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(getClass().getClassLoader());
            List<BeanIntrospectionReference<Object>> without = provider.provide(runtime);
            thread.setContextClassLoader(withExtra);
            List<BeanIntrospectionReference<Object>> with = provider.provide(runtime);

            assertFalse(without.stream().anyMatch(r -> TestBean.class.getName().equals(r.getName())));
            assertTrue(with.stream().anyMatch(r -> TestBean.class.getName().equals(r.getName())),
                "the references of the context class loader are missing");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL memoryResource() throws IOException {
        return new URL(
            null,
            "mem:/CLASS_OUTPUT/" + SERVICE_PATH + "/" + TestIntrospectionReference.class.getName(),
            new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    throw new IOException("mem resources are not opened");
                }
            }
        );
    }

    public static final class TestIntrospectionReference implements BeanIntrospectionReference<Object> {
        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public Class<Object> getBeanType() {
            return Object.class;
        }

        @Override
        public BeanIntrospection<Object> load() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return TestBean.class.getName();
        }
    }

    private static final class TestBean {
    }
}
