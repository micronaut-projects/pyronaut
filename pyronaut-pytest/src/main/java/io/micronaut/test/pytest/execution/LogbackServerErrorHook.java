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
package io.micronaut.test.pytest.execution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.ILoggerFactory;
import org.slf4j.Marker;

/**
 * Hands every exception logged at error level to {@link ServerErrorCapture}.
 * A turbo filter sees the event before logger levels and appenders are
 * consulted, so it works however the application configured its logging.
 * It never changes the logging decision.
 *
 * <p>Only loaded when Logback is the SLF4J backend.
 */
final class LogbackServerErrorHook extends TurboFilter implements LoggerContextListener {

    private LogbackServerErrorHook() {
        setName("pyronaut-test-server-errors");
    }

    static void install(ILoggerFactory factory) {
        LoggerContext context = (LoggerContext) factory;
        LogbackServerErrorHook hook = new LogbackServerErrorHook();
        hook.setContext(context);
        hook.attach(context);
        // The application reconfigures logging when it starts, which resets the context.
        context.addListener(hook);
    }

    private void attach(LoggerContext context) {
        if (!context.getTurboFilterList().contains(this)) {
            start();
            context.addTurboFilter(this);
        }
    }

    @Override
    public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (level == Level.ERROR) {
            Throwable thrown = t;
            if (thrown == null && params != null && params.length > 0 && params[params.length - 1] instanceof Throwable last) {
                thrown = last;
            }
            if (thrown != null) {
                ServerErrorCapture.recordException(logger == null ? null : logger.getName(), thrown);
            }
        }
        return FilterReply.NEUTRAL;
    }

    @Override
    public boolean isResetResistant() {
        return true;
    }

    @Override
    public void onStart(LoggerContext context) {
        // Nothing to do: the filter is attached when the hook is installed.
    }

    @Override
    public void onReset(LoggerContext context) {
        attach(context);
    }

    @Override
    public void onStop(LoggerContext context) {
        // Nothing to do: the filter goes away with the stopped context.
    }

    @Override
    public void onLevelChange(ch.qos.logback.classic.Logger logger, Level level) {
        // Level changes do not affect which errors are recorded.
    }
}
