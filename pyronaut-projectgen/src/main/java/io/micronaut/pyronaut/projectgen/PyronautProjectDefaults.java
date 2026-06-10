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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Internal
final class PyronautProjectDefaults {
    private static final String DEFAULTS_RESOURCE = "/io/micronaut/pyronaut/projectgen/defaults.properties";
    private static final String FALLBACK_MICRONAUT_VERSION = "5.0.0-SNAPSHOT";

    private PyronautProjectDefaults() {
    }

    static String micronautVersion() {
        Properties properties = new Properties();
        try (InputStream input = PyronautProjectDefaults.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            return FALLBACK_MICRONAUT_VERSION;
        }
        return properties.getProperty("micronaut.version", FALLBACK_MICRONAUT_VERSION);
    }
}
