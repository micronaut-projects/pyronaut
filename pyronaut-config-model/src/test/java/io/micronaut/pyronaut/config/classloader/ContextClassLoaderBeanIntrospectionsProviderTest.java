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
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void discoversOncePerClassLoaderRatherThanPerCall() throws Exception {
        // Discovery is a classpath walk. Repeating it per call cost a fifth of request time on an
        // application whose validated routes resolve an introspection per request.
        AtomicInteger resourceLookups = new AtomicInteger();
        ClassLoader counting = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (SERVICE_PATH.equals(name)) {
                    resourceLookups.incrementAndGet();
                }
                return super.getResources(name);
            }
        };
        ContextClassLoaderBeanIntrospectionsProvider provider = new ContextClassLoaderBeanIntrospectionsProvider();

        List<BeanIntrospectionReference<Object>> first = provider.provide(counting);
        int afterFirst = resourceLookups.get();
        for (int i = 0; i < 20; i++) {
            provider.provide(counting);
        }

        assertEquals(afterFirst, resourceLookups.get(), "discovery repeated after the first call");
        assertSame(first, provider.provide(counting), "the same result is not returned");
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
                "a cached result was returned for a different context class loader");
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
