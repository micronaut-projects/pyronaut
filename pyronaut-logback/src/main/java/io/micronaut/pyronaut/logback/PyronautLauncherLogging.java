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
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import io.micronaut.core.annotation.Internal;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.Arrays;
import java.util.Set;

/**
 * Logging bootstrap for Pyronaut JVM launcher commands.
 */
@Internal
public final class PyronautLauncherLogging {
    static final String APPLICATION_DEFAULTS_MARKER = "pyronaut.application.logging.defaults";
    private static final String SIMPLE_LOGGER_DEFAULT_LEVEL = "org.slf4j.simpleLogger.defaultLogLevel";
    private static final String LOGBACK_STATUS_LISTENER = "logback.statusListenerClass";
    private static final String LOGBACK_NOP_STATUS_LISTENER = "ch.qos.logback.core.status.NopStatusListener";
    private static final String LOGBACK_CONFIGURATION_FILE_PROPERTY = "logback.configurationFile";
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";
    static final String PYTHON_LOGGING_CONFIGURED = "pyronaut.python.logging.configured";
    static final String VERBOSE_LOGGER_PROPERTY = "pyronaut.logging.verbose-loggers";
    private static final String FALLBACK_STREAM_PROPERTY = "pyronaut.logging.fallback-stream";
    private static final String FALLBACK_STREAM_ENVIRONMENT = "PYRONAUT_LOGGING_FALLBACK_STREAM";
    private static final String CONSOLE_APPENDER_NAME = "PYRONAUT_LAUNCHER_CONSOLE";
    private static final String CONSOLE_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
    private static final String APPLICATION_CONSOLE_PATTERN = "%cyan(%d{yyyy-MM-dd HH:mm:ss.SSS}) %gray([%level]) %magenta(%logger{36}): %msg%n";
    private static final String[] VERBOSE_DEFAULT_INFO_LOGGERS = {
        "regex",
        "regex.Phases",
        "com.oracle.graal.python.runtime",
        "com.oracle.graal.python.runtime.LoggingPosixSupport",
        "io.micronaut.core.reflect.ClassUtils",
        "io.micronaut.inject.qualifiers.MatchArgumentQualifier"
    };

    private PyronautLauncherLogging() {
    }

    public static void initialize() {
        setDefaultProperty(SIMPLE_LOGGER_DEFAULT_LEVEL, "warn");
        setDefaultProperty(LOGBACK_STATUS_LISTENER, LOGBACK_NOP_STATUS_LISTENER);

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            initializeLauncherDefaults(loggerContext);
        }
    }

    static void initializeLauncherDefaults(LoggerContext loggerContext) {
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.WARN);
        if (!rootLogger.iteratorForAppenders().hasNext()) {
            rootLogger.addAppender(createConsoleAppender(loggerContext));
        }
    }

    /**
     * Initialize default application logging for direct source execution.
     *
     * @param verbose whether to enable trace logging at the root logger
     */
    public static void initializeApplicationDefaults(boolean verbose) {
        initializeApplicationDefaults(verbose ? "" : null);
    }

    /**
     * Initialize application logging, optionally enabling trace for one or more loggers.
     * An empty logger name means the root logger; {@code null} leaves the
     * normal INFO root level in place.
     *
     * @param verboseLogger comma-separated logger names to trace, or {@code null} for normal INFO logging
     */
    public static void initializeApplicationDefaults(String verboseLogger) {
        setDefaultProperty(LOGBACK_STATUS_LISTENER, LOGBACK_NOP_STATUS_LISTENER);
        if (verboseLogger == null) {
            System.clearProperty(VERBOSE_LOGGER_PROPERTY);
        } else {
            System.setProperty(VERBOSE_LOGGER_PROPERTY, verboseLogger);
        }

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            initializeApplicationDefaults(loggerContext, verboseLogger);
        }
    }

    static void initializeApplicationDefaults(LoggerContext loggerContext, String verboseLogger) {
        loggerContext.reset();
        System.setProperty(APPLICATION_DEFAULTS_MARKER, Boolean.TRUE.toString());
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(verboseLogger != null && verboseLogger.isEmpty() ? Level.TRACE : Level.INFO);
        rootLogger.addAppender(createConsoleAppender(loggerContext, APPLICATION_CONSOLE_PATTERN));
        configureVerboseLoggerLevels(loggerContext, verboseLogger);
        installVerboseLoggerFilter(loggerContext, verboseLogger, Set.of());
    }

    /**
     * Reapply the verbose logger policy after framework bootstrap. GraalPy can
     * initialize its logging configuration while the application context is
     * being built, so this must happen immediately before application startup.
     */
    public static void reapplyVerboseLoggerDefaults(String verboseLogger) {
        if (verboseLogger == null) {
            return;
        }
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            configureVerboseLoggerLevels(loggerContext, verboseLogger);
            installVerboseLoggerFilter(loggerContext, verboseLogger, Set.of());
        }
    }

    static void configureVerboseLoggerLevels(LoggerContext loggerContext, String verboseLogger) {
        configureVerboseLoggerLevels(loggerContext, verboseLogger, Set.of());
    }

    static void configureVerboseLoggerLevels(LoggerContext loggerContext, String verboseLogger, Set<String> explicitlyConfiguredLoggers) {
        if (verboseLogger == null) {
            return;
        }
        Arrays.stream(VERBOSE_DEFAULT_INFO_LOGGERS)
            .filter(logger -> !isExplicitlyConfigured(logger, explicitlyConfiguredLoggers))
            .map(loggerContext::getLogger)
            .forEach(logger -> logger.setLevel(Level.INFO));
        if (!verboseLogger.isEmpty()) {
            Arrays.stream(verboseLogger.split(","))
                .map(String::trim)
                .filter(logger -> !logger.isEmpty())
                .filter(logger -> !isExplicitlyConfigured(logger, explicitlyConfiguredLoggers))
                .map(loggerContext::getLogger)
                .forEach(logger -> logger.setLevel(Level.TRACE));
        }
    }

    static void installVerboseLoggerFilter(LoggerContext loggerContext, String verboseLogger, Set<String> explicitlyConfiguredLoggers) {
        if (verboseLogger == null) {
            return;
        }
        VerboseLoggerFilter filter = loggerContext.getTurboFilterList().stream()
            .filter(VerboseLoggerFilter.class::isInstance)
            .map(VerboseLoggerFilter.class::cast)
            .findFirst()
            .orElse(null);
        if (filter == null) {
            loggerContext.addTurboFilter(new VerboseLoggerFilter(verboseLogger, explicitlyConfiguredLoggers));
        } else {
            filter.update(verboseLogger, explicitlyConfiguredLoggers);
        }
    }

    private static boolean isExplicitlyConfigured(String logger, Set<String> explicitlyConfiguredLoggers) {
        return explicitlyConfiguredLoggers.stream()
            .anyMatch(configured -> logger.equals(configured) || logger.startsWith(configured + "."));
    }

    private static final class VerboseLoggerFilter extends TurboFilter {
        private Set<String> explicitlyConfiguredLoggers;
        private Set<String> traceLoggers;

        private VerboseLoggerFilter(String verboseLogger, Set<String> explicitlyConfiguredLoggers) {
            this.explicitlyConfiguredLoggers = explicitlyConfiguredLoggers;
            this.traceLoggers = verboseLogger.isEmpty() ? Set.of() : Set.copyOf(Arrays.stream(verboseLogger.split(","))
                .map(String::trim)
                .filter(logger -> !logger.isEmpty())
                .toList());
        }

        private void update(String verboseLogger, Set<String> explicitlyConfiguredLoggers) {
            this.explicitlyConfiguredLoggers = explicitlyConfiguredLoggers;
            this.traceLoggers = verboseLogger.isEmpty() ? Set.of() : Set.copyOf(Arrays.stream(verboseLogger.split(","))
                .map(String::trim)
                .filter(logger -> !logger.isEmpty())
                .toList());
        }

        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level,
                                  String format, Object[] params, Throwable throwable) {
            if (level.isGreaterOrEqual(Level.INFO)
                || !isSuppressedLogger(logger.getName())
                || isExplicitlyConfigured(logger.getName(), explicitlyConfiguredLoggers)) {
                return FilterReply.NEUTRAL;
            }
            return FilterReply.DENY;
        }

        private boolean isSuppressedLogger(String loggerName) {
            return Arrays.stream(VERBOSE_DEFAULT_INFO_LOGGERS)
                .anyMatch(configured -> matchesLogger(loggerName, configured))
                && traceLoggers.stream().noneMatch(configured -> matchesLogger(loggerName, configured));
        }

        private boolean matchesLogger(String loggerName, String configured) {
            return loggerName.equals(configured)
                || loggerName.startsWith(configured + ".")
                || (configured.endsWith("LoggingPosixSupport") && loggerName.endsWith(".LoggingPosixSupport"))
                || (configured.endsWith("ClassUtils") && loggerName.endsWith(".ClassUtils"))
                || (configured.endsWith("MatchArgumentQualifier") && loggerName.endsWith(".MatchArgumentQualifier"));
        }
    }

    /**
     * Determine whether Pyronaut should use its programmatic application logging defaults.
     *
     * @return {@code true} when no explicit application configuration was provided
     */
    public static boolean shouldInitializeApplicationDefaults() {
        return shouldInitializeApplicationDefaults(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Determine whether Pyronaut should use its defaults for a particular application class loader.
     *
     * @param classLoader the application class loader
     * @return {@code true} when no explicit application configuration was provided
     */
    public static boolean shouldInitializeApplicationDefaults(ClassLoader classLoader) {
        if (System.getProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY) != null
            || System.getProperty(LOGGER_CONFIG_PROPERTY) != null) {
            return false;
        }
        return true;
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
        appender.setTarget(consoleTarget());
        appender.setEncoder(encoder);
        appender.start();
        return appender;
    }

    private static String consoleTarget() {
        String stream = System.getProperty(FALLBACK_STREAM_PROPERTY);
        if (stream == null) {
            stream = System.getenv(FALLBACK_STREAM_ENVIRONMENT);
        }
        return "stderr".equalsIgnoreCase(stream) ? "System.err" : "System.out";
    }

    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }
}
