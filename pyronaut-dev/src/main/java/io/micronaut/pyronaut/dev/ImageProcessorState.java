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
package io.micronaut.pyronaut.dev;

import io.micronaut.dev.compile.CompilationRequest;
import io.micronaut.dev.compile.CompilationResult;
import io.micronaut.dev.compile.SourceCompiler;
import io.micronaut.dev.compile.SourceKind;
import io.micronaut.openapi.visitor.Utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Releases what the annotation processors the native {@code pyronaut-dev} image holds keep in static fields after
 * each compilation of the development runtime.
 *
 * <p>On the JVM, javac loads the processor path in a class loader of its own for every compilation, so a processor's
 * static state goes with it. The image runs the processors it holds instead, from its own classes, once per
 * compilation, for the life of the process, and the OpenAPI visitor keeps the endpoints it saw in static fields:
 * each compilation added to them, and they held the elements of every compilation, with their javac and GraalPy
 * contexts. They are cleared as a new JVM would start them.</p>
 */
final class ImageProcessorState {

    private ImageProcessorState() {
    }

    /**
     * The compilers, each releasing the processors' static state after a compilation when they run from the image.
     *
     * @param compilers The compilers by language
     * @return The compilers to use
     */
    static Map<SourceKind, SourceCompiler> releasingAfterCompilation(Map<SourceKind, SourceCompiler> compilers) {
        return releasingAfterCompilation(compilers, isNativeImageRuntime());
    }

    /**
     * The compilers, each releasing the processors' static state after a compilation when they run from the image.
     *
     * @param compilers The compilers by language
     * @param fromImage Whether the processors run from the image
     * @return The compilers to use
     */
    static Map<SourceKind, SourceCompiler> releasingAfterCompilation(Map<SourceKind, SourceCompiler> compilers, boolean fromImage) {
        if (!fromImage) {
            return compilers;
        }
        Map<SourceKind, SourceCompiler> releasing = new LinkedHashMap<>();
        // a compiler handling several languages is one compiler, wrapped once
        Map<SourceCompiler, SourceCompiler> wrapped = new java.util.IdentityHashMap<>();
        compilers.forEach((kind, compiler) -> releasing.put(kind, wrapped.computeIfAbsent(compiler, Releasing::new)));
        return releasing;
    }

    /**
     * Clears the static state of the processors the image holds.
     */
    static void release() {
        Utils.clean();
    }

    private static boolean isNativeImageRuntime() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /**
     * A compiler that releases the processors' static state after each compilation.
     *
     * @param delegate The compiler
     */
    private record Releasing(SourceCompiler delegate) implements SourceCompiler {

        @Override
        public Set<SourceKind> kinds() {
            return delegate.kinds();
        }

        @Override
        public Set<SourceKind> jointKinds() {
            return delegate.jointKinds();
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public CompilationResult compile(CompilationRequest request) {
            try {
                return delegate.compile(request);
            } finally {
                release();
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
