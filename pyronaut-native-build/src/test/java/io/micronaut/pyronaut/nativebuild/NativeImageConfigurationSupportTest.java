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
package io.micronaut.pyronaut.nativebuild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageConfigurationSupportTest {
    @Test
    void keepsMicronautNativeConfigurationWhenMetadataIsSelected(@TempDir Path directory) throws Exception {
        for (String artifact : List.of("micronaut-core", "micronaut-inject")) {
            Path frameworkJar = directory.resolve(artifact + "-5.2.23.jar");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(frameworkJar))) {
                zip.putNextEntry(new ZipEntry("META-INF/native-image/io.micronaut/" + artifact + "/native-image.properties"));
                zip.write("Args=--features=io.micronaut.core.io.service.ServiceLoaderFeature\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            List<String> arguments = new ArrayList<>();
            String module = "io.micronaut:" + artifact;
            NativeImageConfigurationSupport.addBundledConfigurationExclusions(
                arguments,
                List.of(frameworkJar),
                Set.of(module),
                ignored -> module + ":5.2.23",
                false
            );

            assertTrue(arguments.isEmpty(), artifact + " must retain its native-image configuration");
        }
    }
}
