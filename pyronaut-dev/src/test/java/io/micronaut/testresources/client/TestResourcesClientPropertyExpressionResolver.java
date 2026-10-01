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
package io.micronaut.testresources.client;

import io.micronaut.context.env.PropertyExpressionResolver;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.value.PropertyResolver;

import java.util.Map;
import java.util.Optional;

public final class TestResourcesClientPropertyExpressionResolver implements PropertyExpressionResolver {
    private static final String PREFIX = "auto.test.resources.";
    // Like Mailpit's SMTP host and port, each of these requires the other.
    private static final Map<String, String> REQUIRED = Map.of(
        "mail.host", "mail.port",
        "mail.port", "mail.host"
    );

    @Override
    public <T> Optional<T> resolve(PropertyResolver propertyResolver,
                                   ConversionService conversionService,
                                   String expression,
                                   Class<T> requiredType) {
        if (!expression.startsWith(PREFIX) || requiredType != String.class) {
            return Optional.empty();
        }
        String property = expression.substring(PREFIX.length());
        String required = REQUIRED.get(property);
        if (required != null) {
            String requiredValue = propertyResolver.getProperty(required, String.class).orElse("absent");
            return Optional.of(requiredType.cast("resolved:" + property + "[" + required + "=" + requiredValue + "]"));
        }
        return Optional.of(requiredType.cast("resolved:" + property));
    }
}
