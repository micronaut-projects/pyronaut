/*
 * Copyright 2017-2025
 * Licensed under the Apache License, Version 2.0
 */
package io.micronaut.python.cli.ui;

import dev.tamboui.annotations.bindings.OnAction;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.event.Event;

/**
 * Minimal global actions used by ToolkitRunner with bindings.
 * Registered via ToolkitRunner.Builder.app(this).withAutoBindingRegistration().
 */
public final class GlobalActions {

    @FunctionalInterface
    public interface Quitter {
        void quit();
    }

    private Quitter quitter;
    private Runnable switchToRun;
    private Runnable switchToTest;

    public void setQuitter(Quitter quitter) {
        this.quitter = quitter;
    }

    public void setSwitchToRun(Runnable r) {
        this.switchToRun = r;
    }

    public void setSwitchToTest(Runnable r) {
        this.switchToTest = r;
    }

    @OnAction(Actions.QUIT)
    void quit(Event e) {
        var q = this.quitter;
        if (q != null) {
            q.quit();
        }
    }

    @OnAction("switchToRun")
    void onSwitchToRun(Event e) {
        var r = this.switchToRun;
        if (r != null) {
            r.run();
        }
    }

    @OnAction("switchToTest")
    void onSwitchToTest(Event e) {
        var r = this.switchToTest;
        if (r != null) {
            r.run();
        }
    }
}
