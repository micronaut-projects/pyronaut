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
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import io.micronaut.core.annotation.Internal;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Logging bootstrap for Pyronaut JVM launcher commands.
 */
@Internal
public final class PyronautLauncherLogging {
    public static final String DEFAULT_APPLICATION_LOGBACK_CONFIGURATION = "pyronaut-default-logback.xml";

    private static final String SIMPLE_LOGGER_DEFAULT_LEVEL = "org.slf4j.simpleLogger.defaultLogLevel";
    private static final String LOGBACK_STATUS_LISTENER = "logback.statusListenerClass";
    private static final String LOGBACK_NOP_STATUS_LISTENER = "ch.qos.logback.core.status.NopStatusListener";
    private static final String LOGBACK_CONFIGURATION_FILE_PROPERTY = "logback.configurationFile";
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";
    private static final String CONSOLE_APPENDER_NAME = "PYRONAUT_LAUNCHER_CONSOLE";
    private static final String CONSOLE_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
    private static final String APPLICATION_CONSOLE_PATTERN = "%cyan(%d{yyyy-MM-dd HH:mm:ss.SSS}) %gray([%level]) %magenta(%logger{36}): %msg%n";

    private PyronautLauncherLogging() {
    }

    public static void initialize() {
        setDefaultProperty(SIMPLE_LOGGER_DEFAULT_LEVEL, "warn");
        setDefaultProperty(LOGBACK_STATUS_LISTENER, LOGBACK_NOP_STATUS_LISTENER);

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.WARN);
            if (!rootLogger.iteratorForAppenders().hasNext()) {
                rootLogger.addAppender(createConsoleAppender(loggerContext));
            }
        }
    }

    /**
     * Initialize default application logging for direct source execution.
     */
    public static void initializeApplicationDefaults(boolean verbose) {
        initializeApplicationDefaults(verbose ? "" : null);
    }

    /**
     * Initialize application logging, optionally enabling trace for one logger.
     * An empty logger name means the root logger; {@code null} leaves the
     * normal INFO root level in place.
     */
    public static void initializeApplicationDefaults(String verboseLogger) {
        setDefaultProperty(LOGBACK_STATUS_LISTENER, LOGBACK_NOP_STATUS_LISTENER);

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            loggerContext.reset();
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(verboseLogger != null && verboseLogger.isEmpty() ? Level.TRACE : Level.INFO);
            rootLogger.addAppender(createConsoleAppender(loggerContext, APPLICATION_CONSOLE_PATTERN));
            if (verboseLogger != null && !verboseLogger.isEmpty()) {
                loggerContext.getLogger(verboseLogger).setLevel(Level.TRACE);
            }
        }
    }

    /**
     * Configure Micronaut's default application logging resource when the user did not provide one.
     *
     * @return {@code true} when the default was applied
     */
    public static boolean setDefaultApplicationConfigurationProperty() {
        if (System.getProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY) == null
            && System.getProperty(LOGGER_CONFIG_PROPERTY) == null) {
            System.setProperty(LOGGER_CONFIG_PROPERTY, DEFAULT_APPLICATION_LOGBACK_CONFIGURATION);
            return true;
        }
        return false;
    }

    private static ConsoleAppender<ILoggingEvent> createConsoleAppender(LoggerContext loggerContext) {
        return createConsoleAppender(loggerContext, CONSOLE_PATTERN);
    }

    private static ConsoleAppender<ILoggingEvent> createConsoleAppender(LoggerContext loggerContext, String pattern) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern(pattern);
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(loggerContext);
        appender.setName(CONSOLE_APPENDER_NAME);
        appender.setEncoder(encoder);
        appender.start();
        return appender;
    }

    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }
}
