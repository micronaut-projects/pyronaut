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

import java.util.Map;

/** Per-invocation direct-source declaration state. */
final class DirectSourceDeclarationState {
    private static final ThreadLocal<Map<String, Object>> RUNTIME_PROPERTIES = ThreadLocal.withInitial(Map::of);

    private DirectSourceDeclarationState() {
    }

    /**
     * Sets properties for the current direct-source invocation.
     * @param properties runtime properties
     */
    static void setRuntimeProperties(Map<String, String> properties) {
        RUNTIME_PROPERTIES.set(Map.copyOf(properties));
    }

    /** @return runtime properties for the current invocation */
    static Map<String, Object> runtimeProperties() {
        return RUNTIME_PROPERTIES.get();
    }

    /** Clears state associated with the current thread. */
    static void clear() {
        RUNTIME_PROPERTIES.remove();
    }
}
