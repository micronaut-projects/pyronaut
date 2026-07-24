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
package io.micronaut.pyronaut.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.status.NopStatusListener;
import ch.qos.logback.core.spi.ContextAwareBase;
import io.micronaut.core.annotation.Internal;

import java.net.URL;

/**
 * Configures Pyronaut's launcher logging without requiring Logback to parse XML.
 */
@Internal
public final class PyronautLogbackConfigurator extends ContextAwareBase implements Configurator {
    private static final String LOGBACK_CONFIGURATION_FILE = "logback.configurationFile";
    private static final String LOGGER_CONFIG = "logger.config";

    @Override
    public ExecutionStatus configure(LoggerContext context) {
        setContext(context);
        if (Boolean.parseBoolean(System.getProperty(PyronautLauncherLogging.APPLICATION_DEFAULTS_MARKER))) {
            PyronautLauncherLogging.initializeApplicationDefaults(context, null);
            return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
        }
        if (hasExplicitConfiguration()) {
            return ExecutionStatus.INVOKE_NEXT_IF_ANY;
        }
        if (context.getStatusManager().getCopyOfStatusListenerList().stream()
            .noneMatch(NopStatusListener.class::isInstance)) {
            context.getStatusManager().add(new NopStatusListener());
        }
        PyronautLauncherLogging.initializeLauncherDefaults(context);
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }

    private static boolean hasExplicitConfiguration() {
        if (System.getProperty(LOGBACK_CONFIGURATION_FILE) != null || System.getProperty(LOGGER_CONFIG) != null) {
            return true;
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return resourceExists(classLoader, "logback-test.xml") || resourceExists(classLoader, "logback.xml");
    }

    private static boolean resourceExists(ClassLoader classLoader, String resourceName) {
        ClassLoader effectiveClassLoader = classLoader == null
            ? PyronautLogbackConfigurator.class.getClassLoader()
            : classLoader;
        URL resource = effectiveClassLoader.getResource(resourceName);
        return resource != null;
    }
}
