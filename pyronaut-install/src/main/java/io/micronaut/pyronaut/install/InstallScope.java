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
import java.util.List;

/**
 * Supported dependency installation scopes.
 */
public enum InstallScope {
    BUILD("build", "resolved-build-dependencies"),
    RUNTIME("runtime", "resolved-runtime-dependencies"),
    DEVELOPMENT_RUNTIME("development-runtime", "resolved-development-runtime-dependencies"),
    TEST("test", "resolved-test-dependencies"),
    TEST_RESOURCES_SERVER(
        "test-resources-server",
        "resolved-test-resources-server-dependencies",
        List.of(
            "io.micronaut:micronaut-http-server",
            "io.micronaut.testresources:micronaut-test-resources-core",
            "io.micronaut.testresources:micronaut-test-resources-control-panel",
            "io.micronaut.testresources:micronaut-test-resources-server",
            "io.micronaut.serde:micronaut-serde-jackson",
            "ch.qos.logback:logback-classic",
            "org.slf4j:jul-to-slf4j",
            "io.micronaut:micronaut-http-server-netty"
        )
    );

    private final String cliValue;
    private final String manifestFile;
    private final List<String> defaultDependencies;

    InstallScope(String cliValue, String manifestFile) {
        this(cliValue, manifestFile, List.of());
    }

    InstallScope(String cliValue, String manifestFile, List<String> defaultDependencies) {
        this.cliValue = cliValue;
        this.manifestFile = manifestFile;
        this.defaultDependencies = List.copyOf(defaultDependencies);
    }

    public String cliValue() {
        return cliValue;
    }

    public String manifestFile() {
        return manifestFile;
    }

    /**
     * Dependencies supplied by Pyronaut whenever this scope is resolved.
     *
     * @return immutable default Maven coordinates
     */
    public List<String> defaultDependencies() {
        return defaultDependencies;
    }

    static InstallScope fromCliValue(String value) {
        return Arrays.stream(values())
            .filter(scope -> scope.cliValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported scope '" + value + "'. Expected one of: build,runtime,development-runtime,test,test-resources-server"));
    }
}
