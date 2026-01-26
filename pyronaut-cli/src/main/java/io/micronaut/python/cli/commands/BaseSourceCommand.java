/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.python.cli.commands;

import io.micronaut.python.cli.CliMode;
import io.micronaut.python.cli.PyronautFileWatcher;
import io.micronaut.python.cli.ui.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.util.function.Function;

abstract class BaseSourceCommand extends BaseCommand {

    protected int runUnified(
            BiFunction<UiController, DataOutputStream, PyronautFileWatcher> runFactory,
            BiFunction<UiController, DataOutputStream, PyronautFileWatcher> testFactory,
            Mode initial,
            DataInputStream eventIn,
            DataOutputStream eventOut
    ) {
        if (CliMode.isPlain()) {
            var controller = new UiController();
            var factory = initial == Mode.RUN ? runFactory : testFactory;
            var watcher = factory.apply(controller, eventOut);
            var watcherThread = Thread.ofVirtual().name("PyronautWatcher").unstarted(watcher);
            watcherThread.start();
            var loop = new ProtocolEventLoop(eventIn, new ConsoleEventSink(System.out, System.err));
            var loopThread = Thread.ofVirtual().name("Pyronaut-PlainEvents").unstarted(loop);
            loopThread.start();
            try {
                watcherThread.join();
                loopThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        StreamsCapture.installGlobal();
        var tui = new PyronautTui();
        tui.setEventInputStream(eventIn);
        long ringStart = StreamsCapture.getInstance().tailIndex();
        tui.setInitialLogStart(ringStart);
        tui.setMode(initial);

        ExecutorService controlExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

        class WatcherHandle {
            final PyronautFileWatcher watcher;
            final Thread thread;
            WatcherHandle(PyronautFileWatcher watcher, Thread thread) {
                this.watcher = watcher;
                this.thread = thread;
            }
            void stop(Duration timeout) {
                try {
                    watcher.stop();
                    thread.join(timeout.toMillis());
                } catch (Exception ignored) {
                }
            }
        }

        final UiController controller = tui.getController();
        final ModeCoordinator coordinator = new ModeCoordinator();
        final AtomicReference<Mode> desiredMode = new AtomicReference<>(initial);
        tui.setCoordinator(coordinator);
        tui.setDesiredModeSupplier(desiredMode::get);
        final class State {
            Mode currentMode = initial;
            WatcherHandle handle;
        }
        final State state = new State();

        Runnable quit = () -> controlExecutor.submit(() -> {
            if (state.handle != null) {
                state.handle.stop(Duration.ofSeconds(2));
            }
        });
        tui.setOnQuit(quit);

        Runnable startRun = () -> controlExecutor.submit(() -> {
            state.currentMode = Mode.RUN;
            tui.setMode(Mode.RUN);
            if (state.handle != null) {
                state.handle.stop(Duration.ofSeconds(2));
            }
            var w = runFactory.apply(controller, eventOut);
            if (w == null) {
                return;
            }
            var t = new Thread(w, "PyronautWatcher");
            t.setDaemon(true);
            t.start();
            state.handle = new WatcherHandle(w, t);
        });

        Runnable startTest = () -> controlExecutor.submit(() -> {
            state.currentMode = Mode.TEST;
            tui.setMode(Mode.TEST);
            if (state.handle != null) {
                state.handle.stop(Duration.ofSeconds(2));
            }
            var w = testFactory.apply(controller, eventOut);
            if (w == null) {
                return;
            }
            var t = new Thread(w, "PyronautWatcher");
            t.setDaemon(true);
            t.start();
            state.handle = new WatcherHandle(w, t);
        });

        // Ctrl+R switches or restarts Run; stage UI immediately for feedback
        tui.setOnCtrlR(() -> controlExecutor.submit(() -> {
            if (state.currentMode == Mode.RUN) {
                coordinator.expectRunStopped();
                controller.resetState();
                controller.setRunning();
                desiredMode.set(Mode.RUN);
                tui.setMode(Mode.RUN);
                if (state.handle != null) {
                    state.handle.stop(Duration.ofSeconds(2));
                }
                coordinator.awaitRunStopped(Duration.ofSeconds(3));
                var w = runFactory.apply(controller, eventOut);
                if (w == null) {
                    return;
                }
                var t = new Thread(w, "PyronautWatcher");
                t.setDaemon(true);
                t.start();
                state.handle = new WatcherHandle(w, t);
            } else {
                coordinator.expectTestFinished();
                controller.resetState();
                controller.setRunning();
                desiredMode.set(Mode.RUN);
                tui.setMode(Mode.RUN);
                if (state.handle != null) {
                    state.handle.stop(Duration.ofSeconds(2));
                }
                coordinator.awaitTestFinished(Duration.ofSeconds(3));
                var w = runFactory.apply(controller, eventOut);
                if (w == null) {
                    return;
                }
                var t = new Thread(w, "PyronautWatcher");
                t.setDaemon(true);
                t.start();
                state.handle = new WatcherHandle(w, t);
                state.currentMode = Mode.RUN;
            }
        }));

        tui.setOnCtrlT(() -> controlExecutor.submit(() -> {
            if (state.currentMode == Mode.TEST) {
                coordinator.expectTestFinished();
                controller.resetState();
                controller.startTesting();
                desiredMode.set(Mode.TEST);
                tui.setMode(Mode.TEST);
                if (state.handle != null) {
                    state.handle.stop(Duration.ofSeconds(2));
                }
                coordinator.awaitTestFinished(Duration.ofSeconds(3));
                var w = testFactory.apply(controller, eventOut);
                if (w == null) {
                    return;
                }
                var t = new Thread(w, "PyronautWatcher");
                t.setDaemon(true);
                t.start();
                state.handle = new WatcherHandle(w, t);
            } else {
                coordinator.expectRunStopped();
                controller.resetState();
                controller.startTesting();
                desiredMode.set(Mode.TEST);
                tui.setMode(Mode.TEST);
                if (state.handle != null) {
                    state.handle.stop(Duration.ofSeconds(2));
                }
                coordinator.awaitRunStopped(Duration.ofSeconds(3));
                var w = testFactory.apply(controller, eventOut);
                if (w == null) {
                    return;
                }
                var t = new Thread(w, "PyronautWatcher");
                t.setDaemon(true);
                t.start();
                state.handle = new WatcherHandle(w, t);
                state.currentMode = Mode.TEST;
            }
        }));

        var tuiThread = Thread.ofVirtual().name("PyronautTUI").unstarted(() -> {
            try {
                tui.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        controlExecutor.submit(() -> {
            var w = (initial == Mode.RUN ? runFactory : testFactory).apply(controller, eventOut);
            if (w == null) {
                return;
            }
                var t = new Thread(w, "PyronautWatcher");
                t.setDaemon(true);
                t.start();
                state.handle = new WatcherHandle(w, t);
        });

        tuiThread.start();

        try {
            tuiThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            controlExecutor.shutdownNow();
        }

        return 0;
    }
    protected Path resolveRootDir() {
        try {
            return Path.of(".").toFile().getCanonicalFile().toPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected int runWatcherWithMode(Function<UiController, PyronautFileWatcher> watcherFactory, DataInputStream eventIn) {
        boolean usePlain = CliMode.isPlain();
        if (usePlain) {
            var controller = new UiController();
            var watcher = watcherFactory.apply(controller);
            var watcherThread = new Thread(watcher, "PyronautWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            var loop = new ProtocolEventLoop(eventIn, new ConsoleEventSink(System.out, System.err));
            var loopThread = new Thread(loop, "Pyronaut-PlainEvents");
            loopThread.setDaemon(true);
            loopThread.start();
            try {
                watcherThread.join();
                loopThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        } else {
            StreamsCapture.installGlobal();
            var tui = new PyronautTui();
            tui.setEventInputStream(eventIn);
            // pass initial ring index so TUI can backfill early logs once consumers attach
            long ringStart = StreamsCapture.getInstance().tailIndex();
            tui.setInitialLogStart(ringStart);
            var watcher = watcherFactory.apply(tui.getController());
            tui.setOnQuit(watcher::stop);
            var watcherThread = new Thread(watcher, "PyronautWatcher");
            var tuiThread = new Thread(() -> {
                try {
                    tui.run();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }, "PyronautTUI");
            watcherThread.setDaemon(true);
            tuiThread.setDaemon(true);
            watcherThread.start();
            tuiThread.start();
            try {
                watcherThread.join();
                tuiThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }
}
