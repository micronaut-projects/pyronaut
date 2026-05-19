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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Logging bootstrap for the native development launcher.
 */
final class PyronautDevLogging {
    private static final String SIMPLE_LOGGER_DEFAULT_LEVEL = "org.slf4j.simpleLogger.defaultLogLevel";
    private static final String LOGBACK_STATUS_LISTENER = "logback.statusListenerClass";
    private static final String LOGBACK_NOP_STATUS_LISTENER = "ch.qos.logback.core.status.NopStatusListener";
    private static final String CONSOLE_APPENDER_NAME = "PYRONAUT_DEV_CONSOLE";
    private static final String CONSOLE_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private PyronautDevLogging() {
    }

    static void initializeLauncherLogging() {
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

    private static ConsoleAppender<ILoggingEvent> createConsoleAppender(LoggerContext loggerContext) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern(CONSOLE_PATTERN);
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
