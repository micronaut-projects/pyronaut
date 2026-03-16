/*
 * Copyright 2017-2025 original authors
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

import dev.tamboui.annotations.bindings.OnAction;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.event.Event;

/**
 * Minimal global actions used by ToolkitRunner with bindings.
 * Registered via ToolkitRunner.Builder.app(this).withAutoBindingRegistration().
 */
public final class GlobalActions {
 
    private Quitter quitter;
    private Runnable switchToRun;
    private Runnable switchToTest;

    /**
     * Sets the quitter action.
     *
     * @param quitter the quitter action
     */
    public void setQuitter(Quitter quitter) {

        this.quitter = quitter;
    }

    /**
     * Sets the action to switch to run mode.
     *
     * @param r the action to switch to run mode
     */
    public void setSwitchToRun(Runnable r) {
        this.switchToRun = r;
    }

    /**
     * Sets the action to switch to test mode.
     *
     * @param r the action to switch to test mode
     */
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

    /**
     * Functional interface for quitting action.
     */
    @FunctionalInterface
    public interface Quitter {
        void quit();
    }
}
