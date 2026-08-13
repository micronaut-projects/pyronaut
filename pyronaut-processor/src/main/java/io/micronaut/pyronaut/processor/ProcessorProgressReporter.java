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

import io.micronaut.python.compiler.PyronautCompiler;

import java.io.PrintStream;
import java.nio.file.Path;

final class ProcessorProgressReporter implements AutoCloseable {

    private static final char[] SPINNER_FRAMES = {'|', '/', '-', '\\'};

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;

    private volatile boolean spinning;
    private volatile String spinnerMessage;
    private volatile int frameIndex;
    private Thread spinnerThread;
    private IncrementalPlan incrementalPlan;

    ProcessorProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = mode == ProgressMode.ON || (mode == ProgressMode.AUTO && tty);
    }

    void startPass(String passName, boolean incremental) {
        startPass(passName, -1L, incremental);
    }

    static ProcessorProgressReporter create(String mode) {
        return new ProcessorProgressReporter(System.err, ProgressMode.fromCliValue(mode), System.console() != null);
    }

    void startPass(String passName, long sourceCount, boolean incremental) {
        if (!enabled) {
            return;
        }
        incrementalPlan = null;
        String action = incremental ? "Checking" : "Processing";
        String message = sourceCount < 0
            ? action + " " + passName + " sources"
            : action + " " + passName + " sources (" + sourceCount + " files)";
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
        if (incrementalPlan != null && incrementalPlan.passName().equals(passName)) {
            PyronautCompiler.IncrementalCompilationPlan plan = incrementalPlan.plan();
            if (plan.upToDate()) {
                output.println("Skipped " + passName + " sources (" + sourceCount
                    + " files checked, incremental state up to date)");
            } else if (plan.fullRebuild()) {
                output.println("Processed " + passName + " sources (" + sourceCount
                    + " files, full rebuild)");
            } else {
                output.println("Processed " + passName + " sources (" + plan.sources().size()
                    + " of " + sourceCount + " files recompiled incrementally)");
            }
            incrementalPlan = null;
            return;
        }
        output.println("Processed " + passName + " sources (" + sourceCount + " files)");
    }

    void incrementalPlan(String passName,
                         Path projectRoot,
                         long sourceCount,
                         PyronautCompiler.IncrementalCompilationPlan plan) {
        if (!enabled) {
            return;
        }
        clearInteractiveLine();
        incrementalPlan = new IncrementalPlan(passName, plan);
        if (plan.upToDate()) {
            return;
        }
        if (plan.fullRebuild()) {
            output.println("Full rebuild selected for " + passName + " sources ("
                + sourceCount + " files)");
            return;
        }
        output.println("Incrementally compiling " + passName + " sources ("
            + plan.sources().size() + " of " + sourceCount + " files):");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        for (Path source : plan.sources()) {
            Path normalizedSource = source.toAbsolutePath().normalize();
            Path display = normalizedSource.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedSource)
                : normalizedSource;
            output.println("  - " + display);
        }
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

    private record IncrementalPlan(String passName,
                                   PyronautCompiler.IncrementalCompilationPlan plan) {
    }
}
