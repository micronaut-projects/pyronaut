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
package io.micronaut.python.logging.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ILoggerFactory implementation that creates Python-backed loggers.
 * This factory creates loggers that delegate to Python's logging module
 * when a GraalPy context is available, otherwise falls back to the configured
 * console stream (stdout by default, or stderr when
 * {@code pyronaut.logging.fallback-stream=stderr} or
 * {@code PYRONAUT_LOGGING_FALLBACK_STREAM=stderr} is set).
 *
 * @author Micronaut Team
 * @since 1.0.0
 */
public final class PythonLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, Logger> loggerMap = new ConcurrentHashMap<>();
    private final LoggerFactoryDelegate delegateFactory = new PythonLoggerFactory.PythonLoggerFactoryDelegate();

    /**
     * Returns a logger with the specified name.
     *
     * @param name the logger name
     * @return the logger
     */
    @Override
    public Logger getLogger(String name) {
        return loggerMap.computeIfAbsent(name, delegateFactory::getLogger);
    }

    /**
     * Internal interface for logger factory implementations.
     */
    private interface LoggerFactoryDelegate {
        Logger getLogger(String name);
    }

    /**
     * Factory that creates Python-backed loggers.
     */
    private static final class PythonLoggerFactoryDelegate implements LoggerFactoryDelegate {
        @Override
        public Logger getLogger(String name) {
            return new DelayedConsoleLogger(name);
        }
    }
}
