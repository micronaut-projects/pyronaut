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
package io.micronaut.pyronaut.dev;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.core.annotation.Internal;

import java.util.Optional;

/**
 * Installs the in-memory bean definitions provider while direct JUnit tests create their context.
 */
@Internal
public final class DirectSourceApplicationContextConfigurer implements ApplicationContextConfigurer {
    private static volatile BeanDefinitionsProvider provider;

    /**
     * Creates the configurer.
     */
    public DirectSourceApplicationContextConfigurer() {
    }

    static void set(BeanDefinitionsProvider provider) {
        DirectSourceApplicationContextConfigurer.provider = provider;
    }

    static void clear() {
        provider = null;
    }

    @Override
    public void configure(ApplicationContextBuilder builder) {
        Optional.ofNullable(provider).ifPresent(builder::beanDefinitionsProvider);
    }
}
