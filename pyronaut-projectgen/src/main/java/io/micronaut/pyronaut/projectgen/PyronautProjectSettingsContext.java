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

import java.util.List;

/**
 * Thread-local holder used by CLI generation to pass Pyronaut-specific settings
 * through ProjectGen's generic {@code Options} model.
 */
@Internal
public final class PyronautProjectSettingsContext {
    private static final ThreadLocal<PyronautProjectSettings> CURRENT = new ThreadLocal<>();

    private PyronautProjectSettingsContext() {
    }

    public static PyronautProjectSettings current() {
        PyronautProjectSettings settings = CURRENT.get();
        if (settings != null) {
            return settings;
        }
        return new PyronautProjectSettings(PyronautProjectDefaults.micronautVersion(), List.of());
    }

    public static <T> T withSettings(PyronautProjectSettings settings, CheckedSupplier<T> supplier) throws Exception {
        PyronautProjectSettings previous = CURRENT.get();
        CURRENT.set(settings);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
