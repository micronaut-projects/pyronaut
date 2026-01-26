package io.micronaut.python.cli.ui;

import java.util.List;

public interface ProtocolEventSink {
    void onAppStarted();
    void onAppStopped();
    void onServerUri(String uri);
    void onEndpointList(List<String> endpoints);
    void onAppLog(byte level, String message);
    void onTestRunStarted();
    void onTestRunFinished(long passed, long failed, long skipped, long running, long pending);
    void onTestNode(int id, int parentId, byte kind, String name, String displayName);
    void onTestNodeStarted(int id);
    void onTestNodeFinished(int id, byte status, String message);
    void onTestLog(int id, String message);
}
