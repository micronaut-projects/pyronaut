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
import org.graalvm.nativeimage.hosted.Feature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pyronaut-dev native-image service loader feature.
 *
 * <p>Most Micronaut services are safe and useful to bake into the image. Environment property
 * loaders and expression resolvers are intentionally left dynamic so the launcher can discover
 * providers from the current application classloader, including user test-resources modules.</p>
 */
@Internal
public final class PyronautDevServiceLoaderFeature extends ServiceLoaderFeature {
    private static final Set<String> DYNAMIC_SERVICES = Set.of(
        "io.micronaut.context.env.PropertySourceLoader",
        "io.micronaut.context.env.PropertyExpressionResolver",
        "io.micronaut.data.processor.visitors.finders.MethodMatcher"
    );

    @Override
    protected ServiceScanner.StaticServiceDefinitions buildStaticServiceDefinitions(Feature.BeforeAnalysisAccess access) {
        ServiceScanner.StaticServiceDefinitions definitions = super.buildStaticServiceDefinitions(access);
        Map<String, Set<String>> filtered = new LinkedHashMap<>(definitions.serviceTypeMap());
        DYNAMIC_SERVICES.forEach(filtered::remove);
        return new ServiceScanner.StaticServiceDefinitions(filtered);
    }
}
