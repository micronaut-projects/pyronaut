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
