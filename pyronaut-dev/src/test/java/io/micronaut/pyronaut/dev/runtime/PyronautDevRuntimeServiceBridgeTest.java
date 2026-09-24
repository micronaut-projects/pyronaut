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
package io.micronaut.pyronaut.dev.runtime;

import io.micronaut.context.env.PropertyExpressionResolver;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.core.convert.ConversionService;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautDevRuntimeServiceBridgeTest {

    @Test
    void testResourcesExpressionResolverIsServiceLoadedAndDelegates() {
        assertTrue(
            ServiceLoader.load(PropertyExpressionResolver.class).stream()
                .anyMatch(provider -> provider.type() == PyronautDevTestResourcesPropertyExpressionResolver.class)
        );

        PyronautDevTestResourcesPropertyExpressionResolver resolver = new PyronautDevTestResourcesPropertyExpressionResolver();
        assertTrue(resolver.resolve(null, ConversionService.SHARED, "datasources.default.url", String.class).isEmpty());
        assertEquals(
            "resolved:datasources.default.url",
            resolver.resolve(null, ConversionService.SHARED, "auto.test.resources.datasources.default.url", String.class).orElseThrow()
        );
    }

    @Test
    void testResourcesPropertySourceLoaderIsServiceLoadedAndDelegates() {
        assertTrue(
            ServiceLoader.load(PropertySourceLoader.class).stream()
                .anyMatch(provider -> provider.type() == PyronautDevTestResourcesPropertySourceLoader.class)
        );

        PyronautDevTestResourcesPropertySourceLoader loader = new PyronautDevTestResourcesPropertySourceLoader();
        assertEquals(
            "value-from-delegate",
            loader.load("application", null).orElseThrow().get("datasources.default.url")
        );
    }

    @Test
    void disabledTestResourcesDoNotLoadTheClient() {
        String previous = System.getProperty("micronaut.test.resources.enabled");
        System.setProperty("micronaut.test.resources.enabled", "false");
        try {
            // A stale ~/.micronaut settings file must not be read by the client.
            assertFalse(PyronautDevTestResourcesPropertySourceLoader.enabled());
            assertTrue(new PyronautDevTestResourcesPropertySourceLoader().load("application", null).isEmpty());
            assertTrue(new PyronautDevTestResourcesPropertyExpressionResolver()
                .resolve(null, ConversionService.SHARED, "auto.test.resources.datasources.default.url", String.class)
                .isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("micronaut.test.resources.enabled");
            } else {
                System.setProperty("micronaut.test.resources.enabled", previous);
            }
        }
    }
}
