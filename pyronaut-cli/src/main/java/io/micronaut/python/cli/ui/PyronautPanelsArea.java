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

import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.Keys;
import dev.tamboui.tui.event.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Floating panels area for Pyronaut control panel.
 * Contains draggable panels for different aspects of the application state.
 * Follows the pattern from TamboUI FloatingPanelsArea demo.
 */
final class PyronautPanelsArea implements Element {

    private final UiController controller;
    private final List<PyronautPanel> panels = new ArrayList<>();
    private final Random random = new Random();
    private int nextPanelId = 1;

    PyronautPanelsArea(UiController controller) {
        this.controller = controller;
        createInitialPanels();
    }

    private void createInitialPanels() {
        // Application status panel
        panels.add(new PyronautPanel(nextPanelId++, new AppStatusPanel(controller), 2, 1));
        // Compilation panel
        panels.add(new PyronautPanel(nextPanelId++, new CompilationPanel(controller), 50, 1));
        // Test results panel
        panels.add(new PyronautPanel(nextPanelId++, new TestResultsPanel(controller), 2, 12));
        // Notifications panel
        panels.add(new PyronautPanel(nextPanelId++, new NotificationsPanel(controller), 50, 12));
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        for (var fp : panels) {
            renderPyronautPanel(frame, area, context, fp);
        }
    }

    private void renderPyronautPanel(Frame frame, Rect area, RenderContext context, PyronautPanel fp) {
        var content = fp.content;
        var relX = Math.max(0, Math.min(fp.x, area.width() - content.width()));
        var relY = Math.max(0, Math.min(fp.y, area.height() - content.height()));
        var panelArea = new Rect(area.x() + relX, area.y() + relY, content.width(), content.height());

        var focused = fp.panelId().equals(context.focusManager().focusedId());
        var borderColor = focused ? Color.WHITE : content.color();

        var p = Toolkit.panel(content.title(), () -> content.render(focused))
                .id(fp.panelId())
                .rounded()
                .borderColor(borderColor)
                .focusedBorderColor(Color.WHITE)
                .focusable()
                .onKeyEvent(event -> handlePanelKey(fp, event))
                .draggable((deltaX, deltaY) -> {
                    fp.x += deltaX;
                    fp.y += deltaY;
                });

        p.render(frame, panelArea, context);
    }

    private EventResult handlePanelKey(PyronautPanel fp, KeyEvent event) {
        var result = fp.content.handleKey(event);
        if (result.isHandled()) {
            return result;
        }

        if (Keys.isChar(event, 'x') || Keys.isChar(event, 'X')) {
            panels.removeIf(p -> p.id == fp.id);
            return EventResult.HANDLED;
        }

        return EventResult.UNHANDLED;
    }

    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        // Global shortcuts for panel management
        if (Keys.isChar(event, '1')) {
            createPanel(new AppStatusPanel(controller));
            return EventResult.HANDLED;
        }
        if (Keys.isChar(event, '2')) {
            createPanel(new CompilationPanel(controller));
            return EventResult.HANDLED;
        }
        if (Keys.isChar(event, '3')) {
            createPanel(new TestResultsPanel(controller));
            return EventResult.HANDLED;
        }
        if (Keys.isChar(event, '4')) {
            createPanel(new NotificationsPanel(controller));
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private void createPanel(PanelContent content) {
        var x = 5 + random.nextInt(30);
        var y = 3 + random.nextInt(10);
        panels.add(new PyronautPanel(nextPanelId++, content, x, y));
    }
}
