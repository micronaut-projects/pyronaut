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
package io.micronaut.pyronaut.run;

import io.micronaut.core.annotation.Internal;

/**
 * Runtime extension point for launcher features that are not part of the
 * Java-only production runner.
 */
@Internal
public interface PyronautRunConfigurer {
    /** A no-op Java runtime configuration. */
    PyronautRunConfigurer NO_OP = new PyronautRunConfigurer() { };

    /** Initialize launcher-wide state before command execution. */
    default void initializeLauncher() {
    }

    /**
     * Whether the launcher should apply its default application logging.
     *
     * @param applicationClassLoader the application class loader
     * @return whether default logging should be applied
     */
    default boolean shouldInitializeApplicationDefaults(ClassLoader applicationClassLoader) {
        return true;
    }

    /**
     * Initialize application logging defaults.
     *
     * @param verboseLogger optional logger name, or an empty string for root trace logging
     */
    default void initializeApplicationDefaults(String verboseLogger) {
    }
}
