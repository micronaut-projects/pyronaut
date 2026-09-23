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
package io.micronaut.pyronaut.config.testresources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResourcesLogMirrorTest {

    @Test
    void reportsContainerProgressWrittenAfterWatchingBegan(@TempDir Path logs) throws Exception {
        Path log = logs.resolve("test-resources.log");
        Files.writeString(log, "16:09:00.000 [old] INFO example - Creating container for image: stale/image\n");
        List<TestResourcesLogMirror.Entry> entries = new CopyOnWriteArrayList<>();

        try (TestResourcesLogMirror mirror = TestResourcesLogMirror.start(logs, entries::add)) {
            append(log, """
                16:10:04.527 [pool-2-thread-1] INFO  tc.mysql:8.4.0 - Pulling docker image: mysql:8.4.0
                16:10:04.600 [pool-2-thread-1] INFO  tc.mysql:8.4.0 - Connected to Docker
                16:10:06.889 [pool-2-thread-1] INFO  tc.mysql:8.4.0 - Container mysql:8.4.0 started in PT2.3S
                """);
            await(entries, "started in PT2.3S");
        }

        assertEquals(
            List.of("Pulling docker image: mysql:8.4.0", "Container mysql:8.4.0 started in PT2.3S"),
            entries.stream().map(TestResourcesLogMirror.Entry::message).toList()
        );
    }

    @Test
    void reportsLinesTheServerLoggedWhileStarting(@TempDir Path logs) throws Exception {
        Path log = logs.resolve("test-resources.log");
        List<TestResourcesLogMirror.Entry> entries = new CopyOnWriteArrayList<>();

        // Watching begins before the server exists, so what it logs on the way
        // up is reported once the mirror starts.
        TestResourcesLogMirror mirror = TestResourcesLogMirror.watch(logs, entries::add);
        Files.writeString(log, "16:09:00.000 [main] ERROR server - Docker is not available\n");
        try (TestResourcesLogMirror started = mirror.start()) {
            await(entries, "Docker is not available");
        }

        assertEquals(1, entries.size());
        assertTrue(entries.get(0).error());
        assertEquals("Docker is not available", entries.get(0).message());
    }

    @Test
    void fallsBackToTheLauncherStdioLog(@TempDir Path logs) throws Exception {
        Path log = logs.resolve("launcher-stdio.log");
        List<TestResourcesLogMirror.Entry> entries = new CopyOnWriteArrayList<>();

        try (TestResourcesLogMirror mirror = TestResourcesLogMirror.start(logs, entries::add)) {
            Files.writeString(log, "16:10:04.527 [main] INFO  tc - Creating container for image: mysql:8.4.0\n");
            await(entries, "Creating container for image: mysql:8.4.0");
        }

        assertEquals("Creating container for image: mysql:8.4.0", entries.get(0).message());
        assertFalse(entries.get(0).error());
    }

    @Test
    void reportsTheFinalLinesAsItCloses(@TempDir Path logs) throws Exception {
        Path log = logs.resolve("test-resources.log");
        Files.writeString(log, "");
        List<TestResourcesLogMirror.Entry> entries = new CopyOnWriteArrayList<>();

        TestResourcesLogMirror mirror = TestResourcesLogMirror.watch(logs, entries::add);
        append(log, "16:10:06.889 [pool-2-thread-1] INFO  tc - Container mysql:8.4.0 started in PT2.3S\n");
        mirror.close();

        assertEquals(List.of("Container mysql:8.4.0 started in PT2.3S"), entries.stream().map(TestResourcesLogMirror.Entry::message).toList());
    }

    @Test
    void doesNothingWithoutALogDirectory() {
        List<TestResourcesLogMirror.Entry> entries = new CopyOnWriteArrayList<>();

        try (TestResourcesLogMirror mirror = TestResourcesLogMirror.start((Path) null, entries::add)) {
            assertFalse(mirror.active());
        }

        assertTrue(entries.isEmpty());
    }

    @Test
    void keepsALineThatCarriesNoLoggerPrefix() {
        TestResourcesLogMirror.Entry entry = TestResourcesLogMirror.entry("Pulling docker image: mysql:8.4.0");

        assertEquals("Pulling docker image: mysql:8.4.0", entry.message());
    }

    @Test
    void ignoresALineThatExplainsNoWait() {
        assertNull(TestResourcesLogMirror.entry("16:10:04.600 [pool-2-thread-1] INFO  tc - Connected to Docker"));
    }

    private static void append(Path log, String text) throws IOException {
        Files.writeString(log, text, StandardOpenOption.APPEND);
    }

    private static void await(List<TestResourcesLogMirror.Entry> entries, String message) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (entries.stream().anyMatch(entry -> entry.message().contains(message))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for '" + message + "' in " + entries);
    }
}
