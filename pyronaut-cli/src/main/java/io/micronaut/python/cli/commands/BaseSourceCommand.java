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
import io.micronaut.python.cli.PyronautWatcherFactory;
import io.micronaut.python.cli.ui.*;
import io.micronaut.python.cli.util.WatcherThreads;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

abstract class BaseSourceCommand extends BaseCommand {

    protected int runUnified(
            PyronautWatcherFactory runFactory,
            PyronautWatcherFactory testFactory,
            Mode initial,
            DataInputStream eventIn,
            DataOutputStream eventOut
    ) {
        if (CliMode.isPlain()) {
            return runInCLIMode(runFactory, testFactory, initial, eventIn, eventOut);
        }

        return runInTuiMode(runFactory, testFactory, initial, eventIn, eventOut);
    }

    private int runInTuiMode(PyronautWatcherFactory runFactory,
                             PyronautWatcherFactory testFactory,
                             Mode initial,
                             DataInputStream eventIn,
                             DataOutputStream eventOut) {
        StreamsCapture.installGlobal();
        var tui = new PyronautTui();
        tui.setEventInputStream(eventIn);
        var ringStart = StreamsCapture.getInstance().tailIndex();
        tui.setInitialLogStart(ringStart);
        tui.setMode(initial);

        var controlExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

        var controller = tui.getController();
        var coordinator = new ModeCoordinator();
        var desiredMode = new AtomicReference<>(initial);
        tui.setCoordinator(coordinator);
        tui.setDesiredModeSupplier(desiredMode::get);

        Runnable quit = () -> controlExecutor.submit(coordinator::interruptCurrentWatcher);
        tui.setOnQuit(quit);

        // Ctrl+R switches to Run mode without restarting the watcher
        tui.setOnRunRequested(() -> controlExecutor.submit(() -> {
            controller.resetState();
            controller.setRunning();
            desiredMode.set(Mode.RUN);
            tui.setMode(Mode.RUN);
            coordinator.requestMode(Mode.RUN);
        }));
 
        // Ctrl+T switches to Test mode without restarting the watcher
        tui.setOnTestRequested(() -> controlExecutor.submit(() -> {
            controller.resetState();
            controller.startTesting();
            desiredMode.set(Mode.TEST);
            tui.setMode(Mode.TEST);
            coordinator.requestMode(Mode.TEST);
        }));


        var tuiThread = Thread.ofVirtual().name("PyronautTUI").unstarted(() -> {
            try {
                tui.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        controlExecutor.submit(() -> {
            coordinator.registerFileWatcher(WatcherThreads.start(controller, eventOut, (initial == Mode.RUN ? runFactory : testFactory)));
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

    private static int runInCLIMode(PyronautWatcherFactory runFactory, PyronautWatcherFactory testFactory, Mode initial, DataInputStream eventIn, DataOutputStream eventOut) {
        var controller = new UiController();
        var factory = initial == Mode.RUN ? runFactory : testFactory;
        var pyronautFileWatcher = WatcherThreads.start(controller, eventOut, factory);
        var loop = new ProtocolEventLoop(eventIn, new ConsoleEventSink(System.out, System.err));
        var loopThread = Thread.ofVirtual().name("Pyronaut-PlainEvents").unstarted(loop);
        loopThread.start();
        try {
            pyronautFileWatcher.thread().join();
            loopThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

}
