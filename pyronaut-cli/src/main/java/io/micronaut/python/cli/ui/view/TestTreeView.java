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
package io.micronaut.python.cli.ui.view;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.python.cli.ui.UiModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.tamboui.toolkit.Toolkit.text;

/**
 * Test tree view backed by the native TamboUI TreeElement.
 * - Uses TreeElement for scrolling, selection and expand/collapse.
 * - Preserves previous visual styling (icons, colors, bold for suite/class).
 * - Adds failure message as a child leaf mapped to the method node data.
 */
final class TestTreeView implements Element {

    private static final Color GREEN = Color.rgb(46, 204, 113);
    private static final Color YELLOW = Color.rgb(241, 196, 15);
    private static final Color RED = Color.rgb(231, 76, 60);
    private static final Color CYAN = Color.rgb(0, 180, 216);
    private static final Color DIM = Color.rgb(127, 140, 141);

    private UiModel.TestTree root;
    private final TreeElement<UiModel.TestTree> tree = new TreeElement<>();
    private TreeNode<UiModel.TestTree> rootNode;
    private final Set<String> expandedKeys = new HashSet<>();
    private String selectedKey;

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
        // snapshot current expansion + selection
        snapshotState();
        this.root = root;
        // rebuild model
        this.rootNode = root == null ? null : toTree(root);
        // reapply expansion
        if (this.rootNode != null) {
            reapplyExpansion(this.rootNode);
            tree.roots(this.rootNode);
            reapplySelection();
        } else {
            tree.roots();
        }
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        if (rootNode == null && root != null) {
            rootNode = toTree(root);
            tree.roots(rootNode);
        }
        if (rootNode == null) {
            TreeNode<UiModel.TestTree> none = TreeNode.<UiModel.TestTree>of("No tests to display").leaf();
            tree.roots(none)
                .nodeRenderer(node -> text(node.label()).fg(DIM))
                .scrollbar(TreeElement.ScrollBarPolicy.AS_NEEDED)
                .fill()
                .render(frame, area, context);
            return;
        }

        tree
            .nodeRenderer(this::renderNode)
            .scrollbar(TreeElement.ScrollBarPolicy.AS_NEEDED)
            .fill()
            .render(frame, area, context);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext renderContext) {
        return tree.preferredSize(availableWidth, availableHeight, renderContext);
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return tree.handleMouseEvent(event);
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        return tree.handleKeyEvent(event, focused);
    }

    Integer selectedNodeIndex() {
        return tree.selected();
    }

    UiModel.TestTree selectedNode() {
        var selected = tree.selectedNode();
        if (selected != null) {
            selectedKey = keyFor(selected.data());
            return selected.data();
        }
        return null;
    }

    private TreeNode<UiModel.TestTree> toTree(UiModel.TestTree node) {
        if (node instanceof UiModel.TestSuite suite) {
            var classes = suite.classes();
            TreeNode<UiModel.TestTree> suiteNode = TreeNode.<UiModel.TestTree>of(labelForSuite(suite), suite);
            // default expanded for visibility; persisted expansion reapplied later
            suiteNode.expanded(true);
            if (classes.size() == 1) {
                var only = classes.getFirst();
                for (UiModel.TestMethod m : only.methods()) {
                    suiteNode.add(toTree(m));
                }
                return suiteNode;
            }
            for (UiModel.TestClass c : classes) {
                suiteNode.add(toTree(c));
            }
            return suiteNode;
        }
        if (node instanceof UiModel.TestClass clazz) {
            TreeNode<UiModel.TestTree> classNode = TreeNode.<UiModel.TestTree>of(labelForClass(clazz), clazz);
            classNode.expanded(true);
            for (UiModel.TestMethod m : clazz.methods()) {
                classNode.add(toTree(m));
            }
            return classNode;
        }
        if (node instanceof UiModel.TestMethod method) {
            String label = labelForMethod(method);
            // If there's a failure message, do NOT mark leaf so child can render
            boolean hasFailure = method.failureMessage().isPresent();
            TreeNode<UiModel.TestTree> methodNode = hasFailure
                    ? TreeNode.<UiModel.TestTree>of(label, method)
                    : TreeNode.<UiModel.TestTree>of(label, method).leaf();
            if (hasFailure) {
                method.failureMessage().ifPresent(msg ->
                        methodNode.add(TreeNode.<UiModel.TestTree>of("\u21B3 " + msg, method).leaf())
                );
            }
            return methodNode;
        }
        // Fallback
        return TreeNode.<UiModel.TestTree>of("?", node).leaf();
    }

    private StyledElement<?> renderNode(TreeNode<UiModel.TestTree> node) {
        UiModel.TestTree data = node.data();
        if (data == null) {
            return text(node.label()).fg(DIM);
        }
        if (data instanceof UiModel.TestSuite suite) {
            return text(node.label()).fg(colorFor(suite.status())).bold();
        }
        if (data instanceof UiModel.TestClass clazz) {
            return text(node.label()).fg(colorFor(clazz.status())).bold();
        }
        if (data instanceof UiModel.TestMethod method) {
            Color color = colorFor(method.status());
            // If this is the failure message child (label starts with ↳), render dimmer
            if (node.label() != null && node.label().startsWith("\u21B3 ")) {
                return text(node.label()).fg(DIM);
            }
            return text(node.label()).fg(color);
        }
        return text(node.label());
    }

    private static String labelForSuite(UiModel.TestSuite suite) {
        return statusIcon(suite.status()) + " " + suite.name();
    }

    private static String labelForClass(UiModel.TestClass clazz) {
        return statusIcon(clazz.status()) + " " + clazz.name();
    }

    private static String keyFor(UiModel.TestTree node) {
        if (node instanceof UiModel.TestSuite s) {
            return s.name();
        }
        if (node instanceof UiModel.TestClass c) {
            return c.name();
        }
        if (node instanceof UiModel.TestMethod m) {
            return m.name();
        }
        return "";
    }

    private static String labelForMethod(UiModel.TestMethod method) {
        String label = method.displayName() != null && !method.displayName().isEmpty()
                ? method.displayName()
                : method.name();
        return statusIcon(method.status()) + " " + label;
    }

    private static Color colorFor(UiModel.Status s) {
        return switch (s) {
            case PASSED -> GREEN;
            case FAILED -> RED;
            case SKIPPED -> YELLOW;
            case RUNNING -> CYAN;
            case PENDING -> DIM;
        };
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

    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

    private void snapshotState() {
        expandedKeys.clear();
        selectedKey = null;
        var selected = tree.selectedNode();
        if (selected != null) {
            selectedKey = keyFor(selected.data());
        }
        if (rootNode != null) {
            var stack = new ArrayList<TreeNode<UiModel.TestTree>>();
            stack.add(rootNode);
            while (!stack.isEmpty()) {
                var n = stack.remove(stack.size() - 1);
                if (n.isExpanded()) {
                    expandedKeys.add(keyFor(n.data()));
                }
                for (var ch : n.children()) {
                    stack.add(ch);
                }
            }
        }
    }

    private void reapplyExpansion(TreeNode<UiModel.TestTree> node) {
        if (node.data() != null && expandedKeys.contains(keyFor(node.data()))) {
            node.expanded(true);
        }
        for (var ch : node.children()) {
            reapplyExpansion(ch);
        }
    }

    private void reapplySelection() {
        if (selectedKey == null || rootNode == null) return;
        // linearize visible nodes by expansion
        var flat = new ArrayList<TreeNode<UiModel.TestTree>>();
        flattenVisible(rootNode, flat);
        for (int i = 0; i < flat.size(); i++) {
            var n = flat.get(i);
            if (n.data() != null && selectedKey.equals(keyFor(n.data()))) {
                tree.selected(i);
                break;
            }
        }
    }

    private void flattenVisible(TreeNode<UiModel.TestTree> node, List<TreeNode<UiModel.TestTree>> out) {
        out.add(node);
        if (node.isExpanded()) {
            for (var ch : node.children()) {
                flattenVisible(ch, out);
            }
        }
    }
}
