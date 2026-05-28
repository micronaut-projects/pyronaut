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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestFunctionInvokerTest {

    @Test
    void convertsForeignExceptionToResult() {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            context.eval("python", """
                import java

                IllegalStateException = java.type("java.lang.IllegalStateException")

                def test_function():
                    raise IllegalStateException("boom")
                """);
            Value testFunction = context.getBindings("python").getMember("test_function");

            PytestFunctionInvoker.Result result = PytestFunctionInvoker.call(testFunction);

            assertFalse(result.success);
            assertTrue(result.stack.contains("java.lang.IllegalStateException"));
            assertTrue(result.stack.contains("boom"));
        }
    }

    @Test
    void applicationContextWrapperConvertsLifecycleFailure() throws Exception {
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            String testSupport = new String(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(
                    "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py"
                )
            ).readAllBytes(), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("python", testSupport, "pyronaut-test.py").build());
            context.getBindings("python").putMember("failingContext", new FailingContext());

            context.eval("python", """
                wrapper = ApplicationContextWrapper(failingContext)
                try:
                    wrapper.stop()
                except RuntimeError as e:
                    lifecycle_error = str(e)
                """);

            String error = context.getBindings("python").getMember("lifecycle_error").asString();
            assertTrue(error.contains("Micronaut application context stop failed"));
            assertTrue(error.contains("java.lang.IllegalStateException"));
            assertTrue(error.contains("boom"));
        }
    }

    public static final class FailingContext {
        public void stop() {
            throw new IllegalStateException("boom");
        }
    }
}
