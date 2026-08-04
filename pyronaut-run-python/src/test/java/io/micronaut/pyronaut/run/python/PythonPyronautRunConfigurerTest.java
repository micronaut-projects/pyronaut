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
package io.micronaut.pyronaut.run.python;

import io.micronaut.pyronaut.run.PyronautRunConfigurer;
import io.micronaut.pyronaut.run.PythonPyronautRunConfigurer;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonPyronautRunConfigurerTest {

    @Test
    void registersPythonRuntimeConfigurer() {
        PyronautRunConfigurer configurer = ServiceLoader.load(PyronautRunConfigurer.class)
            .findFirst()
            .orElseThrow();

        assertEquals(PythonPyronautRunConfigurer.class, configurer.getClass());
    }
}
