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

import io.micronaut.projectgen.core.buildtools.Scope;
import io.micronaut.projectgen.core.buildtools.dependencies.Dependency;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import jakarta.inject.Singleton;

@Singleton
final class ScopeMappingFeature implements Feature {
    @Override
    public String getName() {
        return "scope-mapping-fixture";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        module.addDependency(dependency(Scope.COMPILE, "compile-dep"));
        module.addDependency(dependency(Scope.RUNTIME, "runtime-dep"));
        module.addDependency(dependency(Scope.ANNOTATION_PROCESSOR, "annotation-processor-dep"));
        module.addDependency(dependency(Scope.TEST_ANNOTATION_PROCESSOR, "test-annotation-processor-dep"));
        module.addDependency(dependency(Scope.TEST, "test-dep"));
    }

    private static Dependency dependency(Scope scope, String artifactId) {
        return Dependency.builder()
            .groupId("com.example")
            .artifactId(artifactId)
            .scope(scope)
            .build();
    }
}
