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
import io.micronaut.core.order.Ordered;
import io.micronaut.projectgen.core.feature.AvailableFeatures;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeatureContext;
import io.micronaut.projectgen.core.options.Language;
import io.micronaut.projectgen.core.options.Options;
import jakarta.inject.Singleton;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Internal
@Singleton
final class PyronautAvailableFeatures implements AvailableFeatures {
    private static final String PYRONAUT_PROJECTGEN_PACKAGE = "io.micronaut.pyronaut.projectgen";
    private static final Set<String> COMPATIBLE_UPSTREAM_NAMES = Set.of(
        "data-jdbc",
        "mysql",
        "json-schema"
    );
    private static final Map<String, String> VISIBLE_UPSTREAM_ALIASES = Map.of(
        "http-server-netty", "netty-server",
        "serde-jackson", "serialization-jackson"
    );
    private static final Set<String> HIDDEN_UPSTREAM_SELECTION_NAMES = Set.of(
        "netty-server",
        "serialization-jackson"
    );

    private final Map<String, Feature> features;

    PyronautAvailableFeatures(List<Feature> beanFeatures, PyronautFeatureCatalog catalog) {
        Map<String, Feature> merged = new LinkedHashMap<>();
        Map<String, Feature> upstreamFeatures = new LinkedHashMap<>();
        for (Feature feature : beanFeatures) {
            if (isPyronautFeature(feature)) {
                merged.put(feature.getName(), feature);
            } else {
                upstreamFeatures.put(feature.getName(), feature);
            }
        }
        for (Feature feature : catalog.features()) {
            merged.put(feature.getName(), feature);
        }
        addCompatibleUpstreamFeatures(merged, upstreamFeatures);
        addUnsupportedUpstreamFeatures(merged, upstreamFeatures);
        this.features = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    @Override
    public boolean supports(Options options) {
        return options.language() == Language.PYTHON;
    }

    @Override
    public Optional<Feature> findFeature(String name) {
        return findFeature(name, false);
    }

    @Override
    public Optional<Feature> findFeature(String name, boolean ignoreVisibility) {
        Feature feature = features.get(name);
        if (feature == null || (!ignoreVisibility && !feature.isVisible())) {
            return Optional.empty();
        }
        return Optional.of(feature);
    }

    @Override
    public Stream<Feature> getFeatures() {
        return getAllFeatures().filter(Feature::isVisible);
    }

    @Override
    public Stream<Feature> getAllFeatures() {
        return features.values().stream();
    }

    @Override
    public Iterator<String> iterator() {
        return getFeatures().map(Feature::getName).iterator();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static boolean isPyronautFeature(Feature feature) {
        Package featurePackage = feature.getClass().getPackage();
        return featurePackage != null && featurePackage.getName().startsWith(PYRONAUT_PROJECTGEN_PACKAGE);
    }

    private static void addCompatibleUpstreamFeatures(Map<String, Feature> merged, Map<String, Feature> upstreamFeatures) {
        for (String name : COMPATIBLE_UPSTREAM_NAMES) {
            Feature feature = upstreamFeatures.get(name);
            if (feature != null) {
                merged.put(name, feature);
            }
        }
        for (Map.Entry<String, String> entry : VISIBLE_UPSTREAM_ALIASES.entrySet()) {
            Feature feature = upstreamFeatures.get(entry.getValue());
            if (feature != null) {
                merged.put(entry.getKey(), new UpstreamFeatureSelection(entry.getKey(), feature, true, Map.of()));
            }
        }
        for (String name : HIDDEN_UPSTREAM_SELECTION_NAMES) {
            Feature feature = upstreamFeatures.get(name);
            if (feature != null) {
                merged.put(name, new UpstreamFeatureSelection(name, feature, false, Map.of()));
            }
        }
        Feature testResources = upstreamFeatures.get("test-resources");
        if (testResources != null) {
            merged.put("test-resources", new UpstreamFeatureSelection(
                "test-resources",
                testResources,
                true,
                Map.of(
                    "tool.pyronaut.test-resources.enabled", true,
                    "tool.pyronaut.test-resources.infer-classpath", true
                )
            ));
        }
    }

    private static void addUnsupportedUpstreamFeatures(Map<String, Feature> merged, Map<String, Feature> upstreamFeatures) {
        for (Feature feature : upstreamFeatures.values()) {
            if (feature.isVisible() && !merged.containsKey(feature.getName())) {
                merged.put(feature.getName(), PyronautFeatureCatalog.PyronautCatalogFeature.unsupported(feature.getName()));
            }
        }
    }

    private static final class UpstreamFeatureSelection implements Feature, PyprojectContributor {
        private final String name;
        private final Feature delegate;
        private final boolean visible;
        private final Map<String, Object> pyprojectConfiguration;

        private UpstreamFeatureSelection(String name,
                                         Feature delegate,
                                         boolean visible,
                                         Map<String, Object> pyprojectConfiguration) {
            this.name = name;
            this.delegate = delegate;
            this.visible = visible;
            this.pyprojectConfiguration = pyprojectConfiguration;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getTitle() {
            return delegate.getTitle();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public String getCategory() {
            return delegate.getCategory();
        }

        @Override
        public boolean isPreview() {
            return delegate.isPreview();
        }

        @Override
        public boolean isCommunity() {
            return delegate.isCommunity();
        }

        @Override
        public int getOrder() {
            return delegate.getOrder();
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public boolean supports(Options options) {
            return options.language() == Language.PYTHON;
        }

        @Override
        public void processSelectedFeatures(FeatureContext featureContext) {
            if (!featureContext.isPresent(delegate.getClass())) {
                featureContext.addFeature(delegate);
            }
        }

        @Override
        public void contributePyproject(ConfigurationConsumer consumer) {
            pyprojectConfiguration.forEach(consumer::accept);
        }
    }
}
