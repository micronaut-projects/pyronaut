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

import dev.tamboui.annotations.bindings.OnAction;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.MarkupParser;
import dev.tamboui.toolkit.component.Component;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.tui.event.Event;
import dev.tamboui.widgets.tabs.TabsState;
import dev.tamboui.widgets.wavetext.WaveTextState;
import io.micronaut.python.cli.ui.Mode;
import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.*;
import static dev.tamboui.toolkit.elements.ListElement.ScrollBarPolicy.AS_NEEDED;

/**
 * Root dashboard view composed with Toolkit DSL, styled via TCSS classes.
 * 80x24-first layout:
 * - Left: Status, Endpoints, Tests, Notifications
 * - Right: Activity (compile/log tail)
 * Styling is delegated to pyronaut-ui.tcss using classes (no inline colors).
 */
public final class RootView extends Component<RootView> {
    public static final String SAVE_LOGS = "saveLogs";
    public static final String SELECT_TAB_APP = "tabApp";
    public static final String SELECT_TAB_LOGS = "tabLogs";
    public static final String SELECT_TAB_RESOURCES = "tabResources";
    public static final String FOCUS_TREE = "focusTree";
    public static final String FOCUS_OUTPUT = "focusOutput";

    private final UiController controller;
    private final WaveTextState stateWave = new WaveTextState();
    private final TestTreeView testTreeView = new TestTreeView(null);
    private final ListElement<?> testOutputList = list()
            .scrollbar(AS_NEEDED)
            .displayOnly()
            .stickyScroll();
    private final ListElement<?> activityList = list()
            .scrollbar(AS_NEEDED)
            .displayOnly()
            .stickyScroll();
    private final ListElement<?> notificationsList = list()
            .scrollbar(AS_NEEDED)
            .displayOnly()
            .stickyScroll();
    private final ListElement<?> testResourcesSummaryList = list()
            .scrollbar(AS_NEEDED)
            .displayOnly()
            .stickyScroll();
    private final ListElement<?> testResourcesLogsList = list()
            .scrollbar(AS_NEEDED)
            .displayOnly()
            .stickyScroll();

    private final TabsState tabsState = new TabsState(0);
    private Mode mode = Mode.RUN;

    private UiModel.TestTree lastTreeRef;

    private enum UiState {
        RUNNING, COMPILING, TESTING, IDLE
    }

    public RootView(UiController controller) {
        this.controller = controller;
        id("root");
    }

    public Element render() {
        // Header (compact status bar)
        var headerPanel = header();

        // Left column: focusable panels
        var leftChildren = new ArrayList<Element>();
        if (controller.isTesting() || controller.getLastTestSummary().isPresent() || mode == Mode.TEST) {
            leftChildren.add(testsPanel());
        }
        leftChildren.add(notificationsPanel());
        var leftColumn = column(leftChildren.toArray(new Element[0]));

        // Right column: activity panel (focusable, takes the rest)
        var rightPanel = activityPanel();

        // Main row
        var mainRow = row(
                leftColumn,
                spacer(2),
                rightPanel
        ).addClass("main-row");

        // Footer (one-line hint bar)
        var footerPanel = footer();

        // Render selected tab content robustly with default
        Element tabContent;
        Integer selected = tabsState.selected();
        int tabIndex = selected != null ? selected : 0;
        switch (tabIndex) {
            case 0 -> {
                if (controller.isTesting() || controller.getLastTestSummary().isPresent() || mode == Mode.TEST) {
                    tabContent = testsPanel();
                } else {
                    tabContent = grid().gridAreas("endpoints", "notifications")
                            .area("endpoints", endpointsPanel())
                            .area("notifications", notificationsPanel())
                            .addClass("main-row");
                }
            }
            case 2 -> tabContent = testResourcesPanel();
            default -> tabContent = panel("Activity", activityList).addClass("activity");
        }
        return column(
                headerPanel,
                tabContent,
                footerPanel
        );

    }

    // ============ Header / Footer ============

    private Panel header() {
        UiState state = computeState();
        StyledElement<?> appStatus;
        if (state == UiState.RUNNING) {
            appStatus = row(stateWidget(state), spacer(4), url().addClass("fit"));
        } else {
            appStatus = stateWidget(state);
        }
        appStatus = appStatus.addClass("app-status");

        var keyHints = row(
                text("[F1] App").addClass("primary"),
                text(" | ").addClass("dim"),
                text("[F2] Logs").addClass("primary"),
                text(" | ").addClass("dim"),
                text("[F3] Test Resources").addClass("primary")
        ).addClass("header-hints");
        return panel(
                column(
                        keyHints,
                        dock()
                                .left(
                                    row(
                                        text("\uD83D\uDD25 Pyronaut").addClass("header-title")
                                    ).addClass("fit")
                                )
                                .right(appStatus)
                                .fill()
                                .addClass("header-row")
                )
        ).addClass("header");
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.RUN : mode;
    }

    private Panel footer() {
        return panel(
                row(
                        text("[Tab]").addClass("primary"), text(" Focus  ").addClass("dim"),
                        text("[Ctrl+T]").addClass("primary"), text(" Test  ").addClass("dim"),
                        text("[Ctrl+R]").addClass("primary"), text(" Run  ").addClass("dim"),
                        text("| ").addClass("dim"), text("Mode: ").addClass("dim"), text(mode == Mode.TEST ? "Test" : "Run"),
                        text("  "),
                        text("[Ctrl+C]").addClass("error"), text(" Quit")
                ).addClass("footer-row")
        ).addClass("status");
    }

    // ============ Left column panels ============

    private StyledElement<?> url() {
        var url = controller.getUrl();
        if (url != null) {
            return text(url).style(Style.EMPTY.hyperlink(url)).addClass("chip").addClass("primary");
        }
        return text("(not running)").addClass("dim");
    }

    private void addFileUpdates(List<StyledElement<?>> items) {
        var updated = controller.getUpdatedFiles();
        if (!updated.isEmpty()) {
            items.add(text("Files changed:"));
            int max = Math.min(6, updated.size());
            for (int i = 0; i < max; i++) {
                var update = updated.get(i);
                var prefix = switch (update.type()) {
                    case ADDED -> "[green][[+]][/] ";
                    case MODIFIED -> "[yellow][[~]][/] ";
                    case DELETED -> "[gray][[-]][/] ";
                    case CHANGED -> "[red][[?]][/] ";
                    default -> "[red][[?]][/] ";
                };
                items.add(richText(MarkupParser.parse("  " + prefix + update.path())).addClass("updated-files"));
            }
        }
    }

    private Panel endpointsPanel() {
        var eps = controller.getEndpoints();
        List<Element> elems = new ArrayList<>();
        if (eps.isEmpty()) {
            elems.add(text("No endpoints discovered").addClass("dim"));
            if (controller.isRunning()) {
                elems.add(markupText("   Check if you have [code][link=https://docs.micronaut.io/latest/guide/#management]io.micronaut:micronaut-management[/][/] in your [code]pyproject.toml[/] file and that configuration enables endpoints.").addClass("dim").addClass("endpoint"));
            }
        } else {
            elems.add(text("Discovered Endpoints").addClass("info"));
            for (String ep : eps) {
                elems.add(text("  • " + ep));
            }
        }
        return panel("Endpoints", column(elems.toArray(Element[]::new)))
                .addClass("endpoints")
                .focusable();
    }

    private Panel testsPanel() {
        var currentTree = controller.getTestTree();
        if (currentTree != lastTreeRef) {
            testTreeView.update(currentTree);
            lastTreeRef = currentTree;
        }

        // Populate Test Output list on each render based on current selection (persist after run)
        var sel = testTreeView.selectedNode();
        List<String> logs;
        if (sel == null) {
            logs = List.of();
        } else if (sel instanceof UiModel.TestMethod m) {
            logs = m.logs();
        } else if (sel instanceof UiModel.TestClass c) {
            logs = c.methods().stream().flatMap(mm -> mm.logs().stream()).toList();
        } else if (sel instanceof UiModel.TestSuite s) {
            logs = s.classes().stream().flatMap(cc -> cc.methods().stream()).flatMap(mm -> mm.logs().stream()).toList();
        } else {
            logs = List.of();
        }
        var rendered = logs.stream().map(l -> text(l).addClass("log-item")).toArray(StyledElement[]::new);
        testOutputList.elements(rendered);

        var summary = controller.getLastTestSummary();
        var stats = summary.map(RootView::fromSummary).orElseGet(() -> computeTestStats(controller.getTestTree()));
        var content = dock()
                .top(row(
                        text("✔ " + stats.passed).addClass("success"), text("passed"),
                        text("✖ " + stats.failed).addClass("error"), text("failed"),
                        text("⏭ " + stats.skipped).addClass("warning"), text("skipped"),
                        text("⟳ " + stats.running).addClass("info"), text("running"),
                        text("⏳ " + stats.pending).addClass("dim"), text("pending")
                ).addClass("stats-row"))
                .left(panel("Test Tree", testTreeView))
                .right(panel("Test Output", testOutputList)
        ).fill();

        return panel("Tests", content)
                .id("tests")
                .addClass("tests");
    }

    private Panel notificationsPanel() {
        var items = new ArrayList<StyledElement<?>>();
        addFileUpdates(items);
        var history = controller.getNotificationHistory();
        if (history.isEmpty() && items.isEmpty()) {
            notificationsList.elements(text("No notifications").addClass("dim"));
        } else {
            for (var n : history) {
                StyledElement<?> se = switch (n.severity()) {
                    case INFO -> text("ℹ " + n.message()).addClass("info");
                    case SUCCESS -> text("✔ " + n.message()).addClass("success");
                    case WARNING -> text("⚠ " + n.message()).addClass("warning");
                    case ERROR -> text("✖ " + n.message()).addClass("error");
                    default -> text(n.message());
                };
                items.add(se);
            }
            notificationsList.elements(items.toArray(new StyledElement[0]));
        }
        return panel("Notifications", notificationsList)
                .addClass("notifications")
                .focusable();
    }

    private Panel testResourcesPanel() {
        var snapshot = controller.getTestResourcesSnapshot();
        List<StyledElement<?>> rows = new ArrayList<>();

        rows.add(text("State: " + snapshot.status().name().toLowerCase()).addClass("info"));
        if (snapshot.healthMessage() != null && !snapshot.healthMessage().isBlank()) {
            rows.add(text("Health: " + snapshot.healthMessage()));
        }
        if (snapshot.message() != null && !snapshot.message().isBlank()) {
            rows.add(text("Message: " + snapshot.message()).addClass("warning"));
        }

        rows.add(text("Containers (" + snapshot.containers().size() + ")").addClass("primary"));
        if (snapshot.containers().isEmpty()) {
            rows.add(text("  none").addClass("dim"));
        } else {
            for (String container : snapshot.containers()) {
                rows.add(text("  • " + container));
            }
        }

        rows.add(text("Properties (" + snapshot.properties().size() + ")").addClass("primary"));
        if (snapshot.properties().isEmpty()) {
            rows.add(text("  none").addClass("dim"));
        } else {
            for (String property : snapshot.properties()) {
                rows.add(text("  • " + property));
            }
        }

        rows.add(text("Errors (" + snapshot.errors().size() + ")").addClass("primary"));
        if (snapshot.errors().isEmpty()) {
            rows.add(text("  none").addClass("dim"));
        } else {
            for (String error : snapshot.errors()) {
                rows.add(text("  • " + error).addClass("error"));
            }
        }

        testResourcesSummaryList.elements(rows.toArray(new StyledElement[0]));
        var logRows = controller.getTestResourcesLogLines().stream()
            .map(line -> text(line).addClass("log-item"))
            .toArray(StyledElement[]::new);
        testResourcesLogsList.elements(logRows);

        return panel(
            "Test Resources",
            dock()
                .left(panel("Summary", testResourcesSummaryList))
                .right(panel("Test Resources Logs", testResourcesLogsList))
                .fill()
        )
            .id("test-resources")
            .addClass("tests")
            .focusable();
    }

    // ============ Right column (Activity) ============

    private Panel activityPanel() {
        List<ActivityItem> items = new ArrayList<>();
        for (String log : new ArrayList<>(controller.getActivityLogLines())) {
            Color color = Color.GRAY;
            if (log.startsWith("[STDERR]") || containsAny(log, "[ERROR]", "ERROR", "FATAL")) {
                color = Color.RED;
            } else if (containsAny(log, "[WARN]", "WARN")) {
                color = Color.YELLOW;
            } else if (containsAny(log, "[INFO]", "INFO")) {
                color = Color.CYAN;
            }
            items.add(new ActivityItem(log, color));
        }
        activityList.data(items, this::activityItem);

        // Test output panel content depending on selection
        if (controller.isTesting()) {
            var sel = testTreeView.selectedNode();
            List<String> logs;
            if (sel == null) {
                logs = controller.getActivityLogLines();
            } else if (sel instanceof UiModel.TestMethod m) {
                logs = m.logs();
            } else if (sel instanceof UiModel.TestClass c) {
                logs = c.methods().stream().flatMap(mm -> mm.logs().stream()).toList();
            } else if (sel instanceof UiModel.TestSuite s) {
                logs = s.classes().stream().flatMap(cc -> cc.methods().stream()).flatMap(mm -> mm.logs().stream()).toList();
            } else {
                logs = List.of();
            }
            var rendered = logs.stream().map(l -> text(l).addClass("log-item")).toArray(StyledElement[]::new);
            testOutputList.elements(rendered);
        }
        return panel("Activity", activityList)
                .id("activity")
                .addClass("activity")
                .onMouseEvent(activityList::handleMouseEvent)
                .focusable();
    }

    private StyledElement<?> activityItem(ActivityItem item) {
        return text(item.content()).style(Style.EMPTY.fg(item.color())).addClass("log-item");
    }

    private static TestStats fromSummary(UiController.TestSummary s) {
        TestStats st = new TestStats();
        st.passed = s.passed();
        st.failed = s.failed();
        st.skipped = s.skipped();
        st.running = s.running();
        st.pending = s.pending();
        return st;
    }

    private static TestStats computeTestStats(UiModel.TestTree root) {
        TestStats st = new TestStats();
        if (root != null) {
            collectStats(root, st);
        }
        return st;
    }

    private static void collectStats(UiModel.TestTree node, TestStats st) {
        if (node instanceof UiModel.TestSuite s) {
            inc(st, s.status());
            for (var c : s.classes()) {
                collectStats(c, st);
            }
        } else if (node instanceof UiModel.TestClass c) {
            inc(st, c.status());
            for (var m : c.methods()) {
                collectStats(m, st);
            }
        } else if (node instanceof UiModel.TestMethod m) {
            inc(st, m.status());
        }
    }

    private static void inc(TestStats st, UiModel.Status status) {
        switch (status) {
            case PASSED -> st.passed++;
            case FAILED -> st.failed++;
            case SKIPPED -> st.skipped++;
            case RUNNING -> st.running++;
            case PENDING -> st.pending++;
            default -> {
            }
        }
    }

    private StyledElement<?> stateWidget(UiState state) {
        stateWave.advance();
        return switch (state) {
            case COMPILING -> waveText("Compiling...").state(stateWave).addClass(stateClassFor(state));
            case TESTING -> waveText("Running tests...").state(stateWave).addClass(stateClassFor(state));
            case RUNNING -> waveText("Running...").state(stateWave).addClass(stateClassFor(state));
            case IDLE -> waveText("Waiting for changes...").state(stateWave).addClass(stateClassFor(state));
            default -> waveText("Waiting...").state(stateWave).addClass(stateClassFor(state));
        };
    }

    @OnAction(SELECT_TAB_APP)
    void onTabApp(Event e) {
        tabsState.select(0);
    }

    @OnAction(SELECT_TAB_LOGS)
    void onTabLogs(Event e) {
        tabsState.select(1);
    }

    @OnAction(SELECT_TAB_RESOURCES)
    void onTabResources(Event e) {
        tabsState.select(2);
    }

    @OnAction(FOCUS_TREE)
    void onFocusTree(Event e) { /* no-op for now */ }

    @OnAction(FOCUS_OUTPUT)
    void onFocusOutput(Event e) { /* no-op for now */ }

    private UiState computeState() {
        if (controller.isCompiling()) {
            return UiState.COMPILING;
        }
        if (controller.isTesting()) {
            return UiState.TESTING;
        }
        if (controller.getUrl() != null) {
            return UiState.RUNNING;
        }

        return UiState.IDLE;
    }

    private static String stateClassFor(UiState state) {
        return switch (state) {
            case RUNNING -> "state-running";
            case COMPILING -> "state-compiling";
            case TESTING -> "state-testing";
            case IDLE -> "state-idle";
            default -> "state-idle";
        };
    }

    @OnAction(SAVE_LOGS)
    public void saveLogs(Event e) {
        try (var printer = new PrintWriter(Files.newOutputStream(Path.of("error.log")))) {
            controller.getActivityLogLines().forEach(printer::println);
            controller.clearLogs();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) {
                return true;
            }
        }
        return false;
    }

    // ---- Inner types (kept last to satisfy InnerTypeLast) ----
    private static final class TestStats {
        long passed;
        long failed;
        long skipped;
        long running;
        long pending;
    }

    private record ActivityItem(String content, Color color) {
    }
}
