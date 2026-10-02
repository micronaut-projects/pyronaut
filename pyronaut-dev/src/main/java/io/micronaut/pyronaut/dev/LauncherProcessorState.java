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

import io.micronaut.core.annotation.Nullable;
import io.micronaut.dev.compile.CompilationRequest;
import io.micronaut.dev.compile.CompilationResult;
import io.micronaut.dev.compile.SourceCompiler;
import io.micronaut.dev.compile.SourceKind;
import io.micronaut.openapi.visitor.Utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Releases the static state of the annotation processors the launcher's own class loader holds after each
 * compilation of the development runtime.
 *
 * <p>The compiler loads the processor path in a loader whose parent is the launcher's loader, so a processor the
 * launcher already holds runs from the launcher's copy, for the life of the process. The native {@code pyronaut-dev}
 * image holds the processors it ships, and so does any JVM launch that has them on its class path. The OpenAPI
 * visitor keeps the endpoints and constructors it saw in static fields. Each compilation added to them, and they
 * held the elements of every compilation, with their javac and GraalPy contexts. They are cleared as a new process
 * would start them.</p>
 */
final class LauncherProcessorState {

    private static final String OPENAPI_UTILS = "io.micronaut.openapi.visitor.Utils";

    private LauncherProcessorState() {
    }

    /**
     * The compilers, each releasing the launcher's processors' static state after a compilation, when the launcher
     * holds such a processor.
     *
     * @param compilers The compilers by language
     * @return The compilers to use
     */
    static Map<SourceKind, SourceCompiler> releasingAfterCompilation(Map<SourceKind, SourceCompiler> compilers) {
        return releasingAfterCompilation(compilers, release(LauncherProcessorState.class.getClassLoader()));
    }

    /**
     * The compilers, each running the release after a compilation.
     *
     * @param compilers The compilers by language
     * @param release What releases the processors' state, null when the launcher holds no such processor
     * @return The compilers to use
     */
    static Map<SourceKind, SourceCompiler> releasingAfterCompilation(Map<SourceKind, SourceCompiler> compilers, @Nullable Runnable release) {
        if (release == null) {
            return compilers;
        }
        Map<SourceKind, SourceCompiler> releasing = new LinkedHashMap<>();
        // a compiler handling several languages is one compiler, wrapped once
        Map<SourceCompiler, SourceCompiler> wrapped = new IdentityHashMap<>();
        compilers.forEach((kind, compiler) -> releasing.put(kind, wrapped.computeIfAbsent(compiler, delegate -> new Releasing(delegate, release))));
        return releasing;
    }

    /**
     * What clears the OpenAPI visitor's static state when the given loader holds the visitor.
     *
     * @param launcher The launcher's class loader
     * @return The release, null when the loader does not hold the visitor
     */
    static @Nullable Runnable release(ClassLoader launcher) {
        if (isNativeImageRuntime()) {
            // the image holds the visitor, and is not set up for a reflective lookup of it
            return Utils::clean;
        }
        // on the JVM, the launcher's class path holds micronaut-openapi only when it was put there
        try {
            MethodHandle clean = MethodHandles.publicLookup().findStatic(
                Class.forName(OPENAPI_UTILS, false, launcher), "clean", MethodType.methodType(void.class));
            return () -> {
                try {
                    clean.invokeExact();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            };
        } catch (ClassNotFoundException | LinkageError | NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static boolean isNativeImageRuntime() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /**
     * A compiler that releases the processors' static state after each compilation.
     *
     * @param delegate The compiler
     * @param release The release
     */
    private record Releasing(SourceCompiler delegate, Runnable release) implements SourceCompiler {

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
                release.run();
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
