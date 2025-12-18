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
import io.micronaut.projectgen.core.feature.DefaultFeature;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.feature.FeatureContext;
import io.micronaut.projectgen.core.options.Options;
import jakarta.inject.Singleton;

import java.util.Set;

@Singleton
@Internal
class PyronautDefaultFeature implements DefaultFeature {
    private final SetupPy setupPy;
    private final PyProjectToml pyProjectToml;
    private final Banner banner;
    private final LauncherInitPy launcherInitPy;
    private final HelloWorldControllerPy helloWorldControllerPy;
    private final LogbackXml logbackXml;

    PyronautDefaultFeature(SetupPy setupPy,
                           PyProjectToml pyProjectToml,
                           Banner banner,
                           LauncherInitPy launcherInitPy,
                           HelloWorldControllerPy helloWorldControllerPy,
                           LogbackXml logbackXml) {
        this.setupPy = setupPy;
        this.pyProjectToml = pyProjectToml;
        this.banner = banner;
        this.launcherInitPy = launcherInitPy;
        this.helloWorldControllerPy = helloWorldControllerPy;
        this.logbackXml = logbackXml;
    }

    @Override
    public String getName() {
        return "pyronaut-default-feature";
    }

    @Override
    public void processSelectedFeatures(FeatureContext featureContext) {
        featureContext.addFeatureIfNotPresent(SetupPy.class, setupPy);
        featureContext.addFeatureIfNotPresent(PyProjectToml.class, pyProjectToml);
        featureContext.addFeatureIfNotPresent(LogbackXml.class, logbackXml);
        featureContext.addFeatureIfNotPresent(Banner.class, banner);
        featureContext.addFeatureIfNotPresent(LauncherInitPy.class, launcherInitPy);
        featureContext.addFeatureIfNotPresent(HelloWorldControllerPy.class, helloWorldControllerPy);
    }

    @Override
    public boolean shouldApply(Options options, Set<Feature> selectedFeatures) {
        return true;
    }

    @Override
    public boolean isVisible() {
        return false;
    }
}
