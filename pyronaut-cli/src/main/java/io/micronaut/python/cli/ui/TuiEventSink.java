package io.micronaut.python.cli.ui;

import io.micronaut.python.cli.protocol.ProtocolConstants;

import java.util.List;
import java.util.function.Supplier;

public final class TuiEventSink implements ProtocolEventSink {

    private final UiController controller;
    private ModeCoordinator coordinator;
    private Supplier<Mode> desiredModeSupplier;

    public TuiEventSink(UiController controller) {
        this.controller = controller;
    }

    public void setCoordinator(ModeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setDesiredModeSupplier(Supplier<Mode> supplier) {
        this.desiredModeSupplier = supplier;
    }

    private boolean isDesired(Mode mode) {
        return desiredModeSupplier == null || desiredModeSupplier.get() == mode;
    }

    @Override
    public void onAppStarted() {
        if (!isDesired(Mode.RUN)) {
            return;
        }
        controller.setRunning();
        controller.clearEndpoints();
    }

    @Override
    public void onAppStopped() {
        if (coordinator != null) {
            coordinator.signalRunStopped();
        }
        if (!isDesired(Mode.RUN)) {
            return;
        }
        if (!(controller.getModel() instanceof UiModel.Compiling)) {
            controller.setStopped();
        }
    }

    @Override
    public void onServerUri(String uri) {
        if (!isDesired(Mode.RUN)) {
            return;
        }
        controller.setUrl(uri);
    }

    @Override
    public void onEndpointList(List<String> endpoints) {
        if (!isDesired(Mode.RUN)) {
            return;
        }
        controller.setEndpoints(endpoints);
    }

    @Override
    public void onAppLog(byte level, String message) {
        UiModel.Severity sev = switch (level) {
            case ProtocolConstants.LOG_WARN -> UiModel.Severity.WARNING;
            case ProtocolConstants.LOG_ERROR, ProtocolConstants.LOG_FATAL -> UiModel.Severity.ERROR;
            default -> UiModel.Severity.INFO;
        };
        controller.notify(message, sev);
    }

    @Override
    public void onTestRunStarted() {
        if (!isDesired(Mode.TEST)) {
            return;
        }
        controller.startTesting();
        controller.notify("Test run started", UiModel.Severity.INFO);
    }

    @Override
    public void onTestRunFinished(long passed, long failed, long skipped, long running, long pending) {
        if (!isDesired(Mode.TEST)) {
            return;
        }
        controller.updateTestSummary(passed, failed, skipped, running, pending);
        if (coordinator != null) {
            coordinator.signalTestFinished();
        }
        controller.stopTesting();
        controller.notify(
            "Tests completed: " + passed + " passed, " + failed + " failed, " + skipped + " skipped",
            failed > 0 ? UiModel.Severity.ERROR : UiModel.Severity.SUCCESS
        );
    }

    @Override
    public void onTestNode(int id, int parentId, byte kind, String name, String displayName) {
        if (!isDesired(Mode.TEST)) {
            return;
        }
        if (!controller.isTesting()) {
            controller.startTesting();
        }
        controller.addTestNode(id, parentId, kind, name, displayName);
    }

    @Override
    public void onTestNodeStarted(int id) {
        if (!isDesired(Mode.TEST)) {
            return;
        }
        if (!controller.isTesting()) {
            controller.startTesting();
        }
        controller.nodeStarted(id);
    }

    @Override
    public void onTestNodeFinished(int id, byte status, String message) {
        if (!isDesired(Mode.TEST)) {
            return;
        }
        if (!controller.isTesting()) {
            controller.startTesting();
        }
        controller.nodeFinished(id, mapStatus(status), message);
    }

    @Override
    public void onTestLog(int id, String message) {
        // Always allow test logs so users can see activity during switches
        controller.addTestLog(id, message);
    }

    private static UiModel.Status mapStatus(byte code) {
        return switch (code) {
            case ProtocolConstants.TEST_PASSED -> UiModel.Status.PASSED;
            case ProtocolConstants.TEST_FAILED -> UiModel.Status.FAILED;
            case ProtocolConstants.TEST_SKIPPED -> UiModel.Status.SKIPPED;
            case ProtocolConstants.TEST_RUNNING -> UiModel.Status.RUNNING;
            case ProtocolConstants.TEST_PENDING -> UiModel.Status.PENDING;
            default -> UiModel.Status.PENDING;
        };
    }
}
