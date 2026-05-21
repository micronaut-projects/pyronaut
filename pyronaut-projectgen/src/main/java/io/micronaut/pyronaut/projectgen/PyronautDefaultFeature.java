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
import io.micronaut.projectgen.core.feature.ConfigurationFeature;
import io.micronaut.projectgen.core.feature.DefaultFeature;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeatureContext;
import io.micronaut.projectgen.core.options.Options;
import io.micronaut.projectgen.micronaut.features.serde.MicronautSerdeJackson;
import io.micronaut.starter.feature.server.Netty;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.Set;

@Singleton
@Internal
class PyronautDefaultFeature implements DefaultFeature {
    private final PyProjectToml pyProjectToml;
    private final Banner banner;
    private final PyronautGeneratedFiles generatedFiles;
    private final PyronautAgentSkills agentSkills;
    private final PyronautConfigurationToml configurationToml;
    private final PyronautBaseConfiguration baseConfiguration;
    private final PyronautOpenRewriteBridge openRewriteBridge;
    private final PyronautFeatureCatalog catalog;
    private final Netty netty;
    private final MicronautSerdeJackson serdeJackson;

    PyronautDefaultFeature(PyProjectToml pyProjectToml,
                           Banner banner,
                           PyronautGeneratedFiles generatedFiles,
                           PyronautAgentSkills agentSkills,
                           PyronautConfigurationToml configurationToml,
                           PyronautBaseConfiguration baseConfiguration,
                           PyronautOpenRewriteBridge openRewriteBridge,
                           PyronautFeatureCatalog catalog,
                           Netty netty,
                           MicronautSerdeJackson serdeJackson) {
        this.pyProjectToml = pyProjectToml;
        this.banner = banner;
        this.generatedFiles = generatedFiles;
        this.agentSkills = agentSkills;
        this.configurationToml = configurationToml;
        this.baseConfiguration = baseConfiguration;
        this.openRewriteBridge = openRewriteBridge;
        this.catalog = catalog;
        this.netty = netty;
        this.serdeJackson = serdeJackson;
    }

    @Override
    public String getName() {
        return "pyronaut-default-feature";
    }

    @Override
    public void processSelectedFeatures(FeatureContext featureContext) {
        featureContext.exclude(feature -> feature instanceof ConfigurationFeature || "toml-build".equals(feature.getName()));
        featureContext.addFeatureIfNotPresent(PyProjectToml.class, pyProjectToml);
        featureContext.addFeatureIfNotPresent(Banner.class, banner);
        featureContext.addFeatureIfNotPresent(PyronautGeneratedFiles.class, generatedFiles);
        featureContext.addFeatureIfNotPresent(PyronautAgentSkills.class, agentSkills);
        featureContext.addFeatureIfNotPresent(PyronautConfigurationToml.class, configurationToml);
        featureContext.addFeatureIfNotPresent(PyronautBaseConfiguration.class, baseConfiguration);
        featureContext.addFeatureIfNotPresent(PyronautOpenRewriteBridge.class, openRewriteBridge);
        addCatalogFeatureIfMissing(featureContext, "pyronaut-core");
        addFeatureIfMissing(featureContext, netty, "http-server-netty", "netty-server");
        addFeatureIfMissing(featureContext, serdeJackson, "serde-jackson", "serialization-jackson");
        addCatalogFeatureIfMissing(featureContext, "pyronaut-logback");
        addCatalogFeatureIfMissing(featureContext, "pyronaut-pytest");
    }

    @Override
    public boolean shouldApply(Options options, Set<Feature> selectedFeatures) {
        return true;
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    private void addCatalogFeatureIfMissing(FeatureContext featureContext, String name) {
        boolean selected = featureContext.getSelectedFeatures().stream()
            .map(Feature::getName)
            .anyMatch(name::equals);
        if (!selected) {
            featureContext.addFeature(catalog.findFeature(name)
                .orElseThrow(() -> new IllegalStateException("Missing Pyronaut feature: " + name)));
        }
    }

    private static void addFeatureIfMissing(FeatureContext featureContext, Feature feature, String... names) {
        boolean selected = featureContext.getSelectedFeatures().stream()
            .map(Feature::getName)
            .anyMatch(name -> Arrays.asList(names).contains(name));
        if (!selected) {
            featureContext.addFeatureIfNotPresent(feature.getClass(), feature);
        }
    }
}
