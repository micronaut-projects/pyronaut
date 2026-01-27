package io.micronaut.python.cli.util;

import io.micronaut.python.cli.PyronautFileWatcher;
import io.micronaut.python.cli.PyronautWatcherFactory;
import io.micronaut.python.cli.ui.UiController;

import java.io.DataOutputStream;

public final class WatcherThreads {
    private static final String NAME = "PyronautWatcher";

    private WatcherThreads() {}

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
