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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

final class NativeImageConfigurationSupport {
    private NativeImageConfigurationSupport() {
    }

    static void addBundledConfigurationExclusions(List<String> command,
                                                  List<Path> runtimeClasspath,
                                                  Set<String> selectedModules,
                                                  Function<Path, String> gavResolver,
                                                  boolean verbose) {
        int exclusions = 0;
        for (Path entry : runtimeClasspath) {
            String gav = gavResolver.apply(entry);
            if (gav == null || !selectedModules.contains(gav.substring(0, gav.lastIndexOf(':')))
                || !hasBundledNativeImageConfiguration(entry)) {
                continue;
            }
            command.add("--exclude-config");
            command.add(".*\\Q" + entry.toAbsolutePath().normalize().getFileName() + "\\E.*");
            command.add("^/META-INF/native-image/.*");
            exclusions++;
        }
        if (verbose) {
            System.err.println("Bundled native-image configurations excluded: " + exclusions + ", metadata modules: " + selectedModules);
        }
    }

    private static boolean hasBundledNativeImageConfiguration(Path entry) {
        if (entry == null || !Files.isRegularFile(entry)
            || !entry.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        // ZipFile reads only the central directory; streaming the jar would inflate every entry
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            return zip.stream()
                .anyMatch(zipEntry -> !zipEntry.isDirectory() && zipEntry.getName().startsWith("META-INF/native-image/"));
        } catch (IOException ignored) {
            // Native-image will report unreadable classpath entries itself.
        }
        return false;
    }
}
