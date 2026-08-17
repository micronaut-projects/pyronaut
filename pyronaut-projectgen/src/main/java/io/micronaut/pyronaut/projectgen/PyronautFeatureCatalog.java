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
import io.micronaut.projectgen.core.buildtools.Scope;
import io.micronaut.projectgen.core.buildtools.dependencies.Dependency;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeatureContext;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import jakarta.inject.Singleton;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Catalog of Pyronaut-supported ProjectGen features. */
@Internal
@Singleton
public final class PyronautFeatureCatalog {
    private static final List<String> UNSUPPORTED_PREFIXES = List.of("groovy-");
    private final Map<String, Feature> features;

    public PyronautFeatureCatalog() {
        this.features = createFeatures();
    }

    public Optional<Feature> findFeature(String name) {
        return Optional.ofNullable(features.get(name));
    }

    public List<Feature> features() {
        return List.copyOf(features.values());
    }

    public List<Feature> visibleFeatures() {
        return features.values().stream()
            .filter(Feature::isVisible)
            .toList();
    }

    boolean isUnsupported(String name) {
        Feature feature = features.get(name);
        return (feature instanceof PyronautCatalogFeature catalogFeature && !catalogFeature.supported)
            || UNSUPPORTED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static Map<String, Feature> createFeatures() {
        Map<String, Feature> features = new LinkedHashMap<>();
        add(features, PyronautCatalogFeature.builder("pyronaut-core")
            .description("Core Pyronaut compiler and runtime dependencies.")
            .hidden()
            .dependency(Scope.ANNOTATION_PROCESSOR, "io.micronaut:micronaut-inject-python")
            .dependency(Scope.ANNOTATION_PROCESSOR, "io.micronaut:micronaut-context-python")
            .build());
        add(features, PyronautCatalogFeature.builder("pyronaut-logback")
            .title("Pyronaut Logback")
            .description("Adds Python logging integration backed by Logback.")
            .category("Logging")
            .dependency(Scope.RUNTIME, "io.micronaut.pyronaut:micronaut-pyronaut-logback")
            .build());
        add(features, PyronautCatalogFeature.builder("pyronaut-pytest")
            .title("Pytest")
            .description("Adds the Pyronaut pytest engine and Micronaut test support.")
            .category("Testing")
            .dependency(Scope.TEST, "io.micronaut.pyronaut:micronaut-pyronaut-pytest")
            .dependency(Scope.TEST, "io.micronaut.pyronaut:micronaut-pyronaut-requests")
            .dependency(Scope.TEST, "io.micronaut.test:micronaut-test-junit5")
            .testConfiguration("micronaut.server.port", -1)
            .build());
        add(features, PyronautCatalogFeature.builder("views-jinjava")
            .title("Jinjava Views")
            .description("Adds Jinjava server-side view rendering.")
            .category("Web")
            .dependency(Scope.RUNTIME, "io.micronaut.views:micronaut-views-jinjava")
            .build());

        for (String unsupported : List.of(
            "gradle",
            "maven",
            "java",
            "kotlin",
            "groovy",
            "junit",
            "spock",
            "kotest",
            "jackson-databind",
            "jackson-xml",
            "data-jpa",
            "hibernate-jpa",
            "hibernate-jpamodelgen",
            "data-hibernate-reactive",
            "hibernate-reactive-jpa",
            "hibernate-validator",
            "docker",
            "github-workflow-java-ci",
            "kubernetes",
            "aws-lambda",
            "function-aws",
            "oracle-cloud-function",
            "gcp-function",
            "azure-function"
        )) {
            add(features, PyronautCatalogFeature.unsupported(unsupported));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    private static void add(Map<String, Feature> features, Feature feature) {
        features.put(feature.getName(), feature);
    }

    static final class PyronautCatalogFeature implements Feature, PyprojectContributor {
        private final String name;
        private final String title;
        private final String description;
        private final String category;
        private final boolean visible;
        private final boolean supported;
        private final List<Dependency> dependencies;
        private final Map<String, Object> configuration;
        private final Map<String, Object> testConfiguration;
        private final Map<String, Object> pyprojectConfiguration;

        private PyronautCatalogFeature(Builder builder) {
            this.name = builder.name;
            this.title = builder.title;
            this.description = builder.description;
            this.category = builder.category;
            this.visible = builder.visible;
            this.supported = builder.supported;
            this.dependencies = List.copyOf(builder.dependencies);
            this.configuration = Collections.unmodifiableMap(new LinkedHashMap<>(builder.configuration));
            this.testConfiguration = Collections.unmodifiableMap(new LinkedHashMap<>(builder.testConfiguration));
            this.pyprojectConfiguration = Collections.unmodifiableMap(new LinkedHashMap<>(builder.pyprojectConfiguration));
        }

        static Builder builder(String name) {
            return new Builder(name);
        }

        static PyronautCatalogFeature unsupported(String name) {
            return builder(name)
                .hidden()
                .unsupported()
                .description("This starter feature is not supported for Pyronaut/Python projects yet.")
                .build();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public void processSelectedFeatures(FeatureContext featureContext) {
            if (!supported) {
                throw unsupportedFeatureException();
            }
        }

        @Override
        public void apply(GeneratorContext generatorContext) {
            if (!supported) {
                throw unsupportedFeatureException();
            }
            ModuleContext module = generatorContext.getRootModule();
            dependencies.forEach(module::addDependency);
            configuration.forEach(module.configuration()::addNested);
            testConfiguration.forEach(module.testConfiguration()::addNested);
        }

        @Override
        public void contributePyproject(ConfigurationConsumer consumer) {
            pyprojectConfiguration.forEach(consumer::accept);
        }

        private IllegalArgumentException unsupportedFeatureException() {
            return new IllegalArgumentException("Feature '" + name + "' is not supported for Pyronaut/Python projects.");
        }

        private static final class Builder {
            private final String name;
            private String title;
            private String description;
            private String category;
            private boolean visible = true;
            private boolean supported = true;
            private final List<Dependency> dependencies = new java.util.ArrayList<>();
            private final Map<String, Object> configuration = new LinkedHashMap<>();
            private final Map<String, Object> testConfiguration = new LinkedHashMap<>();
            private final Map<String, Object> pyprojectConfiguration = new LinkedHashMap<>();

            private Builder(String name) {
                this.name = name;
                this.title = name;
            }

            Builder title(String title) {
                this.title = title;
                return this;
            }

            Builder description(String description) {
                this.description = description;
                return this;
            }

            Builder category(String category) {
                this.category = category;
                return this;
            }

            Builder hidden() {
                this.visible = false;
                return this;
            }

            Builder unsupported() {
                this.supported = false;
                return this;
            }

            Builder dependency(Scope scope, String coordinate) {
                dependencies.add(dependency(coordinate, scope));
                return this;
            }

            Builder configuration(String key, Object value) {
                configuration.put(key, value);
                return this;
            }

            Builder testConfiguration(String key, Object value) {
                testConfiguration.put(key, value);
                return this;
            }

            Builder pyproject(String key, Object value) {
                pyprojectConfiguration.put(key, value);
                return this;
            }

            PyronautCatalogFeature build() {
                return new PyronautCatalogFeature(this);
            }

            private static Dependency dependency(String coordinate, Scope scope) {
                String[] parts = coordinate.split(":");
                if (parts.length < 2 || parts.length > 3) {
                    throw new IllegalArgumentException("Invalid coordinate: " + coordinate);
                }
                Dependency.Builder builder = Dependency.builder()
                    .groupId(parts[0])
                    .artifactId(parts[1])
                    .scope(scope);
                if (parts.length == 3) {
                    builder.version(parts[2]);
                }
                return builder.build();
            }
        }
    }
}
