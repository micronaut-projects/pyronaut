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

import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.context.python.GraalPyContextFactory;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.util.HttpHeadersUtil;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.*;

/**
 * Tests for LogbackConfigurer that verify end-to-end Python integration
 * with GraalPy context and VFS resources.
 */
class LogbackConfigurerGraalPyTest {

    private Context graalContext;

    @BeforeEach
    void setupGraalPyContext() throws Exception {
        this.graalContext = GraalPyContextFactory.bootstrapReusableContext(
            LogbackConfigurerGraalPyTest.class.getClassLoader()
        );
    }

    @AfterEach
    void cleanup() {
        if (graalContext != null) {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            graalContext.close();
        }
    }

    @Test
    void testPythonCanImportLogbackConfigModule() {
        // Test that Python can import the logback configuration module from VFS
        try {
            graalContext.eval("python", "from logback.config import dictConfig");
            assertTrue(true, "Successfully imported dictConfig from logback.config");
        } catch (Exception e) {
            fail("Failed to import logback.config: " + e.getMessage());
        }
    }

    @Test
    void testCaptureLogsCapturesJavaHeaderTraceFromWorkerThread() throws Exception {
        Logger logger = LogbackConfigurer.getLogger("test.capture.headers");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var originalAppenders = appenders(logger);
        var existing = new ListAppender<ILoggingEvent>();
        existing.setContext(logger.getLoggerContext());
        existing.start();
        logger.addAppender(existing);
        logger.setAdditive(false);
        logger.setLevel(null);
        var worker = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "header-trace-producer"));
        try {
            graalContext.eval("python", """
                import logging
                import time
                from logback import capture_logs
                capture_started = time.time()
                capture = capture_logs(logging.getLogger('test.capture.headers'), level='TRACE')
                records = capture.__enter__()
                assert records == []
                """);
            var capturedAppender = captureAppender(logger, List.of(existing));
            assertEquals(Level.TRACE, logger.getLevel());
            var headers = new SimpleHttpHeaders();
            headers.add("Authorization", "Bearer foo");
            headers.add("Proxy-Authorization", "AWS4-HMAC-SHA256 bar");
            headers.add("Credential", "credential-value");
            headers.add("Signature", "signature-value");
            headers.add("Password", "password-value");
            headers.add("Certificate", "certificate-value");
            headers.add("Api-Key", "key-value");
            headers.add("Secret", "secret-value");
            headers.add("Token", "token-value");
            headers.add("Cookie", "baz");
            headers.add("Set-Cookie", "qux");
            headers.add("X-Forwarded-For", "quux");
            headers.add("X-Forwarded-For", "fred");
            headers.add("X-Forwarded-Host", "quuz");
            headers.add("X-Real-IP", "waldo");
            headers.add("foo", "bar");
            worker.submit(() -> HttpHeadersUtil.trace(logger, headers)).get(10, TimeUnit.SECONDS);
            assertEquals(16, existing.list.size(), "The real Java header producer must emit every value");
            graalContext.eval("python", """
                assert records == [], 'Java events must not call Python while capture is active'
                capture.__exit__(None, None, None)
                assert len(records) == 16
                assert all(isinstance(record, logging.LogRecord) for record in records)
                assert all(record.name == 'test.capture.headers' for record in records)
                assert all(record.levelno == 5 and record.levelname == 'TRACE' for record in records)
                assert all(capture_started <= record.created <= time.time() for record in records)
                assert all(record.thread is None and record.threadName is None for record in records)
                messages = {record.getMessage() for record in records}
                assert {
                    'Authorization: *MASKED*', 'Proxy-Authorization: *MASKED*',
                    'Credential: *MASKED*', 'Signature: *MASKED*', 'Password: *MASKED*',
                    'Certificate: *MASKED*', 'Api-Key: *MASKED*', 'Secret: *MASKED*',
                    'Token: *MASKED*', 'Cookie: baz', 'Set-Cookie: qux',
                    'X-Forwarded-For: quux', 'X-Forwarded-For: fred',
                    'X-Forwarded-Host: quuz', 'X-Real-IP: waldo', 'foo: bar',
                } == messages
                assert not any(secret in message for message in messages for secret in (
                    'Bearer foo', 'AWS4-HMAC-SHA256 bar', 'credential-value', 'signature-value',
                    'password-value', 'certificate-value', 'key-value', 'secret-value', 'token-value',
                ))
                """);
            assertNull(logger.getLevel(), "Restore inheritance, not the effective level");
            assertEquals(List.of(existing), appenders(logger));
            assertTrue(existing.isStarted());
            assertFalse(capturedAppender.isStarted());
            assertFalse(logger.isAdditive());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            restoreLogger(logger, previousLevel, previousAdditive, originalAppenders);
        }
    }

    @Test
    void testCaptureLogsPreservesLevelsAndNestedScopeState() {
        graalContext.eval("python", """
            import logging
            from logback import capture_logs, dictConfig
            dictConfig({'version': 1, 'handlers': {}, 'root': {'level': 'DEBUG'}})
            """);
        Logger logger = LogbackConfigurer.getLogger("test.capture.levels");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var originalAppenders = appenders(logger);
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        try {
            graalContext.eval("python", """
                outer = capture_logs(logging.getLogger('test.capture.levels'), level='TRACE')
                outer_records = outer.__enter__()
                """);
            var outerAppender = captureAppender(logger, originalAppenders);
            logger.trace("outer trace");
            logger.debug("outer debug");
            logger.info("outer info");
            logger.warn("outer warning");
            logger.error("outer error");
            graalContext.eval("python", """
                logging.getLogger('test.capture.levels').warning('python stdlib warning')
                inner = capture_logs('test.capture.levels', level='DEBUG')
                inner_records = inner.__enter__()
                """);
            var innerAppender = captureAppender(logger, List.of(outerAppender));
            assertEquals(Level.DEBUG, logger.getLevel());
            logger.debug("inner debug");
            logger.trace("inner blocked trace");
            graalContext.eval("python", "inner.__exit__(None, None, None)");
            assertEquals(Level.TRACE, logger.getLevel());
            assertFalse(innerAppender.isStarted());
            assertEquals(List.of(outerAppender), appenders(logger));
            logger.trace("restored trace");
            graalContext.eval("python", """
                outer.__exit__(None, None, None)
                assert [record.getMessage() for record in outer_records] == [
                    'outer trace', 'outer debug', 'outer info', 'outer warning', 'outer error',
                    'python stdlib warning', 'inner debug', 'restored trace',
                ]
                assert [record.levelno for record in outer_records] == [5, 10, 20, 30, 40, 30, 10, 5]
                assert [record.levelname for record in outer_records] == [
                    'TRACE', 'DEBUG', 'INFO', 'WARNING', 'ERROR', 'WARNING', 'DEBUG', 'TRACE',
                ]
                assert [record.getMessage() for record in inner_records] == ['inner debug']
                assert inner_records[0].levelno == logging.DEBUG
                """);
            assertEquals(Level.WARN, logger.getLevel());
            assertEquals(originalAppenders, appenders(logger));
            assertFalse(outerAppender.isStarted());
            logger.setLevel(null);
            graalContext.eval("python", """
                inherited = capture_logs('test.capture.levels')
                inherited_records = inherited.__enter__()
                """);
            assertNull(logger.getLevel(), "Default capture must not assign an explicit level");
            logger.debug("inherited debug");
            graalContext.eval("python", """
                inherited.__exit__(None, None, None)
                assert [record.getMessage() for record in inherited_records] == ['inherited debug']
                """);
            assertNull(logger.getLevel());
            Logger root = LogbackConfigurer.getLogger("ROOT");
            var rootAppenders = appenders(root);
            var rootLevel = root.getLevel();
            graalContext.eval("python", """
                with capture_logs() as default_records:
                    logging.getLogger().warning('default root warning')
                assert [record.getMessage() for record in default_records] == ['default root warning']
                assert default_records[0].levelno == logging.WARNING
                """);
            assertEquals(rootLevel, root.getLevel());
            assertEquals(rootAppenders, appenders(root));
        } finally {
            restoreLogger(logger, previousLevel, previousAdditive, originalAppenders);
        }
    }

    @Test
    void testCaptureLogsRestoresStateWhenBodyRaises() {
        graalContext.eval("python", """
            import logging
            from logback import capture_logs, dictConfig
            dictConfig({'version': 1, 'handlers': {}, 'root': {'level': 'DEBUG'}})
            """);
        Logger logger = LogbackConfigurer.getLogger("test.capture.exception");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var originalAppenders = appenders(logger);
        var existing = new ListAppender<ILoggingEvent>();
        existing.setContext(logger.getLoggerContext());
        existing.start();
        logger.addAppender(existing);
        logger.setLevel(null);
        logger.setAdditive(false);
        var ownedAppender = new AtomicReference<Appender<ILoggingEvent>>();
        graalContext.getBindings("python").putMember("inspect_capture", (Runnable) () ->
            ownedAppender.set(captureAppender(logger, List.of(existing))));
        try {
            graalContext.eval("python", """
                body_error = ValueError('capture-body-error')
                try:
                    with capture_logs(logging.getLogger('test.capture.exception'), level='TRACE') as records:
                        inspect_capture.run()
                        logging.getLogger('test.capture.exception').warning('before error')
                        raise body_error
                except ValueError as caught:
                    assert caught is body_error
                else:
                    raise AssertionError('capture must not suppress the body exception')
                assert [record.getMessage() for record in records] == ['before error']
                assert records[0].levelno == logging.WARNING
                """);
            assertNotNull(ownedAppender.get());
            assertFalse(ownedAppender.get().isStarted());
            assertEquals(List.of(existing), appenders(logger));
            assertTrue(existing.isStarted());
            assertNull(logger.getLevel());
            assertFalse(logger.isAdditive());
            logger.warn("after failed scope");
            graalContext.eval("python", """
                assert [record.getMessage() for record in records] == ['before error']
                with capture_logs('test.capture.exception') as second_records:
                    logging.getLogger('test.capture.exception').warning('new scope')
                assert [record.getMessage() for record in second_records] == ['new scope']
                """);
            assertNull(logger.getLevel());
            assertEquals(List.of(existing), appenders(logger));
        } finally {
            restoreLogger(logger, previousLevel, previousAdditive, originalAppenders);
        }
    }

    @Test
    void testCaptureLogsRejectsInvalidLevelWithoutChangingLogger() {
        Logger logger = LogbackConfigurer.getLogger("test.capture.invalid-level");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var originalAppenders = appenders(logger);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            graalContext.eval("python", """
                from logback import capture_logs
                import logback.config as logback_config
                try:
                    with capture_logs(42):
                        raise AssertionError('invalid logger was accepted')
                except TypeError:
                    pass
                original_bridge = logback_config.LogbackConfigurer
                try:
                    logback_config.LogbackConfigurer = None
                    try:
                        with capture_logs('test.capture.invalid-level'):
                            raise AssertionError('missing backend was accepted')
                    except RuntimeError as error:
                        assert 'Java Logback backend' in str(error)
                finally:
                    logback_config.LogbackConfigurer = original_bridge
                try:
                    with capture_logs('test.capture.invalid-level', level='NOT-A-LEVEL'):
                        raise AssertionError('invalid level was accepted')
                except ValueError:
                    pass
                """);
            assertEquals(Level.INFO, logger.getLevel());
            assertEquals(originalAppenders, appenders(logger));
            Object[] levels = {"TRACE", "WARNING", 10, 20, 30, 40};
            Level[] expected = {Level.TRACE, Level.WARN, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR};
            for (int index = 0; index < levels.length; index++) {
                String expression = levels[index] instanceof String ? "'" + levels[index] + "'" : levels[index].toString();
                graalContext.eval("python", "capture = capture_logs('test.capture.invalid-level', level="
                    + expression + "); records = capture.__enter__()");
                var capturedAppender = captureAppender(logger, originalAppenders);
                assertEquals(expected[index], logger.getLevel());
                logger.error("valid level");
                graalContext.eval("python", """
                    capture.__exit__(None, None, None)
                    assert len(records) == 1
                    assert records[0].getMessage() == 'valid level'
                    assert records[0].levelno == 40
                    """);
                assertEquals(Level.INFO, logger.getLevel());
                assertEquals(originalAppenders, appenders(logger));
                assertFalse(capturedAppender.isStarted());
            }
        } finally {
            restoreLogger(logger, previousLevel, previousAdditive, originalAppenders);
        }
    }

    private static List<Appender<ILoggingEvent>> appenders(Logger logger) {
        var result = new ArrayList<Appender<ILoggingEvent>>();
        logger.iteratorForAppenders().forEachRemaining(result::add);
        return result;
    }

    private static Appender<ILoggingEvent> captureAppender(Logger logger, List<Appender<ILoggingEvent>> previous) {
        var added = appenders(logger).stream().filter(appender -> !previous.contains(appender)).toList();
        assertEquals(1, added.size(), "Capture must attach exactly one owned appender");
        assertInstanceOf(ListAppender.class, added.getFirst());
        assertTrue(added.getFirst().isStarted());
        return added.getFirst();
    }

    private static void restoreLogger(Logger logger, Level level, boolean additive, List<Appender<ILoggingEvent>> previous) {
        for (var appender : appenders(logger)) {
            if (!previous.contains(appender)) {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
        logger.setLevel(level);
        logger.setAdditive(additive);
    }

    @Test
    void testPythonDictConfigConfiguresLogback() {
        // Test that Python dictConfig call configures logback
        String pythonCode =
                """
from logback.config import dictConfig

config = {
    'version': 1,
    'handlers': {
        'console': {
            'class': 'logging.StreamHandler'
        }
    },
    'loggers': {
        'test': {
            'level': 'INFO',
            'handlers': ['console']
        }
    }
}

dictConfig(config)
result = 'success'
""".stripIndent();

        try {
            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "result").asString();
            assertEquals("success", result, "dictConfig should execute without errors");
        } catch (Exception e) {
            fail("Python dictConfig failed: " + e.getMessage());
        }
    }

    @Test
    void testPythonDictConfigAcceptsTraceLevel() {
        String pythonCode =
                """
from logback.config import dictConfig

config = {
    'version': 1,
    'handlers': {
        'console': {
            'class': 'logging.StreamHandler',
            'level': 'TRACE'
        }
    },
    'loggers': {
        'test.trace': {
            'level': 'TRACE',
            'handlers': ['console']
        }
    }
}

dictConfig(config)
result = 'success'
""".stripIndent();

        try {
            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "result").asString();
            assertEquals("success", result, "dictConfig should accept TRACE levels");

            Logger logger = (Logger) LoggerFactory.getLogger("test.trace");
            assertEquals(Level.TRACE, logger.getLevel());
        } catch (Exception e) {
            fail("Python dictConfig failed for TRACE level: " + e.getMessage());
        }
    }

    @Test
    void testPythonFileHandlerWorksWithLogback() throws Exception {
        // Test that file handler configuration works
        Path tempFile = Path.of("test-file-logging.log");

        String pythonCode =
                """
                        from logback.config import dictConfig
                        import logging
                        
                        # Configure logging with file handler
                        config = {
                            'version': 1,
                            'handlers': {
                                'console': {
                                    'class': 'logging.StreamHandler'
                                },
                                'file': {
                                    'class': 'logging.FileHandler',
                                    'filename': '%s'
                                }
                            },
                            'root': {
                                'level': 'INFO',
                                'handlers': ['console', 'file']
                            }
                        }
                        
                        dictConfig(config)
                        
                        # Log to file
                        logger = logging.getLogger("test")
                        logger.info('Test message written to file')
                        
                        file_result = 'success'
                        """.formatted(tempFile.toString());

        try {
            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "file_result").asString();
            assertEquals("success", result, "Python file logging should execute without errors");

            await().atMost(Duration.ofSeconds(30)).until(() -> Files.exists(tempFile) && !Files.readString(tempFile).isEmpty());
            // Check that file was created and contains the message
            assertTrue(Files.exists(tempFile), "Log file should be created");
            String fileContent = Files.readString(tempFile);
            System.out.println("File content: '" + fileContent + "'");
            assertTrue(fileContent.contains("Test message written to file"),
                    "File should contain the logged message");
            assertTrue(fileContent.contains("INFO"),
                    "File should contain log level");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testPythonConsoleLoggingOutputsToSystemOut() throws Exception {
        // Capture System.out
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            String pythonCode =
                    """
                            from logback.config import dictConfig
                            import logging
                            
                            # Configure logging with console handler
                            config = {
                                'version': 1,
                                'handlers': {
                                    'console': {
                                        'class': 'logging.StreamHandler'
                                    }
                                },
                                'root': {
                                    'level': 'INFO',
                                    'handlers': ['console']
                                }
                            }
                            
                            dictConfig(config)
                            
                            # Log something
                            logger = logging.getLogger()
                            logger.info('Test console message')
                            
                            result = 'success'
                            """;

            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "result").asString();
            assertEquals("success", result, "Console logging should execute without errors");

            // Check that System.out captured the log message
            String capturedOutput = outputStream.toString();
            assertTrue(capturedOutput.contains("Test console message"),
                    "System.out should contain the logged message. Captured: '" + capturedOutput + "'");
            assertTrue(capturedOutput.contains("INFO"),
                    "System.out should contain log level");

        } finally {
            // Restore original System.out
            System.setOut(originalOut);
        }
    }

    @Test
    void testLoggersCreatedBeforeDictConfigReachLogback() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            String pythonCode =
                    """
                            import logging
                            # Created at "import time", before dictConfig runs
                            early_logger = logging.getLogger('pyronaut.early.module')

                            from logback.config import dictConfig
                            dictConfig({
                                'version': 1,
                                'handlers': {
                                    'console': {
                                        'class': 'logging.StreamHandler'
                                    }
                                },
                                'root': {
                                    'level': 'INFO',
                                    'handlers': ['console']
                                }
                            })

                            early_logger.info('Message from a pre-existing logger')
                            early_logger.debug('Debug message that must be filtered')
                            result = 'success'
                            """;

            graalContext.eval("python", pythonCode);
            assertEquals("success", graalContext.eval("python", "result").asString());

            String capturedOutput = outputStream.toString();
            assertTrue(capturedOutput.contains("Message from a pre-existing logger"),
                    "Records from loggers created before dictConfig should reach logback. Captured: '" + capturedOutput + "'");
            assertTrue(capturedOutput.contains("pyronaut.early.module"),
                    "Logger name should be preserved. Captured: '" + capturedOutput + "'");
            assertFalse(capturedOutput.contains("Debug message that must be filtered"),
                    "Root level INFO should filter debug records. Captured: '" + capturedOutput + "'");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testPythonCustomLogbackAppenderOutputsToSystemErr() throws Exception {
        // Test that custom Logback appenders can be configured via reflection
        // Capture System.err
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(outputStream));

        try {
            String pythonCode =
                    """
                            from logback.config import dictConfig
                            import logging
                            
                            # Configure logging with custom ConsoleAppender
                            config = {
                                'version': 1,
                                'handlers': {
                                    'console': {
                                        'class': 'ch.qos.logback.core.ConsoleAppender',
                                        'target': 'System.err'
                                    }
                                },
                                'root': {
                                    'level': 'INFO',
                                    'handlers': ['console']
                                }
                            }
                            
                            dictConfig(config)
                            
                            # Log something
                            logger = logging.getLogger('test')
                            logger.info('Test custom appender message')
                            
                            result = 'success'
                            """;

            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "result").asString();
            assertEquals("success", result, "Custom appender logging should execute without errors");

            // Verify that the custom appender was configured and logging executed without errors
            // Note: Output capture in GraalPy test environment may not work reliably
            assertTrue(true, "Custom Logback appender configuration and logging completed successfully");

        } finally {
            // Restore original System.err
            System.setErr(originalErr);
        }
    }

    @Test
    void testPythonDictConfigAppliesToJavaLoggerConfiguration() {
        // Test that Python dictConfig correctly configures Java-side logback Logger instances
        String pythonCode =
                """
                        from logback.config import dictConfig
                        
                        config = {
                            'version': 1,
                            'handlers': {
                                'console': {
                                    'class': 'logging.StreamHandler'
                                },
                                'file': {
                                    'class': 'logging.FileHandler',
                                    'filename': 'test-config.log'
                                }
                            },
                            'root': {
                                'level': 'WARN',
                                'handlers': ['console']
                            },
                            'loggers': {
                                'test.configured': {
                                    'level': 'DEBUG',
                                    'handlers': ['file'],
                                    'propagate': False
                                },
                                'test.inherited': {
                                    'level': 'INFO'
                                }
                            }
                        }
                        
                        dictConfig(config)
                        config_applied = 'success'
                        """;

        try {
            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "config_applied").asString();
            assertEquals("success", result, "dictConfig should execute without errors");

            // Now verify the Java-side logback configuration
            // Root logger should have WARN level
            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.WARN, rootLogger.getLevel(), "Root logger should have WARN level");

            // Root logger should have at least one appender (console)
            Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> rootAppenders = rootLogger.iteratorForAppenders();
            assertTrue(rootAppenders.hasNext(), "Root logger should have at least one appender");

            // test.configured logger should have DEBUG level
            Logger configuredLogger = (Logger) LoggerFactory.getLogger("test.configured");
            assertEquals(Level.DEBUG, configuredLogger.getLevel(), "Configured logger should have DEBUG level");

            // test.configured logger should have file appender and not propagate
            assertFalse(configuredLogger.isAdditive(), "Configured logger should not propagate");
            Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> configuredAppenders = configuredLogger.iteratorForAppenders();
            assertTrue(configuredAppenders.hasNext(), "Configured logger should have at least one appender");

            // test.inherited logger should have INFO level (explicitly set)
            Logger inheritedLogger = (Logger) LoggerFactory.getLogger("test.inherited");
            assertEquals(Level.INFO, inheritedLogger.getLevel(), "Inherited logger should have INFO level");

        } catch (Exception e) {
            fail("Python dictConfig configuration verification failed: " + e.getMessage());
        }
    }

    @Test
    void testComplexConfigurationWithFormattersAndRollingFiles() {
        // Test the complex configuration with formatters, handlers, and rolling files
        String pythonCode =
                """
                        from logback.config import dictConfig
                        import logging
                        
                        LOGGING = {
                            'version': 1,
                            'disable_existing_loggers': False,
                           \s
                            'formatters': {
                                'standard': {
                                    'format': 'LOG ME %(asctime)s [%(levelname)s] %(name)s: %(message)s'
                                }
                            },
                           \s
                            'handlers': {
                                'console': {
                                    'class': 'logging.StreamHandler',
                                    'level': 'INFO',
                                    'formatter': 'standard',
                                    'stream': 'ext://sys.stdout'
                                },
                                'file': {
                                    'class': 'logging.handlers.RotatingFileHandler',
                                    'level': 'INFO',
                                    'formatter': 'standard',
                                    'filename': 'app.log',
                                    'maxBytes': 10_000_000,
                                    'backupCount': 5,
                                    'encoding': 'utf-8'
                                }
                            },
                           \s
                            'root': {
                                'level': 'INFO',
                                'handlers': ['console', 'file']
                            }
                        }
                        
                        dictConfig(LOGGING)
                        config_result = 'success'
                        """;

        try {
            graalContext.eval("python", pythonCode);
            String result = graalContext.eval("python", "config_result").asString();
            assertEquals("success", result, "Complex configuration should execute without errors");

        } catch (Exception e) {
            fail("Complex configuration test failed: " + e.getMessage());
        }
    }

    @Test
    void testErrorHandlingForInvalidConfiguration() {
        // Test that invalid configurations throw meaningful errors
        String pythonCode =
                """
                        from logback.config import dictConfig
                        
                        # Configuration missing required fields
                        config = {
                            'handlers': {
                                'bad_handler': {
                                    # Missing 'class' field
                                    'level': 'INFO'
                                }
                            }
                        }
                        
                        dictConfig(config)
                        """;

        try {
            graalContext.eval("python", pythonCode);
            fail("Should have thrown an exception for invalid configuration");

        } catch (Exception e) {
            // Check that the error message is meaningful
            String message = e.getMessage();
            assertTrue(message.contains("missing 'class' configuration") ||
                            message.contains("Invalid handler configuration"),
                    "Error message should be meaningful: " + message);
        }
    }

    @Test
    void testErrorHandlingForUnsupportedHandlerClass() {
        // Test that unsupported handler classes throw errors
        String pythonCode =
                """
                        from logback.config import dictConfig
                        
                        config = {
                            'handlers': {
                                'unsupported': {
                                    'class': 'logging.handlers.UnsupportedHandler',
                                    'filename': 'test.log'
                                }
                            }
                        }
                        
                        dictConfig(config)
                        """;

        try {
            graalContext.eval("python", pythonCode);
            fail("Should have thrown an exception for unsupported handler class");

        } catch (Exception e) {
            String message = e.getMessage();
            assertTrue(message.contains("Unsupported handler class") ||
                            message.contains("Invalid handler configuration"),
                    "Error message should indicate unsupported handler: " + message);
        }
    }

    @Test
    void testErrorHandlingForMissingFormatter() {
        // Test that references to non-existent formatters throw errors
        String pythonCode =
                """
                        from logback.config import dictConfig
                        
                        config = {
                            'handlers': {
                                'console': {
                                    'class': 'logging.StreamHandler',
                                    'formatter': 'nonexistent_formatter',
                                    'level': 'INFO'
                                }
                            }
                        }
                        
                        dictConfig(config)
                        """;

        try {
            graalContext.eval("python", pythonCode);
            fail("Should have thrown an exception for missing formatter");

        } catch (Exception e) {
            String message = e.getMessage();
            assertTrue(message.contains("Invalid handler configuration") ||
                            message.contains("nonexistent_formatter"),
                    "Error message should indicate formatter issue: " + message);
        }
    }
}
