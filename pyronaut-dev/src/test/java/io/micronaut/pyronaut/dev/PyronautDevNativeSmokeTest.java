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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PyronautDevNativeSmokeTest {
    @Test
    void nativeBinaryPrintsHelp() throws Exception {
        String binary = System.getProperty("pyronaut.dev.native.binary");
        assumeTrue(binary != null && !binary.isBlank(), "native binary not configured");
        Process process = new ProcessBuilder(binary, "--help")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        process.waitFor(30, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue());
        assertEquals(true, new File(binary).canExecute());
    }
}
