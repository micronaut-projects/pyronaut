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
package io.micronaut.test.pytest.execution;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Executes pytest test functions behind a Java boundary so foreign exceptions
 * are converted before they reach pytest internals.
 */
public final class PytestFunctionInvoker {
    private PytestFunctionInvoker() {
    }

    public static Result call(Value callable) {
        try {
            callable.executeVoid();
            return Result.success();
        } catch (Throwable t) {
            return Result.failure(t);
        }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * Interop-friendly test call result.
     */
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static final class Result {
        public final boolean success;
        public final String message;
        public final String exceptionClass;
        public final String stack;

        private Result(boolean success, Throwable error) {
            this.success = success;
            if (error == null) {
                this.message = null;
                this.exceptionClass = null;
                this.stack = null;
            } else {
                Throwable rendered = unwrapHostException(error);
                this.message = rendered.getMessage();
                this.exceptionClass = rendered.getClass().getName();
                this.stack = stackTraceToString(rendered);
            }
        }

        private static Throwable unwrapHostException(Throwable error) {
            if (error instanceof PolyglotException polyglotException && polyglotException.isHostException()) {
                return polyglotException.asHostException();
            }
            return error;
        }

        static Result success() {
            return new Result(true, null);
        }

        static Result failure(Throwable t) {
            return new Result(false, t);
        }
    }
}
