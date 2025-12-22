/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import io.micronaut.projectgen.core.rocker.RockerTemplate;
import io.micronaut.pyronaut.projectgen.template.helloWorldControllerPy;
import io.micronaut.pyronaut.projectgen.template.testHelloWorldControllerPy;
import jakarta.inject.Singleton;

@Internal
@Singleton
class HelloWorldControllerPy implements Feature {
    private static final String TEMPLATE_NAME = "src/hello_world_controller.py";
    private static final String TEMPLATE_PATH = TEMPLATE_NAME;
    private static final String TEST_TEMPLATE_NAME = "tests/test_hello_world_controller.py";
    private static final String TEST_TEMPLATE_PATH = TEST_TEMPLATE_NAME;
    private static final String NAME = "hello-world-controller-py";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        module.addTemplate(TEMPLATE_NAME,
            new RockerTemplate(TEMPLATE_PATH, helloWorldControllerPy.template()));
        module.addTemplate(TEST_TEMPLATE_NAME,
            new RockerTemplate(TEST_TEMPLATE_PATH, testHelloWorldControllerPy.template()));
    }
}
