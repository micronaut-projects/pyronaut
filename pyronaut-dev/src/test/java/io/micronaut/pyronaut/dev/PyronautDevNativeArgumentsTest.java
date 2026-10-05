/*
 * Copyright 2026 original authors
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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PyronautDevNativeArgumentsTest {
    @Test
    void reusableImagePreservesNettyAndMicrometerRuntimeClasses() throws IOException {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        while (repositoryRoot != null && !Files.isRegularFile(repositoryRoot.resolve("settings.gradle.kts"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        assertTrue(repositoryRoot != null, "Could not locate the repository root");

        String buildScript = Files.readString(repositoryRoot.resolve("pyronaut-dev/build.gradle.kts"));
        int argsStart = buildScript.indexOf("val nativeImageRuntimeArgs = listOf(");
        int argsEnd = buildScript.indexOf("val nativeImageBuildReportArgs", argsStart);
        assertTrue(argsStart >= 0 && argsEnd > argsStart, "Could not locate nativeImageRuntimeArgs");
        String nativeImageArgs = buildScript.substring(argsStart, argsEnd);
        assertTrue(
            nativeImageArgs.contains("-H:Preserve=package=io.netty.handler.logging.*"),
            "The reusable image must preserve Netty logging classes"
        );
        assertTrue(
            nativeImageArgs.contains("-H:Preserve=package=io.netty.util.internal\""),
            "The reusable image must preserve direct Netty internal classes"
        );
        for (String corePackage : new String[] {"io", "value", "bind", "execution", "convert"}) {
            assertTrue(
                nativeImageArgs.contains("-H:Preserve=package=io.micronaut.core." + corePackage + ".*\""),
                "Python host access needs reflection metadata for io.micronaut.core." + corePackage
            );
        }
        assertAll(
            () -> assertTrue(
                nativeImageArgs.contains("-H:Preserve=package=io.netty.handler.codec\""),
                "The reusable image must preserve direct Netty codec classes"
            ),
            () -> assertTrue(
                nativeImageArgs.contains("-H:Preserve=package=io.micrometer.core.instrument\""),
                "The reusable image must preserve direct Micrometer instrument classes"
            )
        );
    }
}
