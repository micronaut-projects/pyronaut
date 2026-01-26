package io.micronaut.python.cli.ui;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ModeCoordinator {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition runStoppedCond = lock.newCondition();
    private final Condition testFinishedCond = lock.newCondition();
    private boolean runStoppedArmed;
    private boolean runStoppedDone;
    private boolean testFinishedArmed;
    private boolean testFinishedDone;

    public void expectRunStopped() {
        lock.lock();
        try {
            runStoppedArmed = true;
            runStoppedDone = false;
        } finally {
            lock.unlock();
        }
    }

    public void expectTestFinished() {
        lock.lock();
        try {
            testFinishedArmed = true;
            testFinishedDone = false;
        } finally {
            lock.unlock();
        }
    }

    public void signalRunStopped() {
        lock.lock();
        try {
            if (runStoppedArmed) {
                runStoppedDone = true;
                runStoppedCond.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public void signalTestFinished() {
        lock.lock();
        try {
            if (testFinishedArmed) {
                testFinishedDone = true;
                testFinishedCond.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitRunStopped(Duration timeout) {
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeout.toMillis());
        lock.lock();
        try {
            while (!runStoppedDone) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = runStoppedCond.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitTestFinished(Duration timeout) {
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeout.toMillis());
        lock.lock();
        try {
            while (!testFinishedDone) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = testFinishedCond.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }
}
