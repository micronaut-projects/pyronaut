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
package io.micronaut.pyronaut.dev;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

/** Collects direct-source Java declarations before normal Micronaut processing. */
@SupportedAnnotationTypes({
    "pyronaut.build.Dependency", "pyronaut.build.Dependencies",
    "pyronaut.build.MavenRepository", "pyronaut.build.MavenRepositories",
    "pyronaut.build.AppConfig", "pyronaut.build.AppConfigs"
})
final class DirectSourceDeclarationsProcessor extends AbstractProcessor {
    private boolean raised;
    private final List<DependencyResolveRequest.Declaration> dependencies;
    private final List<String> repositories;
    private final Map<String, String> buildProperties;
    private final Map<String, String> runtimeProperties;
    private Elements elementUtils;

    DirectSourceDeclarationsProcessor() {
        dependencies = new ArrayList<>();
        repositories = new ArrayList<>();
        buildProperties = new LinkedHashMap<>();
        runtimeProperties = new LinkedHashMap<>();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        elementUtils = processingEnvironment.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (raised || roundEnvironment.processingOver() || Boolean.getBoolean("pyronaut.direct.source.declarations.resolved")) {
            return false;
        }
        for (Element root : roundEnvironment.getRootElements()) collect(root);
        if (!dependencies.isEmpty() || !repositories.isEmpty() || !buildProperties.isEmpty() || !runtimeProperties.isEmpty()) {
            raised = true;
            throw new DependencyResolveRequest(dependencies, repositories, buildProperties, runtimeProperties);
        }
        return false;
    }

    private void collect(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String name = mirror.getAnnotationType().toString();
            if (name.startsWith("/")) name = name.substring(1);
            Map<String, Object> values = new LinkedHashMap<>();
            elementUtils.getElementValuesWithDefaults(mirror).forEach((key, value) -> values.put(key.getSimpleName().toString(), value.getValue()));
            if (name.equals("pyronaut.build.Dependency")) {
                String coordinate = values.get("group") + ":" + values.get("module");
                if (!String.valueOf(values.get("version")).isBlank()) coordinate += ":" + values.get("version");
                dependencies.add(new DependencyResolveRequest.Declaration(coordinate, String.valueOf(values.get("scope")).endsWith("BUILD")));
            } else if (name.equals("pyronaut.build.MavenRepository")) {
                repositories.add(String.valueOf(values.get("value")));
            } else if (name.equals("pyronaut.build.AppConfig")) {
                (String.valueOf(values.get("scope")).endsWith("BUILD") ? buildProperties : runtimeProperties)
                    .put(String.valueOf(values.get("name")), String.valueOf(values.get("value")));
            }
        }
        for (Element enclosed : element.getEnclosedElements()) collect(enclosed);
    }
}
