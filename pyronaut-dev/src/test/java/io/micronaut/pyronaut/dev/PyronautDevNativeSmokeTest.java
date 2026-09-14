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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PyronautDevNativeSmokeTest {
    @Test
    void nativeBinaryPrintsHelp() throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");
        Process process = new ProcessBuilder(binary, "--help")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        process.waitFor(30, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue());
        assertEquals(true, new File(binary).canExecute());
    }

    @Test
    void nativeBinaryLoadsResourcesFromJavaClassPath(@TempDir Path tempDir) throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");
        Path resourceJar = tempDir.resolve("mysql-resource.jar");
        writeResourceJar(resourceJar, "com/mysql/cj/TlsSettings.properties");

        Process process = new ProcessBuilder(
            binary,
            "-Djava.class.path=" + resourceJar,
            "-Dpyronaut.dev.verify-system-resource=com/mysql/cj/TlsSettings.properties",
            "--help"
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        process.waitFor(30, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue());
    }

    @Test
    void nativeBinaryIncludesOpenApiParserClassResources() throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");

        Process process = new ProcessBuilder(
            binary,
            "-Dpyronaut.dev.verify-system-resource=org/pegdown/Parser.class",
            "--help"
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    @Test
    void nativeInstallUsesBuildTimeAnnotationProcessorOptions(@TempDir Path tempDir) throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "native-options-smoke"
            version = "1.0.0"

            [tool.pyronaut.test-resources]
            enabled = false
            """);

        Process process = new ProcessBuilder(
            binary,
            "install",
            "--project-dir",
            project.toString(),
            "--local-repository",
            tempDir.resolve("maven-local").toString()
        )
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), output);
        assertEquals(0, process.exitValue(), output);
        String options = Files.readString(
            project.resolve("__pyronaut__/annotation-processor-options.properties"),
            StandardCharsets.UTF_8
        );
        assertTrue(options.contains("micronaut.openapi.enabled"), options);
    }

    @Test
    void nativeBinaryDoesNotBakeInBuildHostHomeDirectory() throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");
        // The processor error-dump directory (and every other per-user path) must be
        // resolved from user.home when the launcher runs, not when the image is built.
        // A user.home-derived value captured by build-time class initialisation ends up
        // in the image heap as the build host's home directory, which is what produced
        // "Full error details could not be written: /Users/ci_admin" in the 0.0.1 launchers.
        Path buildHostPyronautHome = Path.of(System.getProperty("user.home"), ".pyronaut");
        assertFalse(
            binaryContains(Path.of(binary), buildHostPyronautHome.toString()),
            "native image contains build host path " + buildHostPyronautHome
                + "; a user.home-derived path was initialised at image build time"
        );
    }

    private static boolean binaryContains(Path binary, String text) throws Exception {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[1 << 20];
        byte[] window = new byte[buffer.length + needle.length - 1];
        int carried = 0;
        try (InputStream input = Files.newInputStream(binary)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                System.arraycopy(buffer, 0, window, carried, read);
                int length = carried + read;
                if (indexOf(window, length, needle) >= 0) {
                    return true;
                }
                carried = Math.min(needle.length - 1, length);
                System.arraycopy(window, length - carried, window, 0, carried);
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        outer:
        for (int i = 0; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void writeResourceJar(Path jar, String resourceName) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resourceName));
            output.write("enabledTLSProtocols=TLSv1.2,TLSv1.3\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
