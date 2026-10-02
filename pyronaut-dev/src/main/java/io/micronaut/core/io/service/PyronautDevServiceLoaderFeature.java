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
package io.micronaut.core.io.service;

import io.micronaut.core.annotation.Internal;
import io.micronaut.pyronaut.config.classloader.NativeLauncherServices;
import org.graalvm.nativeimage.hosted.Feature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pyronaut-dev native-image service loader feature.
 *
 * <p>Most Micronaut services are safe and useful to bake into the image. Extension points used
 * while processing or running an application are intentionally left dynamic so the launcher can
 * discover providers from the current application classloader.</p>
 */
@Internal
public final class PyronautDevServiceLoaderFeature extends ServiceLoaderFeature {
    private static final String JACKSON_ARRAY_SERIALIZERS = "tools.jackson.databind.ser.jdk.JDKArraySerializers";

    private static final Set<String> DYNAMIC_SERVICES = Set.of(
        "io.micronaut.context.ApplicationContextConfigurer",
        "io.micronaut.context.env.PropertySourceLoader",
        "io.micronaut.context.env.PropertySourceImporter",
        "io.micronaut.context.env.PropertyExpressionResolver",
        "io.micronaut.context.python.TargetTypeMapping",
        "io.micronaut.core.convert.TypeConverterRegistrar",
        "io.micronaut.data.processor.visitors.finders.MethodMatcher",
        "io.micronaut.inject.annotation.AnnotatedElementValidator",
        "io.micronaut.inject.visitor.BeanElementVisitor",
        "io.micronaut.inject.visitor.PackageElementVisitor",
        "io.micronaut.inject.visitor.TypeElementVisitor",
        "io.micronaut.serde.config.naming.PropertyNamingStrategy",
        "io.micronaut.sourcegen.generator.SourceGenerator"
    );

    @Override
    public void duringSetup(Feature.DuringSetupAccess access) {
        // Outer and nested serializers depend on each other's initializers. Initialize the
        // outer class on the builder thread before parallel analysis can deadlock on them.
        try {
            Class.forName(JACKSON_ARRAY_SERIALIZERS, true, access.getApplicationClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Jackson array serializers are required by pyronaut-dev", e);
        }
    }

    @Override
    protected ServiceScanner.ExclusiveStaticServiceDefinitions buildStaticServiceDefinitions(Feature.BeforeAnalysisAccess access) {
        ServiceScanner.ExclusiveStaticServiceDefinitions definitions = super.buildStaticServiceDefinitions(access);
        Map<String, Set<String>> filtered = new LinkedHashMap<>(definitions.serviceTypeMap());
        DYNAMIC_SERVICES.forEach(filtered::remove);
        return new ServiceScanner.ExclusiveStaticServiceDefinitions(filtered);
    }

    @Override
    protected void addImageSingleton(ServiceScanner.ExclusiveStaticServiceDefinitions definitions) {
        super.addImageSingleton(definitions);
        // Runtime application loading disables Micronaut's ImageSingletons lookups. Keep the
        // already-filtered launcher names without requiring a resource filesystem scan instead.
        NativeLauncherServices.initialize(definitions.serviceTypeMap());
    }
}
