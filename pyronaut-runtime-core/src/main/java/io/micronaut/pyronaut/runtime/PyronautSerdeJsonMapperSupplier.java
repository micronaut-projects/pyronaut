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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.Ordered;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.JsonMapperSupplier;
import io.micronaut.serde.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serde-backed default mapper supplier for Pyronaut launchers.
 */
@Internal
public final class PyronautSerdeJsonMapperSupplier implements JsonMapperSupplier, Ordered {
    private static final Set<String> SERDE_INCLUDE_PACKAGES = Set.of(
        "io.micronaut.serde",
        "io.micronaut.aop",
        "io.micronaut.runtime.context.env"
    );
    private static final Set<String> SERDE_DEFAULT_REFERENCE_NAMES = Set.of(
        "io.micronaut.serde.config.$DefaultDeserializationConfiguration$Definition",
        "io.micronaut.serde.config.$DefaultSerdeConfiguration$Definition",
        "io.micronaut.serde.config.$DefaultSerializationConfiguration$Definition",
        "io.micronaut.serde.jackson.$JacksonJsonMapper$Definition",
        "io.micronaut.serde.jackson.$SerdeJacksonConfiguration$Definition",
        "io.micronaut.serde.support.$DefaultSerdeIntrospections$Definition",
        "io.micronaut.serde.support.$DefaultSerdeRegistry$Definition",
        "io.micronaut.serde.support.config.$SerdeJsonConfiguration$Definition"
    );
    private static final Object CONTEXT_LOCK = new Object();
    private static final Object MAPPER_LOCK = new Object();
    private static volatile ApplicationContext beanContext;
    private static volatile JsonMapper defaultJsonMapper;

    @Override
    public JsonMapper get() {
        JsonMapper jsonMapper = defaultJsonMapper;
        if (jsonMapper == null) {
            synchronized (MAPPER_LOCK) {
                jsonMapper = defaultJsonMapper;
                if (jsonMapper == null) {
                    jsonMapper = resolveBeanContext().getBean(ObjectMapper.class);
                    defaultJsonMapper = jsonMapper;
                }
            }
        }
        return jsonMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static ApplicationContext resolveBeanContext() {
        ApplicationContext context = beanContext;
        if (context == null) {
            synchronized (CONTEXT_LOCK) {
                context = beanContext;
                if (context == null) {
                    context = createSerdeBeanContext(resolveApplicationClassLoader()).start();
                    beanContext = context;
                }
            }
        }
        return context;
    }

    private static ApplicationContext createSerdeBeanContext(ClassLoader classLoader) {
        ApplicationContextBuilder builder = ApplicationContext.builder()
            .beansPredicate(qualifiedBeanType -> isIncludedPackage(qualifiedBeanType.getBeanType().getName()))
            .beanConfigurationsPredicate(beanConfiguration -> isIncludedPackage(beanConfiguration.getPackage().getName()))
            .classLoader(classLoader)
            .beanDefinitionsProvider(new SerdeDefaultBeanDefinitionsProvider())
            .eventsEnabled(false)
            .eagerBeansEnabled(false)
            .deducePackage(false)
            .bootstrapEnvironment(false)
            .deduceCloudEnvironment(false)
            .enableDefaultPropertySources(false);
        return builder
            .propertySources()
            .build();
    }

    private static boolean isIncludedPackage(String className) {
        for (String packageName : SERDE_INCLUDE_PACKAGES) {
            if (className.startsWith(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static ClassLoader resolveApplicationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : PyronautSerdeJsonMapperSupplier.class.getClassLoader();
    }

    private static final class SerdeDefaultBeanDefinitionsProvider implements BeanDefinitionsProvider {
        private final BeanDefinitionsProvider delegate = new DefaultBeanDefinitionsProvider();

        @Override
        public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            for (BeanDefinitionReference<?> reference : PyronautBeanDefinitionReferences.loadClasspath(classLoader)) {
                String referenceName = reference.getClass().getName();
                if (SERDE_DEFAULT_REFERENCE_NAMES.contains(referenceName)) {
                    references.put(referenceName, reference);
                }
            }
            for (BeanDefinitionReference<?> reference : delegate.provide(classLoader)) {
                String referenceName = reference.getClass().getName();
                if (!referenceName.startsWith("io.micronaut.serde.")) {
                    references.putIfAbsent(referenceName, reference);
                }
            }
            return new ArrayList<>(references.values());
        }
    }
}
