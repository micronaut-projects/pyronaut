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
package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

@EnabledIfSystemProperty(named = "pyronaut.run.native.binary", matches = ".+")
class PyronautRunJavaNativeSmokeTest extends AbstractPyronautRunSmokeTest {

    @Test
    void nativeLauncherServesPrecompiledJavaApplication() throws Exception {
        assertPrecompiledJavaApplicationServesHttpResponse();
    }

    @Override
    protected RunResult runPyronautRun(Path project) throws Exception {
        return runNative(Path.of(System.getProperty("pyronaut.run.native.binary")), project);
    }
}
