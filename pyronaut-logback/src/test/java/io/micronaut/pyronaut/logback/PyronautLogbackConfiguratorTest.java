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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.status.NopStatusListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautLogbackConfiguratorTest {
    private static final String CONFIGURATION_FILE = "logback.configurationFile";
    private static final String LOGGER_CONFIG = "logger.config";
    private static final String FALLBACK_STREAM = "pyronaut.logging.fallback-stream";

    private final LoggerContext context = new LoggerContext();
    private final PyronautLogbackConfigurator configurator = new PyronautLogbackConfigurator();

    @AfterEach
    void stopContext() {
        context.stop();
        System.clearProperty(CONFIGURATION_FILE);
        System.clearProperty(LOGGER_CONFIG);
        System.clearProperty(FALLBACK_STREAM);
        System.clearProperty(PyronautLauncherLogging.PYTHON_LOGGING_CONFIGURED);
        System.clearProperty(PyronautLauncherLogging.APPLICATION_DEFAULTS_MARKER);
    }

    @Test
    void serviceProviderIsRegistered() {
        URL service = getClass().getClassLoader().getResource(
            "META-INF/services/ch.qos.logback.classic.spi.Configurator"
        );

        assertNotNull(service);
        assertEquals(
            PyronautLogbackConfigurator.class.getName(),
            read(service).trim()
        );
    }

    @Test
    void configuresLauncherDefaultsWithoutJoran() {
        Configurator.ExecutionStatus status = configurator.configure(context);

        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY, status);
        assertEquals(Level.WARN, rootLogger.getLevel());
        assertTrue(context.getStatusManager().getCopyOfStatusListenerList().stream()
            .anyMatch(NopStatusListener.class::isInstance));
        ConsoleAppender<?> appender = (ConsoleAppender<?>) rootLogger.iteratorForAppenders().next();
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) appender.getEncoder();
        assertEquals("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n", encoder.getPattern());
    }

    @Test
    void configuresApplicationDefaultsWithoutJoran() {
        PyronautLauncherLogging.initializeApplicationDefaults(context, "io.example, regex");

        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        assertEquals(Level.INFO, rootLogger.getLevel());
        assertEquals(Level.TRACE, context.getLogger("io.example").getLevel());
        assertEquals(Level.TRACE, context.getLogger("regex").getLevel());
        assertEquals(Level.INFO, context.getLogger("com.oracle.graal.python.runtime").getLevel());
        ConsoleAppender<?> appender = (ConsoleAppender<?>) rootLogger.iteratorForAppenders().next();
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) appender.getEncoder();
        assertEquals(
            "%cyan(%d{yyyy-MM-dd HH:mm:ss.SSS}) %gray([%level]) %magenta(%logger{36}): %msg%n",
            encoder.getPattern()
        );
    }

    @Test
    void limitsNoisyVerboseLoggersByDefault() {
        PyronautLauncherLogging.initializeApplicationDefaults(context, "");

        assertEquals(Level.TRACE, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel());
        assertEquals(Level.INFO, context.getLogger("regex").getEffectiveLevel());
        assertEquals(Level.INFO, context.getLogger("com.oracle.graal.python.runtime").getEffectiveLevel());
        assertEquals(Level.INFO, context.getLogger("com.oracle.graal.python.runtime.LoggingPosixSupport").getEffectiveLevel());
        assertEquals(Level.INFO, context.getLogger("io.micronaut.core.reflect.ClassUtils").getEffectiveLevel());
        assertEquals(Level.INFO, context.getLogger("io.micronaut.inject.qualifiers.MatchArgumentQualifier").getEffectiveLevel());
    }

    @Test
    void reappliesVerboseLoggerOverridesAfterPythonLoggingConfiguration() {
        PyronautLauncherLogging.initializeApplicationDefaults(context, "");
        LogbackConfigurer.configure(Map.of("root", Map.of("level", "DEBUG")));

        assertEquals(Level.INFO, context.getLogger("regex").getEffectiveLevel());
        assertEquals(Level.INFO, context.getLogger("com.oracle.graal.python.runtime").getEffectiveLevel());
    }

    @Test
    void preservesExplicitPythonLoggerConfiguration() {
        PyronautLauncherLogging.initializeApplicationDefaults(context, "");
        context.getLogger("regex").setLevel(Level.DEBUG);
        context.getLogger("com.oracle.graal.python.runtime").setLevel(Level.TRACE);
        PyronautLauncherLogging.configureVerboseLoggerLevels(
            context,
            "",
            Set.of("regex", "com.oracle.graal.python.runtime")
        );

        assertEquals(Level.DEBUG, context.getLogger("regex").getEffectiveLevel());
        assertEquals(Level.TRACE, context.getLogger("com.oracle.graal.python.runtime").getEffectiveLevel());
    }

    @Test
    void usesConfiguredFallbackStream() {
        System.setProperty(FALLBACK_STREAM, "stderr");

        PyronautLauncherLogging.initializeApplicationDefaults(context, null);

        ConsoleAppender<?> appender = (ConsoleAppender<?>) context
            .getLogger(Logger.ROOT_LOGGER_NAME)
            .iteratorForAppenders()
            .next();
        assertEquals("System.err", appender.getTarget());
    }

    @Test
    void doesNotReplaceApplicationDefaultsWhenInvokedAgain() {
        PyronautLauncherLogging.initializeApplicationDefaults(context, null);

        assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY, configurator.configure(context));
        assertEquals(Level.INFO, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel());
    }

    @Test
    void defersWhenExplicitSystemConfigurationIsPresent() {
        System.setProperty(CONFIGURATION_FILE, "custom.xml");

        assertEquals(Configurator.ExecutionStatus.INVOKE_NEXT_IF_ANY, configurator.configure(context));
        assertFalse(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
    }

    @Test
    void defersWhenLoggerConfigIsPresent() {
        System.setProperty(LOGGER_CONFIG, "custom.xml");

        assertEquals(Configurator.ExecutionStatus.INVOKE_NEXT_IF_ANY, configurator.configure(context));
        assertFalse(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
    }

    @Test
    void defersWhenClasspathConfigurationIsPresent() throws Exception {
        Path tempDirectory = Files.createTempDirectory("pyronaut-logback-config");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            for (String configurationFile : new String[]{"logback.xml", "logback-test.xml"}) {
                Files.writeString(tempDirectory.resolve(configurationFile), "<configuration/>\n");
                try (URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{tempDirectory.toUri().toURL()},
                    previous
                )) {
                    Thread.currentThread().setContextClassLoader(classLoader);
                    assertEquals(Configurator.ExecutionStatus.INVOKE_NEXT_IF_ANY, configurator.configure(context));
                    assertFalse(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
                }
                Files.deleteIfExists(tempDirectory.resolve(configurationFile));
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            Files.deleteIfExists(tempDirectory.resolve("logback-test.xml"));
            Files.deleteIfExists(tempDirectory.resolve("logback.xml"));
            Files.deleteIfExists(tempDirectory);
        }
    }

    private static String read(URL url) {
        try (var inputStream = url.openStream()) {
            return new String(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
