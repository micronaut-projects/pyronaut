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
package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmOptionsFileTest {
    private static final String CLASSPATH = "pyronaut.test.jvm.options.classpath";
    private static final String FLAG = "pyronaut.test.jvm.options.flag";

    @Test
    void appliesSystemPropertiesFromTheOptionsFile(@TempDir Path directory) throws Exception {
        Path options = Files.writeString(directory.resolve("jvm-options.txt"),
            "-D" + CLASSPATH + "=C:\\lib\\a.jar;C:\\lib\\b=c.jar\n-D" + FLAG + "\nignored\n");
        String previous = System.getProperty(JvmOptionsFile.PROPERTY);
        String previousClasspath = System.getProperty(CLASSPATH);
        String previousFlag = System.getProperty(FLAG);
        try {
            System.setProperty(JvmOptionsFile.PROPERTY, options.toString());

            JvmOptionsFile.apply();

            assertEquals("C:\\lib\\a.jar;C:\\lib\\b=c.jar", System.getProperty(CLASSPATH));
            assertEquals("", System.getProperty(FLAG));
        } finally {
            restore(JvmOptionsFile.PROPERTY, previous);
            restore(CLASSPATH, previousClasspath);
            restore(FLAG, previousFlag);
        }
    }

    @Test
    void appliesDuplicateSystemPropertiesInOrder(@TempDir Path directory) throws Exception {
        Path options = Files.writeString(directory.resolve("jvm-options.txt"),
            "-D" + CLASSPATH + "=earlier\n-D" + CLASSPATH + "=last\n-D" + FLAG + "=set\n-D" + FLAG + "\n");
        String previous = System.getProperty(JvmOptionsFile.PROPERTY);
        String previousClasspath = System.getProperty(CLASSPATH);
        String previousFlag = System.getProperty(FLAG);
        try {
            System.setProperty(JvmOptionsFile.PROPERTY, options.toString());

            JvmOptionsFile.apply();

            assertEquals("last", System.getProperty(CLASSPATH));
            assertEquals("", System.getProperty(FLAG));
        } finally {
            restore(JvmOptionsFile.PROPERTY, previous);
            restore(CLASSPATH, previousClasspath);
            restore(FLAG, previousFlag);
        }
    }

    @Test
    void ignoresMissingOrBlankOptionsFileProperties() {
        String previous = System.getProperty(JvmOptionsFile.PROPERTY);
        String previousClasspath = System.getProperty(CLASSPATH);
        try {
            System.setProperty(CLASSPATH, "unchanged");
            for (String configured : new String[]{null, "", " \t "}) {
                if (configured == null) {
                    System.clearProperty(JvmOptionsFile.PROPERTY);
                } else {
                    System.setProperty(JvmOptionsFile.PROPERTY, configured);
                }

                JvmOptionsFile.apply();

                assertEquals("unchanged", System.getProperty(CLASSPATH));
            }
        } finally {
            restore(JvmOptionsFile.PROPERTY, previous);
            restore(CLASSPATH, previousClasspath);
        }
    }

    @Test
    void ignoresMalformedOptionsAndStillAppliesValidProperties(@TempDir Path directory) throws Exception {
        Path options = Files.writeString(directory.resolve("jvm-options.txt"),
            "-D\n-D=ignored\n" + FLAG + "=ignored\n\n-D" + CLASSPATH + "=valid\n");
        String previous = System.getProperty(JvmOptionsFile.PROPERTY);
        String previousClasspath = System.getProperty(CLASSPATH);
        String previousFlag = System.getProperty(FLAG);
        try {
            System.setProperty(JvmOptionsFile.PROPERTY, options.toString());
            System.setProperty(FLAG, "unchanged");

            JvmOptionsFile.apply();

            assertEquals("valid", System.getProperty(CLASSPATH));
            assertEquals("unchanged", System.getProperty(FLAG));
        } finally {
            restore(JvmOptionsFile.PROPERTY, previous);
            restore(CLASSPATH, previousClasspath);
            restore(FLAG, previousFlag);
        }
    }

    @Test
    void reportsUnreadableOptionsFiles(@TempDir Path directory) {
        String previous = System.getProperty(JvmOptionsFile.PROPERTY);
        try {
            for (Path options : List.of(directory.resolve("missing-options.txt"), directory)) {
                System.setProperty(JvmOptionsFile.PROPERTY, options.toString());

                UncheckedIOException error = assertThrows(UncheckedIOException.class, JvmOptionsFile::apply);

                assertEquals("Failed to read " + JvmOptionsFile.PROPERTY + " " + options, error.getMessage());
                assertNotNull(error.getCause());
            }
        } finally {
            restore(JvmOptionsFile.PROPERTY, previous);
        }
    }

    private static void restore(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
