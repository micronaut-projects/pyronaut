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

import java.util.ArrayList;
import java.util.List;

/**
 * Panel showing compilation status and output.
 * Follows TamboUI panel content patterns.
 */
final class CompilationPanel implements PanelContent {

    private final UiController controller;

    CompilationPanel(UiController controller) {
        this.controller = controller;
    }

    @Override
    public String title() {
        return "Compilation";
    }

    @Override
    public Color color() {
        return Color.YELLOW;
    }

    @Override
    public int width() {
        return 45;
    }

    @Override
    public int height() {
        return 12;
    }

    @Override
    public Element render(boolean focused) {
        List<String> lines = new ArrayList<>();
        if (controller.isCompiling()) {
            lines.add("🔨 Compiling");
            lines.add("");

            List<String> updatedFiles = controller.getUpdatedFiles();
            if (!updatedFiles.isEmpty()) {
                lines.add("Files changed:");
                for (String file : updatedFiles) {
                    lines.add("  " + file);
                }
                lines.add("");
            }

            lines.addAll(controller.getCompileLogLines());
            if (lines.size() <= 2) { // only header
                lines.add("Building...");
            }
        } else {
            lines.add("🔨 Compilation");
            lines.add("");
            lines.add("Ready");
        }

        return new ScrollableText(lines, controller.isCompiling()); // follow-tail when compiling
    }
}
