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
package io.micronaut.python.cli.ui;
 
import java.util.concurrent.locks.ReentrantLock;

public final class ModeCoordinator {
    private final ReentrantLock lock = new ReentrantLock();
    private Watcher fileWatcher;

    public interface Watcher {
        void stop();

        void switchMode(Mode mode);

        void requestRestart();
    }
 
    public void interruptCurrentWatcher() {
        lock.lock();
        try {
            if (fileWatcher != null) {
                fileWatcher.stop();
                fileWatcher = null;
            }
        } finally {
            lock.unlock();
        }
    }
 
    public void registerFileWatcher(Watcher watcher) {
        lock.lock();
        try {
            if (fileWatcher != null) {
                throw  new IllegalStateException("File watcher already registered");
            }
            fileWatcher = watcher;
        } finally {
            lock.unlock();
        }
    }
 
    public void requestMode(Mode mode) {
        lock.lock();
        try {
            if (fileWatcher != null) {
                fileWatcher.switchMode(mode);
            }
        } finally {
            lock.unlock();
        }
    }

    public void requestRestart() {
        lock.lock();
        try {
            if (fileWatcher != null) {
                fileWatcher.requestRestart();
            }
        } finally {
            lock.unlock();
        }
    }
}
