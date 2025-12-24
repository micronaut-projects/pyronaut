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
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import io.micronaut.python.cli.ui.controls.ScrollableText;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel showing application status and URI.
 * Follows TamboUI panel content patterns.
 */
final class AppStatusPanel implements PanelContent {

    private final UiController controller;

    AppStatusPanel(UiController controller) {
        this.controller = controller;
    }

    @Override
    public String title() {
        return "Application Status";
    }

    @Override
    public Color color() {
        return Color.GREEN;
    }

    @Override
    public int width() {
        return 35;
    }

    @Override
    public int height() {
        return 8;
    }

    @Override
    public Element render(boolean focused) {
        var model = controller.getModel();
        return switch (model) {
            case UiModel.Idle __ -> Toolkit.column(
                Toolkit.text("Status: ").cyan().bold(),
                Toolkit.text("Not Running").dim(),
                Toolkit.text(""),
                Toolkit.text("Ready to start").white()
            );
            case UiModel.Running r -> {
                List<String> lines = new ArrayList<>();
                lines.add("Status: Running");
                lines.add("");
                lines.add("URI: " + r.uri());
                lines.add("");
                lines.add("Endpoints:");
                lines.addAll(r.endpoints());
                yield Toolkit.column(
                    Toolkit.text("Status: ").cyan().bold(),
                    Toolkit.text("Running").green(),
                    Toolkit.text(""),
                    Toolkit.text("URI: ").cyan(),
                    Toolkit.text(r.uri()).yellow(),
                    Toolkit.text(""),
                    new ScrollableText(lines.subList(5, lines.size()), false) // endpoints scrollable
                );
            }
            case UiModel.Compiling c -> Toolkit.column(
                Toolkit.text("Status: ").cyan().bold(),
                Toolkit.text("Compiling").yellow(),
                Toolkit.text(""),
                Toolkit.text("Building...").white()
            );
            case UiModel.Testing t -> Toolkit.column(
                Toolkit.text("Status: ").cyan().bold(),
                Toolkit.text("Testing").blue(),
                Toolkit.text(""),
                Toolkit.text("Running tests...").white()
            );
        };
    }
}
