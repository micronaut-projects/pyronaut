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
import io.micronaut.python.cli.ui.controls.ScrollableText;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel showing notifications and log messages.
 * Follows TamboUI panel content patterns.
 */
final class NotificationsPanel implements PanelContent {

    private final UiController controller;

    NotificationsPanel(UiController controller) {
        this.controller = controller;
    }

    @Override
    public String title() {
        return "Notifications";
    }

    @Override
    public Color color() {
        return Color.CYAN;
    }

    @Override
    public int width() {
        return 50;
    }

    @Override
    public int height() {
        return 8;
    }

    @Override
    public Element render(boolean focused) {
        List<String> lines = new ArrayList<>();
        lines.add("📢 Notifications");
        lines.add("");

        List<UiModel.Notification> history = controller.getNotificationHistory();
        if (history.isEmpty()) {
            lines.add("No notifications");
        } else {
            for (UiModel.Notification notif : history) {
                String time = notif.timestamp().atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                String severity = switch (notif.severity()) {
                    case INFO -> "ℹ️";
                    case SUCCESS -> "✅";
                    case WARNING -> "⚠️";
                    case ERROR -> "❌";
                };
                lines.add("[" + time + "] " + severity + " " + notif.message());
            }
        }

        return new ScrollableText(lines, false); // no follow-tail for notifications
    }
}
