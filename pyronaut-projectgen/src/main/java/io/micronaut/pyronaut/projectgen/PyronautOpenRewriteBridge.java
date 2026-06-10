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

import io.micronaut.core.annotation.Internal;
import io.micronaut.projectgen.core.buildtools.BuildTool;
import io.micronaut.projectgen.core.buildtools.gradle.GradleDsl;
import io.micronaut.projectgen.core.buildtools.maven.Packaging;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeaturePhase;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import io.micronaut.projectgen.core.openrewrite.OpenRewriteFeature;
import io.micronaut.projectgen.core.options.ConfigurationFormat;
import io.micronaut.projectgen.core.options.JdkVersion;
import io.micronaut.projectgen.core.options.Language;
import io.micronaut.projectgen.core.options.OperatingSystem;
import io.micronaut.projectgen.core.options.Options;
import io.micronaut.projectgen.core.options.TestFramework;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Internal
@Singleton
final class PyronautOpenRewriteBridge implements Feature {
    private static final List<BuildTool> RECIPE_BUILD_TOOLS = List.of(BuildTool.MAVEN, BuildTool.GRADLE);
    private static final String DEFAULT_TEMPLATE = "default";

    @Override
    public String getName() {
        return "pyronaut-openrewrite-bridge";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public int getOrder() {
        return FeaturePhase.HIGH.getOrder();
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        Options recipeOptions = new RecipeOptions(generatorContext.getOptions());
        GeneratorContext recipeContext = new GeneratorContext(
            generatorContext.getProject(),
            recipeOptions,
            generatorContext.getFeatures().getFeatures(),
            module.coordinateResolver(),
            module.recipeFetcher()
        );
        Set<String> recipes = new LinkedHashSet<>();
        for (Feature feature : generatorContext.getFeatures().getFeatures()) {
            if (feature instanceof OpenRewriteFeature openRewriteFeature) {
                recipes.addAll(openRewriteFeature.getRecipes(recipeContext));
            }
        }
        for (String recipe : recipes) {
            module.addConfigurationByRecipeName(recipe);
            module.recipeFetcher().findDevPropertiesByRecipeName(recipe).ifPresent(properties ->
                properties.forEach((key, value) -> module.devConfiguration().addNested(key.toString(), value))
            );
            module.addDependenciesByRecipeName(recipeOptions, recipe);
        }
    }

    private record RecipeOptions(Options delegate) implements Options {
        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public OperatingSystem operatingSystem() {
            return delegate.operatingSystem();
        }

        @Override
        public String template() {
            String template = delegate.template();
            return template == null ? DEFAULT_TEMPLATE : template;
        }

        @Override
        public Language language() {
            return delegate.language();
        }

        @Override
        public List<BuildTool> buildTools() {
            return RECIPE_BUILD_TOOLS;
        }

        @Override
        public ConfigurationFormat configurationFormat() {
            return delegate.configurationFormat();
        }

        @Override
        public GradleDsl gradleDsl() {
            return delegate.gradleDsl();
        }

        @Override
        public String group() {
            return delegate.group();
        }

        @Override
        public String artifact() {
            return delegate.artifact();
        }

        @Override
        public JdkVersion java() {
            return delegate.java();
        }

        @Override
        public String packageName() {
            return delegate.packageName();
        }

        @Override
        public String version() {
            return delegate.version();
        }

        @Override
        public Packaging packaging() {
            return delegate.packaging();
        }

        @Override
        public List<String> features() {
            return delegate.features();
        }

        @Override
        public TestFramework testFramework() {
            return delegate.testFramework();
        }

        @Override
        public Options withoutFeatures() {
            return new RecipeOptions(delegate.withoutFeatures());
        }
    }
}
