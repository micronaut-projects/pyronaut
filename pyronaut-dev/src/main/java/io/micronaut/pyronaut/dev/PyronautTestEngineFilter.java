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

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the tests of the engines {@code tool.pyronaut.test.engine} selects, in the runs of {@code pyronaut test -t}
 * on the JVM toolchain, as the test command's engine filter does: the test mode of the development runtime runs every
 * engine on the classpath. Registered as a service of the JUnit Platform; it keeps every test unless
 * {@link PyronautTestReload} named the engines.
 */
public final class PyronautTestEngineFilter implements PostDiscoveryFilter {

    private final Set<String> engines;

    /**
     * Created by the service loader.
     */
    public PyronautTestEngineFilter() {
        this(System.getProperty(PyronautTestReload.ENGINES_PROPERTY, ""));
    }

    PyronautTestEngineFilter(String engines) {
        this.engines = Arrays.stream(engines.split(","))
            .map(String::strip)
            .filter(engine -> !engine.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (engines.isEmpty()) {
            return FilterResult.included("every engine runs");
        }
        // the engine that discovered the test, by its root: the pytest engine roots the identifiers of its tests at
        // an engine segment of its own, pytest-engine, rather than at its identifier
        TestDescriptor root = descriptor;
        while (root.getParent().isPresent()) {
            root = root.getParent().get();
        }
        String engine = root.getUniqueId().getEngineId().orElse("");
        return engines.contains(engine)
            ? FilterResult.included("tool.pyronaut.test.engine selects " + engine)
            : FilterResult.excluded("tool.pyronaut.test.engine does not select " + engine);
    }
}
