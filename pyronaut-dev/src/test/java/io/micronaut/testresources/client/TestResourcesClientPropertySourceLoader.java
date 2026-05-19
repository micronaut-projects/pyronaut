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

import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TestResourcesClientPropertySourceLoader implements PropertySourceLoader {
    @Override
    public Optional<PropertySource> load(String resourceName, ResourceLoader resourceLoader) {
        return Optional.of(PropertySource.of("test-resources-client", Map.of(
            "datasources.default.url", "value-from-delegate"
        )));
    }

    @Override
    public Optional<PropertySource> loadEnv(String resourceName,
                                            ResourceLoader resourceLoader,
                                            ActiveEnvironment activeEnvironment) {
        return load(resourceName, resourceLoader);
    }

    @Override
    public Map<String, Object> read(String name, InputStream input) {
        return Map.of();
    }

    @Override
    public Set<String> getExtensions() {
        return Set.of();
    }
}
