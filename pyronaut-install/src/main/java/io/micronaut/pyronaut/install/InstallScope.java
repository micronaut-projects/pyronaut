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
package io.micronaut.pyronaut.install;

import java.util.Arrays;

/**
 * Supported dependency installation scopes.
 */
public enum InstallScope {
    BUILD("build", "resolved-build-dependencies"),
    RUNTIME("runtime", "resolved-runtime-dependencies"),
    DEVELOPMENT_RUNTIME("development-runtime", "resolved-development-runtime-dependencies"),
    TEST("test", "resolved-test-dependencies"),
    TEST_RESOURCES_SERVER("test-resources-server", "resolved-test-resources-server-dependencies");

    private final String cliValue;
    private final String manifestFile;

    InstallScope(String cliValue, String manifestFile) {
        this.cliValue = cliValue;
        this.manifestFile = manifestFile;
    }

    public String cliValue() {
        return cliValue;
    }

    public String manifestFile() {
        return manifestFile;
    }

    static InstallScope fromCliValue(String value) {
        return Arrays.stream(values())
            .filter(scope -> scope.cliValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported scope '" + value + "'. Expected one of: build,runtime,development-runtime,test,test-resources-server"));
    }
}
