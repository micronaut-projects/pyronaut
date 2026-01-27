package io.micronaut.python.cli;

import io.micronaut.python.cli.ui.UiController;

import java.io.DataOutputStream;

@FunctionalInterface
public interface PyronautWatcherFactory {
    PyronautFileWatcher create(UiController controller, DataOutputStream eventOut);
}
