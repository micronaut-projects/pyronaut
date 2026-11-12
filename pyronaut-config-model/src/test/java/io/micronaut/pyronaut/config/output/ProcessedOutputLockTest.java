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
package io.micronaut.pyronaut.config.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessedOutputLockTest {

    @TempDir
    Path project;

    @Test
    void theLockFileIsTheOneTheDevelopmentRuntimeLocksBesideTheOutput() {
        Path classes = project.resolve("__pyronaut__/./classes");

        // io.micronaut.dev.Compilations#outputLock: <first output>.micronaut-dev.lock, beside it
        assertEquals(project.resolve("__pyronaut__/classes.micronaut-dev.lock").toAbsolutePath().normalize(),
            ProcessedOutputLock.lockFile(classes));
    }

    @Test
    void theLockIsReentrantInAProcessAndReleasedByTheOutermostHold() throws Exception {
        Path classes = project.resolve("__pyronaut__/classes");
        Path file = ProcessedOutputLock.lockFile(classes);

        try (ProcessedOutputLock.Hold outer = ProcessedOutputLock.lock(classes, () -> { })) {
            try (ProcessedOutputLock.Hold inner = ProcessedOutputLock.lock(classes, () -> { })) {
                assertHeldByThisProcess(file);
            }
            assertHeldByThisProcess(file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
            assertNotNull(lock);
        }
    }

    @Test
    void anotherThreadWaitsForTheHold() throws Exception {
        Path classes = project.resolve("__pyronaut__/classes");
        CompletableFuture<Void> other;
        try (ProcessedOutputLock.Hold hold = ProcessedOutputLock.lock(classes, () -> { })) {
            other = CompletableFuture.runAsync(() -> {
                try (ProcessedOutputLock.Hold taken = ProcessedOutputLock.lock(classes, () -> { })) {
                    // taken once the first hold is released
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThrows(TimeoutException.class, () -> other.get(300, TimeUnit.MILLISECONDS));
        }
        other.get(10, TimeUnit.SECONDS);
    }

    @Test
    void anotherProcessHoldingTheOutputAsTheDevelopmentRuntimeDoesIsWaitedFor() throws Exception {
        Path classes = project.resolve("__pyronaut__/classes");
        Path file = ProcessedOutputLock.lockFile(classes);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
            CoreStyleHolder.class.getName(), file.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
             OutputStream in = holder.getOutputStream()) {
            assertEquals("locked", out.readLine());

            CountDownLatch waited = new CountDownLatch(1);
            CompletableFuture<Void> lock = CompletableFuture.runAsync(() -> {
                try (ProcessedOutputLock.Hold hold = ProcessedOutputLock.lock(classes, waited::countDown)) {
                    // taken once the other process released the file
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(waited.await(10, TimeUnit.SECONDS), "the hold should report that it waits");
            assertThrows(TimeoutException.class, () -> lock.get(300, TimeUnit.MILLISECONDS));
            assertFalse(lock.isDone());

            in.write('\n');
            in.flush();
            lock.get(30, TimeUnit.SECONDS);
        } finally {
            holder.destroy();
            holder.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void assertHeldByThisProcess(Path file) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            assertThrows(OverlappingFileLockException.class, channel::tryLock);
        }
    }

    /**
     * Locks the file as {@code io.micronaut.dev.compile.OutputLock} does, until a line arrives on its input.
     */
    public static final class CoreStyleHolder {
        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            java.nio.file.Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                System.out.println("locked");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
