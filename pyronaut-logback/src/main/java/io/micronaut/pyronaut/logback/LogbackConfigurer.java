/*
 * Copyright 2017-2025 original authors
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
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;

/**
 * Utility class for programmatic configuration of logback.
 * This allows Python code to configure logback appenders and loggers.
 *
 * @author Micronaut Team
 * @since 1.0.0
 */
public final class LogbackConfigurer {

    private static final Map<String, Appender<ILoggingEvent>> APPENDERS = new HashMap<>();
    private static final Map<String, String> FORMATTERS = new HashMap<>();
    private static final Map<String, Logger> CONFIGURED_LOGGERS = new HashMap<>();

    private LogbackConfigurer() {
    }

    /**
     * Configure logback with a dictConfig-like structure.
     * Supports formatters, handlers, root logger, and named loggers.
     *
     * @param config the configuration map
     */
    @SuppressWarnings("unchecked")
    public static synchronized void configure(Map<String, Object> config) {
        LoggerContext lc = initialize();

        APPENDERS.clear();
        FORMATTERS.clear();


        // Configure formatters first
        Map<String, Object> formatterConfigs = (Map<String, Object>) config.get("formatters");
        if (formatterConfigs != null) {
            for (Map.Entry<String, Object> entry : formatterConfigs.entrySet()) {
                String formatterName = entry.getKey();
                Map<String, Object> formatterConfig = (Map<String, Object>) entry.getValue();
                addFormatter(formatterName, formatterConfig);
            }
        }

        // Configure handlers
        Map<String, Object> handlers = (Map<String, Object>) config.get("handlers");
        if (handlers != null) {
            for (Map.Entry<String, Object> entry : handlers.entrySet()) {
                try {
                    String handlerName = entry.getKey();
                    Map<String, Object> handlerConfig = (Map<String, Object>) entry.getValue();
                    addAppender(lc, handlerName, handlerConfig);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid handler configuration for '" + entry.getKey() + "': " + e.getMessage(), e);
                }
            }
        }

        // If no handlers defined, add default console
        if (APPENDERS.isEmpty()) {
            addDefaultConsoleAppender(lc);
        }

        // Configure root logger
        Logger rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
        Map<String, Object> rootConfig = (Map<String, Object>) config.get("root");
        if (rootConfig != null) {
            configureLogger(rootLogger, rootConfig);
        } else {
            // Default root config
            rootLogger.setLevel(Level.INFO);
            for (Appender<ILoggingEvent> appender : APPENDERS.values()) {
                rootLogger.addAppender(appender);
            }
        }

        // Configure other loggers
        Map<String, Object> loggers = (Map<String, Object>) config.get("loggers");
        if (loggers != null) {
            for (Map.Entry<String, Object> entry : loggers.entrySet()) {
                String loggerName = entry.getKey();
                Map<String, Object> loggerConfig = (Map<String, Object>) entry.getValue();
                Logger logger = lc.getLogger(loggerName);
                configureLogger(logger, loggerConfig);
            }
        }
    }

    /**
     * Initialize the logback context.
     *
     * @return The logback context.
     */
    public static @NonNull LoggerContext initialize() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        lc.reset(); // Reset existing configuration
        installJulBridge();
        return lc;
    }

    /**
     * Add a formatter configuration.
     */
    private static void addFormatter(String name, Map<String, Object> config) {
        String format = (String) config.get("format");
        if (format != null) {
            // Translate Python logging format to logback pattern
            String logbackPattern = translatePythonFormatToLogback(format);
            FORMATTERS.put(name, logbackPattern);
        } else {
            throw new IllegalArgumentException("Formatter '" + name + "' missing 'format' configuration");
        }
    }

    /**
     * Translate Python logging format string to logback pattern.
     */
    private static String translatePythonFormatToLogback(String pythonFormat) {
        if (pythonFormat == null) {
            return "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
        }
        // Basic translation - this could be extended for more complex formats
        String logbackPattern = pythonFormat
            .replace("%(asctime)s", "%d{yyyy-MM-dd HH:mm:ss.SSS}")
            .replace("%(levelname)s", "%level")
            .replace("%(name)s", "%logger")
            .replace("%(message)s", "%msg");

        // Ensure the pattern ends with %n for proper line separation
        if (!logbackPattern.endsWith("%n")) {
            logbackPattern += "%n";
        }

        return logbackPattern;
    }

    /**
     * Add an appender based on handler config.
     */
    @SuppressWarnings("unchecked")
    private static void addAppender(LoggerContext lc, String name, Map<String, Object> config) {
        String clazz = (String) config.get("class");
        if (clazz == null) {
            throw new IllegalArgumentException("Handler '" + name + "' missing 'class' configuration");
        }

        String formatter = (String) config.get("formatter");
        if (formatter != null && !FORMATTERS.containsKey(formatter)) {
            throw new IllegalArgumentException("Formatter '" + formatter + "' not found in formatters configuration");
        }
        String pattern = formatter != null ? FORMATTERS.get(formatter) : "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
        String level = (String) config.get("level");

        Appender<ILoggingEvent> appender = null;
        if (clazz.contains("StreamHandler")) {
            appender = createConsoleAppender(lc, pattern);
        } else if (clazz.contains("FileHandler")) {
            String filename = (String) config.get("filename");
            if (filename == null) {
                throw new IllegalArgumentException("FileHandler '" + name + "' missing 'filename' configuration");
            }
            appender = createFileAppender(lc, filename, pattern, config.get("delay") instanceof Boolean b ? b : false);
        } else if (clazz.contains("RotatingFileHandler")) {
            String filename = (String) config.get("filename");
            if (filename == null) {
                throw new IllegalArgumentException("RotatingFileHandler '" + name + "' missing 'filename' configuration");
            }
            Number maxBytes = (Number) config.get("maxBytes");
            Number backupCount = (Number) config.get("backupCount");
            appender = createRollingFileAppender(lc, filename, pattern, maxBytes, backupCount);
        } else {
            // Try to instantiate the specified Logback appender class
            appender = createAppenderFromClass(lc, clazz, config);
        }

        if (appender != null) {
            appender.setName(name);
            APPENDERS.put(name, appender);
        }
    }

    /**
     * Configure a logger with the given config.
     */
    @SuppressWarnings("unchecked")
    private static void configureLogger(Logger logger, Map<String, Object> config) {
        // Set level
        String level = (String) config.get("level");
        if (level != null) {
            // Map Python levels to logback levels
            if ("TRACE".equals(level)) {
                logger.setLevel(Level.DEBUG); // Map TRACE to DEBUG
            } else {
                logger.setLevel(Level.toLevel(level));
            }
        }

        // Set handlers
        List<String> handlerNames = (List<String>) config.get("handlers");
        if (handlerNames != null) {
            // Remove existing appenders
            logger.detachAndStopAllAppenders();
            
            // Add specified appenders
            for (String handlerName : handlerNames) {
                Appender<ILoggingEvent> appender = APPENDERS.get(handlerName);
                if (appender != null) {
                    logger.addAppender(appender);
                }
            }
        }

        // Set propagate (additive in logback terms)
        Boolean propagate = (Boolean) config.get("propagate");
        if (propagate != null) {
            logger.setAdditive(propagate);
        }

        // Store the configured logger
        CONFIGURED_LOGGERS.put(logger.getName(), logger);
    }

    /**
     * Create a console appender.
     */
    private static ConsoleAppender<ILoggingEvent> createConsoleAppender(LoggerContext lc, String pattern) {
        PatternLayoutEncoder ple = new PatternLayoutEncoder();
        ple.setPattern(pattern);
        ple.setContext(lc);
        ple.start();

        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
        consoleAppender.setEncoder(ple);
        consoleAppender.setContext(lc);
        consoleAppender.start();

        return consoleAppender;
    }

    /**
     * Create a file appender.
     */
    private static FileAppender<ILoggingEvent> createFileAppender(LoggerContext lc, String filename, String pattern, boolean delay) {
        PatternLayoutEncoder ple = new PatternLayoutEncoder();
        ple.setPattern(pattern);
        ple.setContext(lc);
        ple.start();

        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setFile(filename);
        fileAppender.setEncoder(ple);
        fileAppender.setContext(lc);
        fileAppender.setImmediateFlush(!delay);
        fileAppender.start();

        return fileAppender;
    }

    /**
     * Create a rolling file appender.
     */
    private static RollingFileAppender<ILoggingEvent> createRollingFileAppender(LoggerContext lc, String filename, String pattern, Number maxBytes, Number backupCount) {
        PatternLayoutEncoder ple = new PatternLayoutEncoder();
        ple.setPattern(pattern);
        ple.setContext(lc);
        ple.start();

        RollingFileAppender<ILoggingEvent> rollingAppender = new RollingFileAppender<>();
        rollingAppender.setFile(filename);
        rollingAppender.setEncoder(ple);
        rollingAppender.setContext(lc);
        rollingAppender.setImmediateFlush(true);

        // Create and configure rolling policy
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new SizeAndTimeBasedRollingPolicy<>();
        rollingPolicy.setContext(lc);
        rollingPolicy.setParent(rollingAppender);
        rollingPolicy.setFileNamePattern(filename + ".%d{yyyy-MM-dd}.%i.gz");
        
        if (maxBytes != null) {
            rollingPolicy.setMaxFileSize(ch.qos.logback.core.util.FileSize.valueOf(String.valueOf(maxBytes.longValue())));
        } else {
            rollingPolicy.setMaxFileSize(ch.qos.logback.core.util.FileSize.valueOf("10MB"));
        }
        
        if (backupCount != null) {
            rollingPolicy.setMaxHistory(backupCount.intValue());
        } else {
            rollingPolicy.setMaxHistory(5);
        }
        
        rollingPolicy.start();
        rollingAppender.setRollingPolicy(rollingPolicy);
        rollingAppender.start();

        return rollingAppender;
    }

    /**
     * Add a default console appender.
     */
    private static void addDefaultConsoleAppender(LoggerContext lc) {
        ConsoleAppender<ILoggingEvent> consoleAppender = createConsoleAppender(lc, "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        APPENDERS.put("console", consoleAppender);
    }

    /**
     * Create an appender from an arbitrary Logback class using reflection.
     */
    @SuppressWarnings("unchecked")
    private static Appender<ILoggingEvent> createAppenderFromClass(LoggerContext lc, String className, Map<String, Object> config) {
        try {
            // Load the class using the same classloader as the LoggerContext
            Class<?> clazz = Class.forName(className, true, lc.getClass().getClassLoader());

            // Check if it's an Appender
            if (!Appender.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("Class " + className + " is not a Logback Appender");
            }

            // Find constructor that takes LoggerContext
            Constructor<?> constructor = null;
            try {
                constructor = clazz.getConstructor(lc.getClass());
            } catch (NoSuchMethodException e) {
                // Try default constructor
                constructor = clazz.getConstructor();
            }

            // Instantiate the appender
            Appender<ILoggingEvent> appender;
            if (constructor.getParameterCount() == 1) {
                appender = (Appender<ILoggingEvent>) constructor.newInstance(lc);
            } else {

                appender = (Appender<ILoggingEvent>) constructor.newInstance();
                // Set context if available
                try {
                    Method setContext = clazz.getMethod("setContext", lc.getClass());
                    setContext.setAccessible(true);
                    setContext.invoke(appender, lc);
                } catch (Exception e) {
                    // Some appenders don't need context
                }
            }

            // Configure properties
            configureAppenderProperties(appender, config);

            // Set up encoder if the appender supports it
            try {
                Method setEncoder = clazz.getMethod("setEncoder", ch.qos.logback.core.encoder.Encoder.class);
                String formatter = (String) config.get("formatter");
                String pattern = formatter != null ? FORMATTERS.get(formatter) : "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

                PatternLayoutEncoder ple = new PatternLayoutEncoder();
                ple.setPattern(pattern);
                ple.setContext(lc);
                ple.start();

                setEncoder.invoke(appender, ple);
            } catch (NoSuchMethodException ignored) {
                // Appender doesn't support encoders
            }

            // Start the appender
            appender.start();

            return appender;

        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Appender class not found: " + className, e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create appender " + className + ": " + e.getMessage(), e);
        }
    }

    /**
     * Configure appender properties using reflection.
     */
    private static void configureAppenderProperties(Appender<ILoggingEvent> appender, Map<String, Object> config) throws Exception {
        Class<?> clazz = appender.getClass();

        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Skip special configuration keys
            if ("class".equals(key) || "formatter".equals(key) || "level".equals(key)) {
                continue;
            }

            // Convert property name to method names
            String setterName = "set" + capitalize(key);
            String adderName = "add" + capitalize(key);

            // Handle list values (call adder multiple times)
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                try {
                    Method adder = clazz.getMethod(adderName, String.class);
                    for (Object item : list) {
                        adder.invoke(appender, convertToString(item));
                    }
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("No adder method " + adderName + " for property " + key + " in " + clazz.getName());
                }
            } else {
                // Try setter first, then adder
                boolean set = false;
                try {
                    Method setter = clazz.getMethod(setterName, String.class);
                    setter.invoke(appender, convertToString(value));
                    set = true;
                } catch (NoSuchMethodException e) {
                    // Try adder
                    try {
                        Method adder = clazz.getMethod(adderName, String.class);
                        adder.invoke(appender, convertToString(value));
                        set = true;
                    } catch (NoSuchMethodException e2) {
                        // Ignore, some properties might not be settable
                    }
                }
                if (!set) {
                    System.err.println("Warning: Could not set property " + key + " on " + clazz.getName());
                }
            }
        }
    }

    /**
     * Capitalize the first letter of a string.
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /**
     * Convert an object to string.
     */
    private static String convertToString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    /**
     * Log a message directly to logback, bypassing SLF4J.
     * This is used by Python loggers to ensure logging works in GraalPy environments.
     */
    @Internal
    public static void log(String name, String level, String message) {
        String loggerKey = name != null && !name.isEmpty() ? name : Logger.ROOT_LOGGER_NAME;
        Logger logger = CONFIGURED_LOGGERS.get(loggerKey);
        if (logger == null) {
            logger = (Logger) LoggerFactory.getLogger(name != null ? name : "");
        }
        if (logger != null) {
            switch (level.toUpperCase()) {
                case "TRACE":
                    logger.trace(message);
                    break;
                case "DEBUG":
                    logger.debug(message);
                    break;
                case "WARN":
                case "WARNING":
                    logger.warn(message);
                    break;
                case "ERROR":
                    logger.error(message);
                    break;
                default:
                    logger.info(message);
                    break;
            }
        }
    }

    /**
     * Get or create a logger with the given name.
     *
     * @param name the logger name
     * @return the logger
     */
    public static Logger getLogger(String name) {
        return (Logger) LoggerFactory.getLogger(name != null ? name : "");
    }

    private static void installJulBridge() {
        try {
            ClassLoader cl = LogbackConfigurer.class.getClassLoader();
            resetGraalpyLogger(cl);
            Class<?> bridge = Class.forName("org.slf4j.bridge.SLF4JBridgeHandler", false, cl);
            Method removeHandlers = bridge.getMethod("removeHandlersForRootLogger");
            removeHandlers.invoke(null);
            Method isInstalled = bridge.getMethod("isInstalled");
            boolean installed = (Boolean) isInstalled.invoke(null);
            if (!installed) {
                Method install = bridge.getMethod("install");
                install.invoke(null);
            }

            java.util.logging.Logger logger = java.util.logging.Logger.getLogger("org.graalvm.python.embedding.VirtualFileSystem");
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            Handler[] handlers = rootLogger.getHandlers();
            for (Handler handler : handlers) {
                logger.addHandler(handler);
            }

        } catch (ClassNotFoundException e) {
            // classpath missing
        } catch (Throwable t) {
            System.err.println("Warning: Failed to install JUL-to-SLF4J bridge: " + t.getMessage());
        }
    }

    private static void resetGraalpyLogger(ClassLoader cl) {
        try {
            Class.forName("org.graalvm.python.embedding.VirtualFileSystemImpl", true, cl);
        } catch (ClassNotFoundException e) {
            // ignore
        }
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("org.graalvm.python.embedding.VirtualFileSystem");
        Handler[] handlers = logger.getHandlers();
        for (Handler handler : handlers) {
            logger.removeHandler(handler);
        }
        logger.setUseParentHandlers(true);
    }
}

