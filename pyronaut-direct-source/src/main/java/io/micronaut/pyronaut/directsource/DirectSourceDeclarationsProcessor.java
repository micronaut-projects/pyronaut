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
package io.micronaut.pyronaut.directsource;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects direct-source Java declarations before normal Micronaut processing.
 */
@SupportedAnnotationTypes({
    "pyronaut.build.Dependency", "pyronaut.build.Dependencies",
    "pyronaut.build.MavenRepository", "pyronaut.build.MavenRepositories",
    "pyronaut.build.AppConfig", "pyronaut.build.AppConfigs"
})
public final class DirectSourceDeclarationsProcessor extends AbstractProcessor {
    private boolean raised;
    private final List<DirectSourceDeclarations.Dependency> dependencies = new ArrayList<>();
    private final List<String> repositories = new ArrayList<>();
    private final Map<String, String> buildProperties = new LinkedHashMap<>();
    private final Map<String, String> runtimeProperties = new LinkedHashMap<>();
    private Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        elementUtils = processingEnvironment.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (raised || roundEnvironment.processingOver() || DirectSourceDeclarationState.isResolved()) {
            return false;
        }
        for (Element root : roundEnvironment.getRootElements()) {
            collect(root);
        }
        var declarations = declarations();
        if (!declarations.isEmpty()) {
            raised = true;
            throw new DirectSourceDeclarationRequest(declarations);
        }
        return false;
    }

    private void collect(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String name = mirror.getAnnotationType().toString();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            elementUtils.getElementValuesWithDefaults(mirror)
                .forEach((key, value) -> values.put(key.getSimpleName().toString(), value.getValue()));
            if (name.equals("pyronaut.build.Dependency")) {
                addDependency(values);
            } else if (name.equals("pyronaut.build.Dependencies")) {
                nestedValues(values).forEach(this::addDependency);
            } else if (name.equals("pyronaut.build.MavenRepository")) {
                repositories.add(String.valueOf(values.get("value")));
            } else if (name.equals("pyronaut.build.MavenRepositories")) {
                nestedValues(values).stream()
                    .map(value -> String.valueOf(value.get("value")))
                    .forEach(repositories::add);
            } else if (name.equals("pyronaut.build.AppConfig")) {
                addProperty(values);
            } else if (name.equals("pyronaut.build.AppConfigs")) {
                nestedValues(values).forEach(this::addProperty);
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            collect(enclosed);
        }
    }

    private void addDependency(Map<String, Object> values) {
        String coordinate = values.get("group") + ":" + values.get("module");
        if (!String.valueOf(values.get("version")).isBlank()) {
            coordinate += ":" + values.get("version");
        }
        dependencies.add(new DirectSourceDeclarations.Dependency(coordinate, isBuild(values.get("scope"))));
    }

    private void addProperty(Map<String, Object> values) {
        (isBuild(values.get("scope")) ? buildProperties : runtimeProperties)
            .put(String.valueOf(values.get("name")), String.valueOf(values.get("value")));
    }

    private List<Map<String, Object>> nestedValues(Map<String, Object> values) {
        if (!(values.get("value") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(AnnotationValue.class::isInstance)
            .map(AnnotationValue.class::cast)
            .map(AnnotationValue::getValue)
            .filter(AnnotationMirror.class::isInstance)
            .map(AnnotationMirror.class::cast)
            .map(this::values)
            .toList();
    }

    private Map<String, Object> values(AnnotationMirror mirror) {
        Map<String, Object> values = new LinkedHashMap<>();
        elementUtils.getElementValuesWithDefaults(mirror)
            .forEach((key, value) -> values.put(key.getSimpleName().toString(), value.getValue()));
        return values;
    }

    private static boolean isBuild(Object scope) {
        return String.valueOf(scope).endsWith("BUILD");
    }

    private DirectSourceDeclarations declarations() {
        return new DirectSourceDeclarations(dependencies, repositories, buildProperties, runtimeProperties);
    }
}
