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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.inject.BeanDefinitionReference;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service names discovered by the native launcher feature, independent of runtime resource scans.
 *
 * <p>Public only so the hosted feature in {@code pyronaut-dev} can capture the names;
 * this is an internal cross-module bridge, not an application extension point.</p>
 */
@Internal
public final class NativeLauncherServices {
    private static Map<String, Set<String>> services = Map.of();

    private NativeLauncherServices() {
    }

    /**
     * Captures the launcher's bean metadata while its image is being built. Only names are kept:
     * definitions must be instantiated afresh for each application context, and application-only
     * services must still be discovered from the runtime classloader.
     *
     * @param discovered The service names from the native service loader feature
     */
    public static void initialize(Map<String, Set<String>> discovered) {
        Map<String, Set<String>> captured = new HashMap<>();
        for (Class<?> type : List.of(BeanDefinitionReference.class, BeanIntrospectionReference.class)) {
            Set<String> names = discovered.get(type.getName());
            if (names != null) {
                captured.put(type.getName(), Collections.unmodifiableSet(new LinkedHashSet<>(names)));
            }
        }
        services = Map.copyOf(captured);
    }

    static Set<String> find(ClassLoader classLoader, String serviceName) throws IOException {
        Set<String> names = services.get(serviceName);
        return names != null ? names : MicronautMetaServiceLoaderUtils.findMicronautMetaServiceEntries(classLoader, serviceName);
    }
}
