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
package io.micronaut.pyton.logging.impl;

import io.micronaut.context.python.ContextHolder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PythonLoggingServiceProvider that verify logging integration
 * between Java SLF4J and Python logging via GraalPy.
 */
class PythonLoggingServiceProviderTest {

    private Context graalContext;

    @BeforeEach
    void setupGraalPyContext() {
        // Create GraalPy context
        graalContext = Context.newBuilder("python")
                .allowAllAccess(true)
                .build();

        // Set the context in ContextHolder
        ContextHolder.setContext(graalContext);
    }

    @AfterEach
    void cleanup() {
        if (graalContext != null) {
            graalContext.close();
            ContextHolder.setContext(null);
        }
    }

    @Test
    void testPythonLoggerIsUsedWhenContextIsAvailable() {
        // When GraalPy context is available, PythonLogger should be used
        Logger logger = LoggerFactory.getLogger("test.python");

        // Verify that we get a PythonLogger instance
        assertTrue(logger instanceof PythonLogger,
                "Logger should be PythonLogger when GraalPy context is available");
        assertEquals("test.python", logger.getName(),
                "Logger should have correct name");
    }

    @Test
    void testDelayedConsoleLoggerIsUsedWhenContextIsNotAvailable() {
        // Clear the context
        ContextHolder.setContext(null);

        // When no GraalPy context is available, DelayedConsoleLogger should be used
        Logger logger = LoggerFactory.getLogger("test.console");

        // Verify that we get a DelayedConsoleLogger instance
        assertTrue(logger instanceof DelayedConsoleLogger,
                "Logger should be DelayedConsoleLogger when no GraalPy context is available");
        assertEquals("test.console", logger.getName(),
                "Logger should have correct name");
    }

    @Test
    void testPythonLoggerDelegatesToPythonLogging() {
        Logger logger = LoggerFactory.getLogger("test.python.integration");

        // Verify it's a PythonLogger
        assertTrue(logger instanceof PythonLogger,
                "Should be PythonLogger when context is available");

        PythonLogger pythonLogger = (PythonLogger) logger;

        // Configure Python logging to capture output
        graalContext.eval("python", """
            import logging
            import sys
            from io import StringIO

            # Create a string buffer to capture log output
            log_capture = StringIO()
            handler = logging.StreamHandler(log_capture)
            handler.setFormatter(logging.Formatter('%(name)s - %(levelname)s - %(message)s'))

            # Configure the test logger
            test_logger = logging.getLogger('test.python.integration')
            test_logger.addHandler(handler)
            test_logger.setLevel(logging.DEBUG)

            # Make the buffer available globally
            globals()['log_capture'] = log_capture
            """);

        // Log a message
        logger.info("Test message from Java to Python");

        // Retrieve captured output
        Value logOutput = graalContext.eval("python", "globals()['log_capture'].getvalue()");

        String capturedOutput = logOutput.asString();

        // Verify the message was logged through Python
        assertTrue(capturedOutput.contains("test.python.integration"),
                "Captured output should contain logger name");
        assertTrue(capturedOutput.contains("INFO"),
                "Captured output should contain log level");
        assertTrue(capturedOutput.contains("Test message from Java to Python"),
                "Captured output should contain the log message");
    }

    @Test
    void testLogLevelsAreCorrectlyMapped() {
        Logger logger = LoggerFactory.getLogger("test.levels");

        assertTrue(logger instanceof PythonLogger,
                "Should be PythonLogger when context is available");

        // Test that Python logger levels are enabled based on Python configuration
        // By default, Python root logger level is WARNING (30)
        assertFalse(logger.isTraceEnabled(), "TRACE should not be enabled by default");
        assertFalse(logger.isDebugEnabled(), "DEBUG should not be enabled by default");
        assertTrue(logger.isInfoEnabled(), "INFO should be enabled by default");
        assertTrue(logger.isWarnEnabled(), "WARN should be enabled by default");
        assertTrue(logger.isErrorEnabled(), "ERROR should be enabled by default");

        // Configure Python logger to DEBUG level
        graalContext.eval("python", "logging.getLogger('test.levels').setLevel(logging.DEBUG)");

        // Now DEBUG and TRACE (which maps to Python's NOTSET level) should be enabled for this specific logger
        assertTrue(logger.isTraceEnabled(), "TRACE should be enabled when logger level is set to DEBUG (maps to Python NOTSET)");
        assertTrue(logger.isDebugEnabled(), "DEBUG should be enabled after setting level");
        assertTrue(logger.isInfoEnabled(), "INFO should still be enabled");
        assertTrue(logger.isWarnEnabled(), "WARN should still be enabled");
        assertTrue(logger.isErrorEnabled(), "ERROR should still be enabled");
    }

    @Test
    void testLoggerFactoryReturnsSameInstance() {
        Logger logger1 = LoggerFactory.getLogger("test.singleton");
        Logger logger2 = LoggerFactory.getLogger("test.singleton");

        assertSame(logger1, logger2,
                "LoggerFactory should return the same logger instance for the same name");
    }

    @Test
    void testServiceProviderIntegration() {
        // Test that the SLF4J service provider mechanism works
        PythonLoggingServiceProvider provider = new PythonLoggingServiceProvider();
        provider.initialize();

        assertNotNull(provider.getLoggerFactory(),
                "ServiceProvider should provide a logger factory");
        assertNotNull(provider.getMarkerFactory(),
                "ServiceProvider should provide a marker factory");
        assertNotNull(provider.getMDCAdapter(),
                "ServiceProvider should provide an MDC adapter");
        assertEquals("2.0.99", provider.getRequestedApiVersion(),
                "ServiceProvider should request correct SLF4J API version");
    }

    @Test
    void testDelayedConsoleLoggerFallback() {
        // Clear context to force DelayedConsoleLogger usage
        ContextHolder.setContext(null);

        Logger logger = LoggerFactory.getLogger("test.fallback");

        assertTrue(logger instanceof DelayedConsoleLogger,
                "Should be DelayedConsoleLogger when no context");

        DelayedConsoleLogger delayedLogger = (DelayedConsoleLogger) logger;

        // Initially, only INFO, WARN, ERROR are enabled by default in DelayedConsoleLogger
        assertFalse(delayedLogger.isTraceEnabled(), "TRACE should not be enabled by default");
        assertFalse(delayedLogger.isDebugEnabled(), "DEBUG should not be enabled by default");
        assertTrue(delayedLogger.isInfoEnabled(), "INFO should be enabled by default");
        assertTrue(delayedLogger.isWarnEnabled(), "WARN should be enabled by default");
        assertTrue(delayedLogger.isErrorEnabled(), "ERROR should be enabled by default");

        // After setting context, it should delegate to PythonLogger
        ContextHolder.setContext(graalContext);

        Logger sameLogger = LoggerFactory.getLogger("test.fallback");
        // Note: This might still be the same DelayedConsoleLogger instance,
        // but it should delegate to PythonLogger internally
        assertTrue(sameLogger instanceof DelayedConsoleLogger,
                "Should still be DelayedConsoleLogger instance");
    }
}
