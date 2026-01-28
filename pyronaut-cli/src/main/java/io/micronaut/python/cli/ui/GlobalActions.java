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
