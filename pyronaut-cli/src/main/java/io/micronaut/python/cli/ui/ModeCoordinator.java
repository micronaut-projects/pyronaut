package io.micronaut.python.cli.ui;
 
import io.micronaut.python.cli.PyronautFileWatcher;
 
import java.util.concurrent.locks.ReentrantLock;
 
public final class ModeCoordinator {
    private final ReentrantLock lock = new ReentrantLock();
    private PyronautFileWatcher fileWatcher;
 
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
 
    public void registerFileWatcher(PyronautFileWatcher watcher) {
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

