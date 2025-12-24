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

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Interface for panel content in the Pyronaut control panel.
 * Follows the pattern from TamboUI demo panel content classes.
 */
interface PanelContent {

    /**
     * Get the panel title.
     */
    String title();

    /**
     * Get the panel color for borders.
     */
    Color color();

    /**
     * Get the panel width.
     */
    int width();

    /**
     * Get the panel height.
     */
    int height();

    /**
     * Render the panel content.
     * @param focused whether the panel is currently focused
     * @return the rendered element
     */
    Element render(boolean focused);

    /**
     * Handle key events for the panel.
     * @param event the key event
     * @return the event result
     */
    default EventResult handleKey(KeyEvent event) {
        return EventResult.UNHANDLED;
    }

    /**
     * Called on each tick for animations/updates.
     * @param tick the current tick count
     */
    default void onTick(long tick) {
        // Default: no-op
    }
}
