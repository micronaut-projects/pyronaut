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
import io.micronaut.projectgen.core.feature.config.Configuration;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import io.micronaut.projectgen.core.template.TomlTemplate;
import jakarta.inject.Singleton;

import java.util.List;

@Internal
@Singleton
class PyProjectToml implements Feature {
    private static final String TEMPLATE_NAME = "pyproject.toml";
    private static final String TEMPLATE_PATH = TEMPLATE_NAME;

    @Override
    public String getName() {
        return "pyprojecttoml";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        Configuration config = new Configuration(TEMPLATE_PATH, TEMPLATE_NAME, TEMPLATE_NAME + "-config");
        config.put("project.name", generatorContext.getOptions().name());
        config.put("project.version", generatorContext.getOptions().version());
        config.put("project.dynamic", List.of("scripts"));
        config.put("build-system.requires", List.of("setuptools", "wheel", "tomli"));
        config.put("build-system.build-backend", "setuptools.build_meta");
        config.put("tool.pyronaut.version", "5.0.0-SNAPSHOT");
        config.put("tool.pyronaut.repositories", List.of("mavenCentral", "mavenLocal", "https://repo.gradle.org/gradle/libs-releases"));
        config.put("tool.pyronaut.dependencies.compile", List.of("io.micronaut:micronaut-inject-python",
            "io.micronaut:micronaut-context-python",
            "io.micronaut:micronaut-http-server-netty", "io.micronaut:micronaut-json-core",
            "io.micronaut:micronaut-jackson-databind",
            "ch.qos.logback:logback-classic",
            "org.bouncycastle:bcprov-jdk18on",
            "org.apache.commons:commons-lang3:3.20.0"));
        config.put("tool.pyronaut.dependencies.annotationProcessor", List.of("io.micronaut:micronaut-inject-python",
            "io.micronaut:micronaut-context-python"));

        TomlTemplate template = new TomlTemplate(TEMPLATE_PATH, config);

        module.addTemplate(TEMPLATE_NAME, template);
    }
}
