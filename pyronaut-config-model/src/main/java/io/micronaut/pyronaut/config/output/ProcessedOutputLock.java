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

import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The lock of the processed classes, {@code __pyronaut__/classes}, which {@code pyronaut process} writes and which the
 * development runtime of {@code micronaut-dev} compiles into in {@code pyronaut dev} and {@code pyronaut test -t}.
 * The runtime holds its own lock, {@code io.micronaut.dev.compile.OutputLock}, across each compilation and each whole
 * read of the output: a file beside the output, named after it with {@value #SUFFIX}, locked whole with
 * {@link FileChannel#lock()}. This lock is the same file, locked the same way, so that a development process and the
 * processing that precedes another one's launch never write the output at once.
 *
 * <p>Between processes it is the file lock, an {@code fcntl} record lock on macOS and Linux and a {@code LockFileEx}
 * lock on Windows; within one process a reentrant lock, since the JVM holds a file lock for the process and not for a
 * thread. The runtime's lock in the same JVM is a different one: a process takes this lock only while no development
 * runtime of its own holds the output.</p>
 */
@Internal
public final class ProcessedOutputLock {

    /**
     * The suffix of the lock file's name, after the output's.
     */
    public static final String SUFFIX = ".micronaut-dev.lock";

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ProcessedOutputLock() {
    }

    /**
     * The lock file of an output: beside it, as the development runtime names it.
     *
     * @param output The class output
     * @return The lock file
     */
    public static Path lockFile(Path output) {
        Path normalized = output.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("No lock file beside the root directory " + normalized);
        }
        return normalized.resolveSibling(name + SUFFIX);
    }

    /**
     * Takes the lock of an output, waiting for another process that holds it.
     *
     * @param output The class output
     * @param onWait Called once, before waiting, when another process holds the lock
     * @return The hold, to close
     * @throws IOException if the lock file cannot be created or locked
     */
    public static Hold lock(Path output, Runnable onWait) throws IOException {
        Path file = lockFile(output);
        ReentrantLock local = LOCKS.computeIfAbsent(file, path -> new ReentrantLock());
        local.lock();
        if (local.getHoldCount() > 1) {
            return local::unlock;
        }
        FileChannel channel;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            local.unlock();
            throw e;
        }
        try {
            FileLock taken = channel.tryLock();
            if (taken == null) {
                onWait.run();
                channel.lock();
            }
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            } finally {
                local.unlock();
            }
            throw e;
        }
        return () -> {
            try {
                // closing the channel releases its lock
                channel.close();
            } catch (IOException ignored) {
                // the process releases the lock when it exits
            } finally {
                local.unlock();
            }
        };
    }

    /**
     * A hold of the lock, released when closed.
     */
    @FunctionalInterface
    public interface Hold extends AutoCloseable {
        @Override
        void close();
    }
}
