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
package io.micronaut.pyronaut.directsource;

/**
 * Shared direct-source declaration state.
 */
public final class DirectSourceDeclarationState {
    /**
     * System property set once declarations have been resolved.
     */
    public static final String RESOLVED_PROPERTY = "pyronaut.direct.source.declarations.resolved";

    private DirectSourceDeclarationState() {
    }

    /**
     * @return whether declarations have already been resolved for this invocation
     */
    public static boolean isResolved() {
        return Boolean.getBoolean(RESOLVED_PROPERTY);
    }
}
