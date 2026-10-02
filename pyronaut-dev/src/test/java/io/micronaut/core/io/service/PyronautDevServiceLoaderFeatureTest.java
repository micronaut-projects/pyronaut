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
package io.micronaut.core.io.service;

import org.graalvm.nativeimage.hosted.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PyronautDevServiceLoaderFeatureTest {
    private static final String JACKSON_ARRAY_SERIALIZERS = "tools.jackson.databind.ser.jdk.JDKArraySerializers";
    private static final String INITIALIZED_PROPERTY = "pyronaut.test.jackson.array.serializers.initialized";

    @Test
    void initializesJacksonArraySerializersWithApplicationClassLoader(@TempDir Path classesDirectory) throws Exception {
        // Compile the sentinel into an isolated loader, never shadow Jackson on the test classpath.
        Path sourceFile = classesDirectory.resolve("JDKArraySerializers.java");
        try (var source = getClass().getResourceAsStream("/jackson-initialization/JDKArraySerializers.java")) {
            Files.copy(source, sourceFile);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-proc:none", "-d", classesDirectory.toString(), sourceFile.toString()
        ));
        System.clearProperty(INITIALIZED_PROPERTY);
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{classesDirectory.toUri().toURL()}, null)) {
            Class.forName(JACKSON_ARRAY_SERIALIZERS, false, classLoader);
            assertNull(System.getProperty(INITIALIZED_PROPERTY));

            Feature.DuringSetupAccess access = (Feature.DuringSetupAccess) Proxy.newProxyInstance(
                Feature.DuringSetupAccess.class.getClassLoader(),
                new Class<?>[]{Feature.DuringSetupAccess.class},
                (proxy, method, args) -> method.getName().equals("getApplicationClassLoader") ? classLoader : null
            );
            new PyronautDevServiceLoaderFeature().duringSetup(access);

            assertEquals("true", System.getProperty(INITIALIZED_PROPERTY));
        } finally {
            System.clearProperty(INITIALIZED_PROPERTY);
        }
    }
}
