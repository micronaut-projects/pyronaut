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
package io.micronaut.pyronaut.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.List;

/** Maps the Python API to the existing Micronaut DI and Jakarta validation metadata. */
public final class PythonFacingAnnotationRemapper implements AnnotationRemapper {
    @Override
    public String getPackageName() {
        return "pyronaut.annotations";
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        String canonical = switch (annotation.getAnnotationName()) {
            case "pyronaut.annotations.singleton" -> "jakarta.inject.Singleton";
            case "pyronaut.annotations.not_blank" -> "jakarta.validation.constraints.NotBlank";
            case "pyronaut.annotations.valid" -> "jakarta.validation.Valid";
            default -> null;
        };
        return List.of(canonical == null ? annotation : AnnotationValue.builder(canonical).members(annotation.getValues()).build());
    }
}
