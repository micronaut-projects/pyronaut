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
package io.micronaut.pyronaut.install;

import java.io.PrintStream;

final class InstallProgressReporter implements AutoCloseable {

    private static final char[] SPINNER_FRAMES = {'|', '/', '-', '\\'};

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;

    private volatile boolean spinning;
    private volatile String spinnerMessage;
    private volatile int frameIndex;
    private Thread spinnerThread;

    static InstallProgressReporter create(String mode) {
        return new InstallProgressReporter(System.err, ProgressMode.fromCliValue(mode), System.console() != null);
    }

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = mode == ProgressMode.ON || (mode == ProgressMode.AUTO && tty);
    }

    void cacheHit() {
        if (!enabled) {
            return;
        }
        output.println("Dependency manifests are up to date (cache hit)");
    }

    void startScope(InstallScope scope) {
        if (!enabled) {
            return;
        }
        String message = "Resolving " + scope.cliValue() + " dependencies";
        if (!interactive) {
            output.println(message + "...");
            return;
        }
        stopSpinner();
        spinnerMessage = message;
        spinning = true;
        spinnerThread = Thread.ofVirtual().name("pyronaut-install-spinner").start(() -> {
            while (spinning) {
                output.print("\r" + spinnerMessage + " " + SPINNER_FRAMES[frameIndex % SPINNER_FRAMES.length]);
                output.flush();
                frameIndex++;
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    void finishScope(InstallScope scope, int artifactCount) {
        if (!enabled) {
            return;
        }
        if (interactive) {
            stopSpinner();
            output.print("\r");
            output.flush();
        }
        output.println("Resolved " + scope.cliValue() + " dependencies (" + artifactCount + " artifacts)");
    }

    @Override
    public void close() {
        stopSpinner();
    }

    private void stopSpinner() {
        spinning = false;
        if (spinnerThread == null) {
            return;
        }
        try {
            spinnerThread.join(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        spinnerThread = null;
    }

    enum ProgressMode {
        AUTO,
        ON,
        OFF;

        static ProgressMode fromCliValue(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Invalid --progress value ''. Expected one of: auto|on|off");
            }
            return switch (value.trim().toLowerCase()) {
                case "auto" -> AUTO;
                case "on" -> ON;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("Invalid --progress value '" + value + "'. Expected one of: auto|on|off");
            };
        }
    }
}
