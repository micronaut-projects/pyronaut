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
package io.micronaut.pyronaut.config;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.Internal;

import java.util.Map;

/**
 * Applies default configuration for Pyronaut application contexts.
 */
@Internal
public final class PyronautApplicationContextConfigurer implements ApplicationContextConfigurer {
    private static final String PROPERTY_SOURCE_NAME = "pyronaut-defaults";
    private static final int PROPERTY_SOURCE_ORDER = -500;
    private static final Map<String, Object> DEFAULT_PROPERTIES = Map.of(
        "micronaut.executors.io.type", "cached",
        "micronaut.executors.io.virtual", false,
        "micronaut.executors.blocking.type", "cached",
        "micronaut.executors.blocking.virtual", false
    );

    @Override
    public void configure(ApplicationContextBuilder builder) {
        builder.propertySources(PropertySource.of(
            PROPERTY_SOURCE_NAME,
            DEFAULT_PROPERTIES,
            PROPERTY_SOURCE_ORDER
        ));
    }
}
