/*
 * Copyright 2026 original authors
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
package io.micronaut.pyronaut.fixture.crema;

import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.io.buffer.ByteBuffer;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Fixture library that is not a provided artifact of the native launchers, so
 * {@code pyronaut-dev} loads and interprets it at runtime (Crema).
 *
 * <p>Its bytecode reads {@link ByteArrayBufferFactory#INSTANCE}, a static final
 * field of a class compiled into the image. AOT code constant-folds such fields,
 * and Crema could not resolve a folded field that the image did not preserve:
 * the VM aborted with "Cannot load undefined field" (micronaut-langchain4j's
 * {@code MicronautLangChain4jHttpClient} hit this on its first request).</p>
 */
public final class ProvidedStaticFieldAccess {
    private ProvidedStaticFieldAccess() {
    }

    /**
     * Copies text through the shared byte array buffer factory.
     *
     * @param text the text
     * @return the text read back from a buffer allocated by the factory
     */
    public static String roundTrip(String text) {
        // A lambda like MicronautLangChain4jHttpClient.byteBodyFactory, so the
        // getstatic runs in an interpreted synthetic method.
        Function<byte[], ByteBuffer<?>> wrap = bytes -> ByteArrayBufferFactory.INSTANCE.wrap(bytes);
        return wrap.apply(text.getBytes(StandardCharsets.UTF_8)).toString(StandardCharsets.UTF_8);
    }
}
