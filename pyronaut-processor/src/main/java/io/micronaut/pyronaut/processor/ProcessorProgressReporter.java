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
package io.micronaut.pyronaut.processor;

import java.io.PrintStream;

final class ProcessorProgressReporter implements AutoCloseable {

    private static final char[] SPINNER_FRAMES = {'|', '/', '-', '\\'};

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;

    private volatile boolean spinning;
    private volatile String spinnerMessage;
    private volatile int frameIndex;
    private Thread spinnerThread;

    ProcessorProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = mode == ProgressMode.ON || (mode == ProgressMode.AUTO && tty);
    }

    static ProcessorProgressReporter create(String mode) {
        return new ProcessorProgressReporter(System.err, ProgressMode.fromCliValue(mode), System.console() != null);
    }

    void startPass(String passName, long sourceCount) {
        if (!enabled) {
            return;
        }
        String message = "Processing " + passName + " sources (" + sourceCount + " files)";
        if (!interactive) {
            output.println(message + "...");
            return;
        }
        stopSpinner();
        spinnerMessage = message;
        spinning = true;
        spinnerThread = Thread.ofVirtual().name("pyronaut-processor-spinner").start(() -> {
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

    void finishPass(String passName, long sourceCount) {
        if (!enabled) {
            return;
        }
        clearInteractiveLine();
        output.println("Processed " + passName + " sources (" + sourceCount + " files)");
    }

    void cacheHit(String passName, long sourceCount) {
        if (!enabled) {
            return;
        }
        clearInteractiveLine();
        output.println("Skipped " + passName + " sources (" + sourceCount + " files, cache hit)");
    }

    void cacheBypass(String passName, long sourceCount) {
        if (!enabled) {
            return;
        }
        clearInteractiveLine();
        output.println("Processing " + passName + " sources (" + sourceCount + " files, cache bypass)");
    }

    void noSources(String passName) {
        if (!enabled) {
            return;
        }
        clearInteractiveLine();
        output.println("Skipped " + passName + " sources (0 files, no processable sources)");
    }

    void complete(String mainStatus, String testStatus) {
        if (!enabled) {
            return;
        }
        output.println("Processing completed (main: " + mainStatus + ", test: " + testStatus + ")");
    }

    @Override
    public void close() {
        stopSpinner();
    }

    private void clearInteractiveLine() {
        if (!interactive) {
            return;
        }
        stopSpinner();
        output.print("\r");
        output.flush();
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
