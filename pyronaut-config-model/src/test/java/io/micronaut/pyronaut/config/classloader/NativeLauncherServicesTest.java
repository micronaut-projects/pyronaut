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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.event.ApplicationEventPublisherFactory;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinitionReference;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLauncherServicesTest {
    @Test
    void keepsLauncherServicesWhenRuntimeMetadataIsUnavailable() throws Exception {
        String beans = BeanDefinitionReference.class.getName();
        String introspections = BeanIntrospectionReference.class.getName();
        Set<String> names = new HashSet<>(Set.of(ApplicationEventPublisherFactory.class.getName()));
        NativeLauncherServices.initialize(Map.of(
            beans, names,
            introspections, Set.of(ContextClassLoaderBeanIntrospectionsProviderTest.TestIntrospectionReference.class.getName())
        ));
        names.clear(); // The feature may mutate its own sets; the image snapshot must be independent.
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader hiddenResources = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        };
        try {
            assertEquals(Set.of(ApplicationEventPublisherFactory.class.getName()), NativeLauncherServices.find(hiddenResources, beans));
            Thread.currentThread().setContextClassLoader(hiddenResources);
            var provider = new ContextClassLoaderBeanDefinitionsProvider();
            var first = provider.provide(hiddenResources).stream()
                .filter(reference -> reference instanceof ApplicationEventPublisherFactory).findFirst().orElseThrow();
            var second = provider.provide(hiddenResources).stream()
                .filter(reference -> reference instanceof ApplicationEventPublisherFactory).findFirst().orElseThrow();
            assertNotSame(first, second);
            try (ApplicationContext context = ApplicationContext.builder()
                .classLoader(hiddenResources)
                .beanDefinitionsProvider(provider)
                .start()) {
                assertNotNull(context.getBean(Argument.of(ApplicationEventPublisher.class, StartupEvent.class)));
            }
            assertTrue(new ContextClassLoaderBeanIntrospectionsProvider().provide(hiddenResources).stream()
                .anyMatch(reference -> reference instanceof ContextClassLoaderBeanIntrospectionsProviderTest.TestIntrospectionReference));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            NativeLauncherServices.initialize(Map.of());
        }
    }
}
