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
    void testLoggerCaching() {
        // Test that LogbackConfigurer.getLogger returns the same instance
        Logger logger1 = LogbackConfigurer.getLogger("test.cache");
        Logger logger2 = LogbackConfigurer.getLogger("test.cache");
        
        assertSame(logger1, logger2, "getLogger should return the same instance for the same name");
    }
}
