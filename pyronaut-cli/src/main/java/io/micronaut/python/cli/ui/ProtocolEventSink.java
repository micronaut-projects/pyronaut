/*
 * Copyright 2017-2026 original authors
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
