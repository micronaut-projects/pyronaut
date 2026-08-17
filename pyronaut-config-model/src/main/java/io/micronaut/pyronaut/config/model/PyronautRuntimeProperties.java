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
package io.micronaut.pyronaut.config.model;

/**
 * System properties configured by the Pyronaut launchers.
 */
public final class PyronautRuntimeProperties {
    /** Controls Micronaut's optional GraalVM {@code ImageSingletons} lookups. */
    public static final String GRAALVM_IMAGESINGLETONS_ENABLED = "micronaut.graalvm.imagesingletons.enabled";

    private PyronautRuntimeProperties() {
    }

    /**
     * Disable optional GraalVM {@code ImageSingletons} lookups unless the user
     * has explicitly configured the property.
     */
    public static void disableGraalVmImageSingletons() {
        if (System.getProperty(GRAALVM_IMAGESINGLETONS_ENABLED) == null) {
            System.setProperty(GRAALVM_IMAGESINGLETONS_ENABLED, Boolean.FALSE.toString());
        }
    }
}
