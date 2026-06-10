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
import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.projectgen.core.buildtools.Scope;
import io.micronaut.projectgen.core.buildtools.dependencies.Dependency;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeaturePhase;
import io.micronaut.projectgen.core.feature.config.Configuration;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

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
    public int getOrder() {
        return FeaturePhase.HIGHEST.getOrder();
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        PyronautProjectSettings settings = PyronautProjectSettingsContext.current();
        Configuration config = new Configuration(TEMPLATE_PATH, TEMPLATE_NAME, TEMPLATE_NAME + "-config");
        config.put("project.name", generatorContext.getOptions().name());
        config.put("project.version", generatorContext.getOptions().version());
        config.put("build-system.requires", List.of("setuptools", "wheel", "tomli"));
        config.put("build-system.build-backend", "setuptools.build_meta");
        config.put("tool.setuptools.package-dir", Map.of("", "src"));
        config.put("tool.setuptools.packages.find.where", List.of("src"));
        config.put("tool.pyronaut.repositories", repositories(settings));
        config.put("tool.pyronaut.core.version", PyronautManagedVersions.micronautCoreVersion());
        config.put("tool.pyronaut.platform.version", settings.micronautVersion());
        config.put("tool.pyronaut.sources.python", "src");
        config.put("tool.pyronaut.sources.python-test", "tests");
        config.put("tool.pyronaut.sources.resources", "config");
        config.put("tool.pyronaut.sources.test-resources", "tests-config");
        config.put("tool.pyronaut.test-resources.enabled", false);
        config.put("tool.pyronaut.test-resources.infer-classpath", false);

        for (Feature feature : generatorContext.getFeatures().getFeatures()) {
            if (feature instanceof PyprojectContributor contributor) {
                contributor.contributePyproject(config::put);
            }
        }

        DependencyScopes dependencies = dependencyScopes(module.getDependencies());
        config.put("tool.pyronaut.dependencies.runtime", List.copyOf(dependencies.runtime()));
        config.put("tool.pyronaut.dependencies.build", List.copyOf(dependencies.build()));
        config.put("tool.pyronaut.dependencies.test", List.copyOf(dependencies.test()));

        module.addTemplate(TEMPLATE_NAME, new PyprojectTomlTemplate(TEMPLATE_PATH, config));
    }

    private static List<String> repositories(PyronautProjectSettings settings) {
        if (!settings.repositories().isEmpty()) {
            return settings.repositories();
        }
        if (settings.micronautVersion() != null && settings.micronautVersion().endsWith("-SNAPSHOT")) {
            return List.of("mavenLocal", "mavenCentral");
        }
        return List.of("mavenCentral");
    }

    private static DependencyScopes dependencyScopes(Iterable<Dependency> dependencies) {
        Set<String> runtime = new LinkedHashSet<>();
        Set<String> build = new LinkedHashSet<>();
        Set<String> test = new LinkedHashSet<>();
        StreamSupport.stream(dependencies.spliterator(), false)
            .sorted(Dependency.COMPARATOR)
            .forEach(dependency -> {
                String coordinate = coordinate(dependency);
                Scope scope = dependency.getScope();
                if (Scope.ANNOTATION_PROCESSOR.equals(scope)
                    || Scope.TEST_ANNOTATION_PROCESSOR.equals(scope)) {
                    build.add(coordinate);
                } else if (Scope.TEST.equals(scope)
                    || Scope.TEST_RUNTIME.equals(scope)
                    || Scope.TEST_COMPILE_ONLY.equals(scope)) {
                    test.add(coordinate);
                } else if (Scope.COMPILE.equals(scope)
                    || Scope.RUNTIME.equals(scope)
                    || scope == null) {
                    runtime.add(coordinate);
                }
            });
        return new DependencyScopes(runtime, build, test);
    }

    private static String coordinate(Dependency dependency) {
        String coordinate = dependency.getGroupId() + ":" + dependency.getArtifactId();
        if (dependency.getVersion() != null && !dependency.getVersion().isBlank()) {
            coordinate += ":" + dependency.getVersion();
        }
        return coordinate;
    }

    private record DependencyScopes(Set<String> runtime, Set<String> build, Set<String> test) {
    }
}
