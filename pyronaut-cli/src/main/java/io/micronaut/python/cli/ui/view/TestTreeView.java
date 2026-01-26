/*
 * Copyright 2017-2025
 * Licensed under the Apache License, Version 2.0
 */
package io.micronaut.python.cli.ui.view;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import io.micronaut.python.cli.ui.UiModel;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.elements.ListElement.ScrollBarPolicy.ALWAYS;

/**
 * Renders UiModel.TestTree as an indented, styled tree inside a List widget.
 * - Methods show displayName and status icon/color.
 * - Suites and classes are bold and colored for readability.
 */
final class TestTreeView implements Element {

    private static final Color GREEN = Color.rgb(46, 204, 113);
    private static final Color YELLOW = Color.rgb(241, 196, 15);
    private static final Color RED = Color.rgb(231, 76, 60);
    private static final Color CYAN = Color.rgb(0, 180, 216);
    private static final Color DIM = Color.rgb(127, 140, 141);
    private static final Color BRIGHT = Color.rgb(236, 240, 241);

    private UiModel.TestTree root;
    private final ListElement<?> list = list();
    private final List<UiModel.TestTree> indexToNode = new ArrayList<>();

    TestTreeView(UiModel.TestTree root) {
        this.root = root;
    }

    @Override
    public String id() {
        return "test-tree";
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    void update(UiModel.TestTree root) {
        this.root = root;
    }

    private void flatten(UiModel.TestTree node) {
        // no-op; we now build rows on render to keep mapping aligned
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        List<StyledElement<?>> itemList = new ArrayList<>();
        indexToNode.clear();
        if (root != null) {
            renderTree(root, 0, itemList);
        } else {
            itemList.add(lineItem("No tests to display", DIM, false));
            indexToNode.add(null);
        }
        list.elements(itemList.toArray(new StyledElement<?>[0]))
                .scrollbar(ALWAYS)
                .autoScroll()
                .fill()
                .render(frame, area, context);

    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return list.handleMouseEvent(event);
    }

    Integer selectedNodeIndex() {
        return list.selected();
    }

    UiModel.TestTree selectedNode() {
        Integer idx = list.selected();
        if (idx == null || idx < 0 || idx >= indexToNode.size()) {
            return null;
        }
        return indexToNode.get(idx);
    }

    private void renderTree(UiModel.TestTree node, int depth, List<StyledElement<?>> out) {
        if (node instanceof UiModel.TestSuite suite) {
            // Flatten a single class with the same name as the suite to avoid duplicate 'Tests'/'All tests'
            var classes = suite.classes();
            if (classes.size() == 1) {
                out.add(suiteItem(suite, depth));
                indexToNode.add(suite);
                var only = classes.get(0);
                for (UiModel.TestMethod m : only.methods()) {
                    renderTree(m, depth + 1, out);
                }
                return;
            }
            out.add(suiteItem(suite, depth));
            indexToNode.add(suite);
            for (UiModel.TestClass c : classes) {
                renderTree(c, depth + 1, out);
            }
        } else if (node instanceof UiModel.TestClass clazz) {
            out.add(classItem(clazz, depth));
            indexToNode.add(clazz);
            for (UiModel.TestMethod m : clazz.methods()) {
                renderTree(m, depth + 1, out);
            }
        } else if (node instanceof UiModel.TestMethod method) {
            out.add(methodItem(method, depth));
            indexToNode.add(method);
            method.failureMessage().ifPresent(msg -> {
                String indent = " ".repeat((depth + 1) * 2);
                out.add(lineItem(indent + "↳ " + msg, DIM, false));
                indexToNode.add(method);
            });
        }
    }

    private StyledElement<?> suiteItem(UiModel.TestSuite suite, int depth) {
        String indent = " ".repeat(depth * 2);
        String icon = statusIcon(suite.status());
        Color color = switch (suite.status()) {
            case PASSED -> GREEN;
            case FAILED -> RED;
            case SKIPPED -> YELLOW;
            case RUNNING -> CYAN;
            case PENDING -> DIM;
        };
        return lineItem(indent + icon + " " + suite.name(), color, true);
    }

    private StyledElement<?> classItem(UiModel.TestClass clazz, int depth) {
        String indent = " ".repeat(depth * 2);
        String icon = statusIcon(clazz.status());
        Color color = switch (clazz.status()) {
            case PASSED -> GREEN;
            case FAILED -> RED;
            case SKIPPED -> YELLOW;
            case RUNNING -> CYAN;
            case PENDING -> DIM;
        };
        return lineItem(indent + icon + " " + clazz.name(), color, true);
    }

    private StyledElement<?> methodItem(UiModel.TestMethod method, int depth) {
        String indent = " ".repeat(depth * 2);
        String icon = statusIcon(method.status());
        Color color = switch (method.status()) {
            case PASSED -> GREEN;
            case FAILED -> RED;
            case SKIPPED -> YELLOW;
            case RUNNING -> CYAN;
            case PENDING -> DIM;
        };
        String label = method.displayName() != null && !method.displayName().isEmpty()
                ? method.displayName()
                : method.name();
        return lineItem(indent + icon + " " + label, color, false);
    }

    private static String statusIcon(UiModel.Status s) {
        return switch (s) {
            case PASSED -> "✔";
            case FAILED -> "✖";
            case SKIPPED -> "⏭";
            case RUNNING -> "⟳";
            case PENDING -> "⏳";
        };
    }

    private static StyledElement<?> lineItem(String text, Color color, boolean bold) {
        var te = text(text).fg(color);
        if (bold) {
            te = te.bold();
        }
        return te;
    }


    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

}
