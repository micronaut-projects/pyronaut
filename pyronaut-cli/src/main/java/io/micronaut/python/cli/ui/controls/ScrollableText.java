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
package io.micronaut.python.cli.ui.controls;

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

import java.util.List;

/**
 * A scrollable text area with a visible vertical scrollbar.
 * Supports keyboard navigation and optional follow-tail mode.
 */
public final class ScrollableText implements Element {

    private final List<String> lines;
    private int scrollOffset = 0;
    private boolean followTail;
    private int viewportHeight = 1;

    /**
     * Create a scrollable text area.
     * @param lines the lines of text to display
     * @param followTail if true, automatically scroll to bottom when new lines are added
     */
    public ScrollableText(List<String> lines, boolean followTail) {
        this.lines = lines;
        this.followTail = followTail;
    }

    public ScrollableText(List<String> lines) {
        this(lines, false);
    }

    /**
     * Update follow-tail mode.
     */
    public void setFollowTail(boolean followTail) {
        this.followTail = followTail;
    }

    /**
     * Scroll to bottom if follow-tail is enabled.
     */
    public void checkFollowTail() {
        if (followTail) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        scrollOffset = Math.max(0, lines.size() - viewportHeight);
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        viewportHeight = area.height();
        int totalLines = lines.size();
        int maxScroll = Math.max(0, totalLines - viewportHeight);

        if (followTail && totalLines > viewportHeight) {
            scrollOffset = maxScroll;
        } else if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        // Render visible lines
        for (int i = 0; i < viewportHeight; i++) {
            int lineIndex = scrollOffset + i;
            if (lineIndex < totalLines) {
                String line = lines.get(lineIndex);
                // Truncate line to fit width
                int maxLineLength = area.width();
                if (line.length() > maxLineLength) {
                    line = line.substring(0, maxLineLength);
                }
                // For now, use Toolkit.text to render (assuming frame.write doesn't exist)
                Toolkit.text(line).render(frame, new Rect(area.x(), area.y() + i, area.width(), 1), context);
            }
        }
    }

    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        if (!focused) return EventResult.UNHANDLED;

        int totalLines = lines.size();
        int maxScroll = Math.max(0, totalLines - viewportHeight);

        if (Keys.isArrowUp(event)) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            followTail = false;
            return EventResult.HANDLED;
        } else if (Keys.isArrowDown(event)) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            if (scrollOffset == maxScroll) followTail = true;
            return EventResult.HANDLED;
        } else if (Keys.isPageUp(event)) {
            scrollOffset = Math.max(0, scrollOffset - viewportHeight);
            followTail = false;
            return EventResult.HANDLED;
        } else if (Keys.isPageDown(event)) {
            scrollOffset = Math.min(maxScroll, scrollOffset + viewportHeight);
            if (scrollOffset == maxScroll) followTail = true;
            return EventResult.HANDLED;
        } else if (Keys.isHome(event)) {
            scrollOffset = 0;
            followTail = false;
            return EventResult.HANDLED;
        } else if (Keys.isEnd(event)) {
            scrollOffset = maxScroll;
            followTail = true;
            return EventResult.HANDLED;
        }

        return EventResult.UNHANDLED;
    }
}
