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
package io.micronaut.test.pytest;

import io.micronaut.core.annotation.Internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders Java failures crossing the pytest boundary without hiding the causal chain.
 */
@Internal
public final class FailureDiagnostics {
    private static final Pattern INTERNAL_STACK_FRAME = Pattern.compile(
        "^(com\\.oracle\\.truffle\\.|com\\.oracle\\.graal\\.python\\.|org\\.graalvm\\.polyglot\\.|org\\.graalvm\\.python\\.embedding\\.|java\\.base/).*"
    );

    private FailureDiagnostics() {
    }

    /**
     * Render a failure with all non-internal stack frames and causes.
     *
     * @param throwable The failure
     * @return Diagnostic text suitable for pytest, events, and HTML reports
     */
    public static String render(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(1024);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowable(builder, throwable, "", seen);
        return builder.toString();
    }

    private static void appendThrowable(StringBuilder builder, Throwable throwable, String prefix, Set<Throwable> seen) {
        if (!seen.add(throwable)) {
            appendLine(builder, prefix + "[CIRCULAR REFERENCE: " + throwable + "]");
            return;
        }
        appendLine(builder, prefix + throwable);
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (!isInternal(element)) {
                appendLine(builder, "\tat " + element);
            }
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            appendThrowable(builder, suppressed, "Suppressed: ", seen);
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            appendThrowable(builder, cause, "Caused by: ", seen);
        }
    }

    private static boolean isInternal(StackTraceElement element) {
        return "java.base".equals(element.getModuleName())
            || INTERNAL_STACK_FRAME.matcher(element.getClassName()).matches();
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append(System.lineSeparator());
        }
        builder.append(line);
    }
}
