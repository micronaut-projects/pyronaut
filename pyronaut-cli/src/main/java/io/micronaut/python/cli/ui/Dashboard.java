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

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.Keys;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Static dashboard layout for Pyronaut TUI.
 * Lays out 4 panels responsively: Compilation, Notifications, App Status, Test Results.
 * Supports focus cycling with Tab/Shift+Tab.
 */
public final class Dashboard implements Element {

    private final UiController controller;
    private final PanelContent[] panels;
    private int focusedPanelIndex = 0; // Start with Compilation (0)

    public Dashboard(UiController controller) {
        this.controller = controller;
        this.panels = new PanelContent[]{
            new CompilationPanel(controller),
            new NotificationsPanel(controller),
            new AppStatusPanel(controller),
            new TestResultsPanel(controller)
        };
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        int totalWidth = area.width();
        int totalHeight = area.height();

        boolean isWide = totalWidth >= 100; // Threshold for two-column layout

        if (isWide) {
            // Two-column layout
            int leftWidth = (int) (totalWidth * 0.6);
            int rightWidth = totalWidth - leftWidth;
            int leftHeight = totalHeight;
            int rightHeight = totalHeight;

            // Left column: Compilation (top 65%), Notifications (bottom 35%)
            int compHeight = (int) (leftHeight * 0.65);
            int notifHeight = leftHeight - compHeight;

            Rect compRect = new Rect(area.x(), area.y(), leftWidth, compHeight);
            Rect notifRect = new Rect(area.x(), area.y() + compHeight, leftWidth, notifHeight);

            // Right column: App Status (top ~5-8 lines), Test Results (rest)
            int statusHeight = Math.min(8, (int) (rightHeight * 0.2));
            int testHeight = rightHeight - statusHeight;

            Rect statusRect = new Rect(area.x() + leftWidth, area.y(), rightWidth, statusHeight);
            Rect testRect = new Rect(area.x() + leftWidth, area.y() + statusHeight, rightWidth, testHeight);

            // Render panels
            renderPanel(frame, compRect, context, 0); // Compilation
            renderPanel(frame, notifRect, context, 1); // Notifications
            renderPanel(frame, statusRect, context, 2); // App Status
            renderPanel(frame, testRect, context, 3); // Test Results

        } else {
            // Single-column layout for narrow terminals
            int panelHeight = totalHeight / 4; // Divide evenly
            int y = area.y();
            for (int i = 0; i < panels.length; i++) {
                int h = (i == panels.length - 1) ? (area.y() + totalHeight - y) : panelHeight;
                Rect rect = new Rect(area.x(), y, totalWidth, h);
                renderPanel(frame, rect, context, i);
                y += h;
            }
        }
    }

    private void renderPanel(Frame frame, Rect rect, RenderContext context, int index) {
        PanelContent panel = panels[index];
        boolean focused = (index == focusedPanelIndex);
        Color borderColor = focused ? Color.WHITE : Color.DARK_GRAY;

        // Get the content element
        Element contentElement = panel.render(focused);

        // Wrap in a panel with border
        var borderedPanel = Toolkit.panel(panelTitles[index], () -> contentElement)
                .borderColor(borderColor)
                .rounded();

        borderedPanel.render(frame, rect, context);
    }

    private static final String[] panelTitles = {
        "Compilation", "Notifications", "App Status", "Test Results"
    };

    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        if (!focused) return EventResult.UNHANDLED;

        // Handle focus cycling
        if (Keys.isTab(event)) {
            // Tab: cycle to next panel
            focusedPanelIndex = (focusedPanelIndex + 1) % panels.length;
            return EventResult.HANDLED;
        }

        // Delegate to focused panel
        return panels[focusedPanelIndex].handleKey(event);
    }
}
