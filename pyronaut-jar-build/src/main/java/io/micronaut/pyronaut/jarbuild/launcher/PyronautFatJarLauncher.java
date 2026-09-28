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
package io.micronaut.pyronaut.jarbuild.launcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Bootstrap entry point stored at the root of a Pyronaut FAT JAR.
 */
public final class PyronautFatJarLauncher {
    /** System property indicating that the runtime is executing from an indexed FAT JAR. */
    public static final String PACKAGED_JAR_PROPERTY = "pyronaut.packaged.jar";

    private PyronautFatJarLauncher() {
    }

    /**
     * Launches the indexed application.
     *
     * @param args application arguments
     * @throws Throwable when application startup fails
     */
    public static void main(String[] args) throws Throwable {
        Path archive = Path.of(PyronautFatJarLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // The archive is never closed. The target main may start a server (or
        // other non-daemon threads) and return immediately, and application
        // shutdown hooks run concurrently with any hook of ours while still
        // loading classes lazily from the archive. The JVM releases it on exit.
        IndexedJarClassLoader classLoader = new IndexedJarClassLoader(archive);
        try {
            System.setProperty(PACKAGED_JAR_PROPERTY, Boolean.TRUE.toString());
            thread.setContextClassLoader(classLoader);
            Class<?> target = Class.forName(classLoader.mainClass(), true, classLoader);
            Method main = target.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
