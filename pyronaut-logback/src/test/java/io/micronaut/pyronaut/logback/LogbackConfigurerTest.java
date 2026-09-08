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

import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LogbackConfigurer that verify logback configuration works.
 */
class LogbackConfigurerTest {

    @Test
    void testLogbackConfigurerCanBeConfigured() {
        Map<String, Object> config = new HashMap<>();
        
        // Test basic configuration
        LogbackConfigurer.configure(config);
        
        // Verify that configuration doesn't throw exceptions
        Logger logger = LoggerFactory.getLogger("test.config");
        assertNotNull(logger);
    }

    @Test
    void testLogbackConfigurerWithHandlers() {
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> handlers = new HashMap<>();
        
        // Add console handler
        Map<String, Object> consoleHandler = new HashMap<>();
        consoleHandler.put("class", "logging.StreamHandler");
        handlers.put("console", consoleHandler);
        
        // Add file handler
        Map<String, Object> fileHandler = new HashMap<>();
        fileHandler.put("class", "logging.FileHandler");
        fileHandler.put("filename", "test.log");
        handlers.put("file", fileHandler);
        
        config.put("handlers", handlers);
        
        // Configure logback
        LogbackConfigurer.configure(config);
        
        // Verify loggers can be created
        Logger logger = LoggerFactory.getLogger("test.handlers");
        assertNotNull(logger);
    }

    @Test
    void testStreamHandlerCanTargetSystemErr() {
        Map<String, Object> consoleHandler = new HashMap<>();
        consoleHandler.put("class", "logging.StreamHandler");
        consoleHandler.put("stream", "ext://sys.stderr");

        Map<String, Object> config = new HashMap<>();
        config.put("handlers", Map.of("console", consoleHandler));
        config.put("root", Map.of("level", "INFO", "handlers", java.util.List.of("console")));

        LogbackConfigurer.configure(config);

        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ConsoleAppender<?> appender = assertInstanceOf(ConsoleAppender.class, root.iteratorForAppenders().next());
        assertEquals("System.err", appender.getTarget());
    }

    @Test
    void testLogbackConfigurerWithRootLogger() {
        Map<String, Object> config = new HashMap<>();
        
        // Configure root logger
        Map<String, Object> root = new HashMap<>();
        root.put("level", "INFO");
        root.put("handlers", java.util.Arrays.asList("console"));
        config.put("root", root);
        
        // Configure handlers
        Map<String, Object> handlers = new HashMap<>();
        Map<String, Object> consoleHandler = new HashMap<>();
        consoleHandler.put("class", "logging.StreamHandler");
        handlers.put("console", consoleHandler);
        config.put("handlers", handlers);
        
        // Configure logback
        LogbackConfigurer.configure(config);
        
        // Verify root logger configuration
        Logger rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertNotNull(rootLogger);
    }

    @Test
    void testLogbackConfigurerWithNamedLoggers() {
        Map<String, Object> config = new HashMap<>();
        
        // Configure named loggers
        Map<String, Object> loggers = new HashMap<>();
        
        Map<String, Object> testLogger = new HashMap<>();
        testLogger.put("level", "DEBUG");
        testLogger.put("handlers", java.util.Arrays.asList("file"));
        testLogger.put("propagate", false);
        loggers.put("test.named", testLogger);
        
        config.put("loggers", loggers);
        
        // Configure handlers
        Map<String, Object> handlers = new HashMap<>();
        Map<String, Object> fileHandler = new HashMap<>();
        fileHandler.put("class", "logging.FileHandler");
        fileHandler.put("filename", "test.log");
        handlers.put("file", fileHandler);
        config.put("handlers", handlers);
        
        // Configure logback
        LogbackConfigurer.configure(config);
        
        // Verify named logger configuration
        Logger namedLogger = LoggerFactory.getLogger("test.named");
        assertNotNull(namedLogger);
    }

    @Test
    void testLogbackConfigurerWithTraceLogger() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> loggers = new HashMap<>();
        Map<String, Object> testLogger = new HashMap<>();
        testLogger.put("level", "TRACE");
        loggers.put("test.trace", testLogger);
        config.put("loggers", loggers);

        LogbackConfigurer.configure(config);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("test.trace");
        assertEquals(ch.qos.logback.classic.Level.TRACE, logger.getLevel());
    }

    @Test
    void testPythonLevelNamesAndNumbersAreMapped() {
        assertEquals(ch.qos.logback.classic.Level.WARN, LogbackConfigurer.toLogbackLevel("WARNING"));
        assertEquals(ch.qos.logback.classic.Level.WARN, LogbackConfigurer.toLogbackLevel("warning"));
        assertEquals(ch.qos.logback.classic.Level.ERROR, LogbackConfigurer.toLogbackLevel("CRITICAL"));
        assertEquals(ch.qos.logback.classic.Level.ERROR, LogbackConfigurer.toLogbackLevel("FATAL"));
        assertEquals(ch.qos.logback.classic.Level.TRACE, LogbackConfigurer.toLogbackLevel("TRACE"));
        assertEquals(ch.qos.logback.classic.Level.INFO, LogbackConfigurer.toLogbackLevel("INFO"));
        assertNull(LogbackConfigurer.toLogbackLevel("NOTSET"));
        assertNull(LogbackConfigurer.toLogbackLevel(null));
        assertNull(LogbackConfigurer.toLogbackLevel(0));
        assertEquals(ch.qos.logback.classic.Level.TRACE, LogbackConfigurer.toLogbackLevel(5));
        assertEquals(ch.qos.logback.classic.Level.DEBUG, LogbackConfigurer.toLogbackLevel(10));
        assertEquals(ch.qos.logback.classic.Level.INFO, LogbackConfigurer.toLogbackLevel(20));
        assertEquals(ch.qos.logback.classic.Level.WARN, LogbackConfigurer.toLogbackLevel(30));
        assertEquals(ch.qos.logback.classic.Level.ERROR, LogbackConfigurer.toLogbackLevel(40));
        assertEquals(ch.qos.logback.classic.Level.ERROR, LogbackConfigurer.toLogbackLevel(50L));
        assertEquals(ch.qos.logback.classic.Level.INFO, LogbackConfigurer.toLogbackLevel("20"));
        assertThrows(IllegalArgumentException.class, () -> LogbackConfigurer.toLogbackLevel("VERBOSE"));
    }

    @Test
    void testLoggerLevelsAcceptPythonNamesAndNumbers() {
        Map<String, Object> loggers = new HashMap<>();
        loggers.put("test.warning", Map.of("level", "WARNING"));
        loggers.put("test.critical", Map.of("level", "CRITICAL"));
        loggers.put("test.numeric", Map.of("level", 20));
        loggers.put("test.notset", Map.of("level", "NOTSET"));

        Map<String, Object> config = new HashMap<>();
        config.put("loggers", loggers);
        config.put("root", Map.of("level", "NOTSET"));

        LogbackConfigurer.configure(config);

        assertEquals(ch.qos.logback.classic.Level.WARN, classicLogger("test.warning").getLevel());
        assertEquals(ch.qos.logback.classic.Level.ERROR, classicLogger("test.critical").getLevel());
        assertEquals(ch.qos.logback.classic.Level.INFO, classicLogger("test.numeric").getLevel());
        assertNull(classicLogger("test.notset").getLevel());
        assertNotNull(classicLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel(), "root logger must keep a level");
    }

    @Test
    void testPythonFormatTokensAreTranslatedWithModifiers() {
        assertEquals(
                "%d{yyyy-MM-dd HH:mm:ss.SSS} %-8level [%thread] %logger %file:%line %method %logger{0} %property{PID} - %msg%n",
                LogbackConfigurer.translatePythonFormatToLogback(
                        "%(asctime)s %(levelname)-8s [%(threadName)s] %(name)s %(filename)s:%(lineno)d %(funcName)s %(module)s %(process)d - %(message)s"
                )
        );
        assertEquals("%X{request_id} 100\\% %msg%n", LogbackConfigurer.translatePythonFormatToLogback("%(request_id)s 100%% %(message)s"));
        assertEquals("%msg%n", LogbackConfigurer.translatePythonFormatToLogback("%(message)s%n"));
    }

    @Test
    void testHandlerLevelBecomesThresholdFilter() {
        Map<String, Object> consoleHandler = new HashMap<>();
        consoleHandler.put("class", "logging.StreamHandler");
        consoleHandler.put("level", "WARNING");

        Map<String, Object> config = new HashMap<>();
        config.put("handlers", Map.of("console", consoleHandler));
        config.put("root", Map.of("level", "DEBUG", "handlers", java.util.List.of("console")));

        LogbackConfigurer.configure(config);

        ch.qos.logback.core.Appender<?> appender = classicLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().next();
        ch.qos.logback.classic.filter.ThresholdFilter filter = assertInstanceOf(
                ch.qos.logback.classic.filter.ThresholdFilter.class,
                appender.getCopyOfAttachedFiltersList().get(0)
        );
        ch.qos.logback.classic.spi.LoggingEvent infoEvent = new ch.qos.logback.classic.spi.LoggingEvent();
        infoEvent.setLevel(ch.qos.logback.classic.Level.INFO);
        ch.qos.logback.classic.spi.LoggingEvent warnEvent = new ch.qos.logback.classic.spi.LoggingEvent();
        warnEvent.setLevel(ch.qos.logback.classic.Level.WARN);
        assertEquals(ch.qos.logback.core.spi.FilterReply.DENY, filter.decide(infoEvent));
        assertEquals(ch.qos.logback.core.spi.FilterReply.NEUTRAL, filter.decide(warnEvent));
    }

    @Test
    void testRotatingFileHandlerUsesFixedWindowSizeBasedRotation() throws Exception {
        java.nio.file.Path logFile = java.nio.file.Files.createTempFile("pyronaut-rotating", ".log");
        try {
            Map<String, Object> handler = new HashMap<>();
            handler.put("class", "logging.handlers.RotatingFileHandler");
            handler.put("filename", logFile.toString());
            handler.put("maxBytes", 2048);
            handler.put("backupCount", 3);

            Map<String, Object> config = new HashMap<>();
            config.put("handlers", Map.of("rotating", handler));
            config.put("root", Map.of("level", "INFO", "handlers", java.util.List.of("rotating")));

            LogbackConfigurer.configure(config);

            ch.qos.logback.core.rolling.RollingFileAppender<?> appender = assertInstanceOf(
                    ch.qos.logback.core.rolling.RollingFileAppender.class,
                    classicLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().next()
            );
            ch.qos.logback.core.rolling.FixedWindowRollingPolicy rollingPolicy = assertInstanceOf(
                    ch.qos.logback.core.rolling.FixedWindowRollingPolicy.class,
                    appender.getRollingPolicy()
            );
            assertEquals(1, rollingPolicy.getMinIndex());
            assertEquals(3, rollingPolicy.getMaxIndex());
            assertEquals(logFile + ".%i", rollingPolicy.getFileNamePattern());
            ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy<?> triggeringPolicy = assertInstanceOf(
                    ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy.class,
                    appender.getTriggeringPolicy()
            );
            assertEquals(2048L, triggeringPolicy.getMaxFileSize().getSize());
        } finally {
            LogbackConfigurer.configure(new HashMap<>());
            java.nio.file.Files.deleteIfExists(logFile);
        }
    }

    @Test
    void testRotatingFileHandlerWithoutBackupsDoesNotRotate() throws Exception {
        java.nio.file.Path logFile = java.nio.file.Files.createTempFile("pyronaut-plain", ".log");
        try {
            Map<String, Object> handler = new HashMap<>();
            handler.put("class", "logging.handlers.RotatingFileHandler");
            handler.put("filename", logFile.toString());
            handler.put("maxBytes", 2048);
            handler.put("backupCount", 0);

            Map<String, Object> config = new HashMap<>();
            config.put("handlers", Map.of("rotating", handler));
            config.put("root", Map.of("level", "INFO", "handlers", java.util.List.of("rotating")));

            LogbackConfigurer.configure(config);

            ch.qos.logback.core.Appender<?> appender = classicLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().next();
            assertInstanceOf(ch.qos.logback.core.FileAppender.class, appender);
            assertFalse(appender instanceof ch.qos.logback.core.rolling.RollingFileAppender);
        } finally {
            LogbackConfigurer.configure(new HashMap<>());
            java.nio.file.Files.deleteIfExists(logFile);
        }
    }

    private static ch.qos.logback.classic.Logger classicLogger(String name) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
    }

    @Test
    void testLoggerCaching() {
        // Test that LogbackConfigurer.getLogger returns the same instance
        Logger logger1 = LogbackConfigurer.getLogger("test.cache");
        Logger logger2 = LogbackConfigurer.getLogger("test.cache");
        
        assertSame(logger1, logger2, "getLogger should return the same instance for the same name");
    }
}
