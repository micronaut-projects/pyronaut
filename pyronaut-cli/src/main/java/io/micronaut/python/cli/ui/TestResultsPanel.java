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
 * Panel showing test results and status.
 * Follows TamboUI panel content patterns.
 */
final class TestResultsPanel implements PanelContent {

    private final UiController controller;

    TestResultsPanel(UiController controller) {
        this.controller = controller;
    }

    @Override
    public String title() {
        return "Test Results";
    }

    @Override
    public Color color() {
        return Color.BLUE;
    }

    @Override
    public int width() {
        return 40;
    }

    @Override
    public int height() {
        return 10;
    }

    @Override
    public Element render(boolean focused) {
        var model = controller.getModel();
        return switch (model) {
            case UiModel.Testing t -> {
                List<String> lines = new ArrayList<>();
                lines.add("🧪 Testing");
                lines.add("");
                flattenTestTree(t.testTree(), lines, "");
                yield new ScrollableText(lines, false); // scrollable flat list
            }
            default -> {
                List<String> lines = new ArrayList<>();
                lines.add("🧪 Test Results");
                lines.add("");
                lines.add("No tests running");
                yield new ScrollableText(lines, false);
            }
        };
    }

    private void flattenTestTree(UiModel.TestTree tree, List<String> lines, String prefix) {
        switch (tree) {
            case UiModel.TestSuite suite -> {
                lines.add(prefix + "📁 " + suite.name() + " " + statusBadge(suite.status()));
                for (var class_ : suite.classes()) {
                    flattenTestTree(class_, lines, prefix + "  ");
                }
            }
            case UiModel.TestClass class_ -> {
                lines.add(prefix + "📄 " + class_.name() + " " + statusBadge(class_.status()));
                for (var method : class_.methods()) {
                    flattenTestTree(method, lines, prefix + "  ");
                }
            }
            case UiModel.TestMethod method -> {
                String failure = method.failureMessage().map(msg -> " - " + msg).orElse("");
                lines.add(prefix + "⚡ " + method.displayName() + " " + statusBadge(method.status()) + failure);
            }
        }
    }

    private String statusBadge(UiModel.Status status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "🔄";
            case PASSED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }
}
