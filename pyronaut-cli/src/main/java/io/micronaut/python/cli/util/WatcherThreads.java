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
package io.micronaut.python.cli.util;

import io.micronaut.python.cli.PyronautFileWatcher;
import io.micronaut.python.cli.PyronautWatcherFactory;
import io.micronaut.python.cli.ui.UiController;

import java.io.DataOutputStream;

/**
 * Helper to start file watcher threads for the CLI tooling.
 *
 * <p>Creates and starts daemon watcher threads using a {@link PyronautWatcherFactory}
 * and returns the created {@link PyronautFileWatcher} instance.</p>
 */
public final class WatcherThreads {
    private static final String NAME = "PyronautWatcher";

    private WatcherThreads() { }

    /**
     * Create and start a daemon watcher thread using the provided factory.
     *
     * @param controller UI controller receiving watcher events
     * @param eventOut stream where watcher events are written
     * @param factory factory to create the watcher
     * @return the started watcher instance
     * @throws IllegalStateException if the factory failed to create a watcher
     */
    public static PyronautFileWatcher start(UiController controller,
                                      DataOutputStream eventOut,
                                      PyronautWatcherFactory factory) {
        var watcher = factory.create(controller, eventOut);
        if (watcher == null) {
            throw new IllegalStateException();
        }
        var t = new Thread(watcher, NAME);
        t.setDaemon(true);
        t.start();
        return watcher;
    }

}
