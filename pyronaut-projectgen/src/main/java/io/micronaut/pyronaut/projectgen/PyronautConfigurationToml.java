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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Internal;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeaturePhase;
import io.micronaut.projectgen.core.feature.config.Configuration;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import jakarta.inject.Singleton;

import java.util.Map;

@Internal
@Singleton
class PyronautConfigurationToml implements Feature {
    @Override
    public String getName() {
        return "pyronaut-configuration-toml";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public int getOrder() {
        return FeaturePhase.HIGHEST.getOrder();
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        module.removeTemplate("src/main/resources/application.toml");
        module.removeTemplate("src/test/resources/application-test.toml");
        addConfigurationTemplate(module, "config/application.toml", module.configuration());
        for (Map.Entry<String, ? extends Configuration> entry : module.configurationByEnvironment().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String environment = entry.getKey();
            String path = Environment.TEST.equals(environment)
                ? "tests-config/application-test.toml"
                : "config/application-" + environment + ".toml";
            addConfigurationTemplate(module, path, entry.getValue());
        }
    }

    private static void addConfigurationTemplate(ModuleContext module, String path, Configuration source) {
        Configuration target = new Configuration(path, path.substring(path.lastIndexOf('/') + 1), path + "-config");
        target.addNested(source);
        module.addTemplate(path, new PyronautConfigurationTomlTemplate(path, target));
    }
}
