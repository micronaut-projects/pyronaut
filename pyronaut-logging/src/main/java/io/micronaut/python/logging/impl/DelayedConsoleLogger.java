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

import io.micronaut.context.python.PythonContextRuntime;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Console-based SLF4J Logger implementation that writes to a configurable console stream.
 * This logger is used when Python logging is not available.
 *
 * @author Micronaut Team
 * @since 1.0.0
 */
final class DelayedConsoleLogger implements Logger {

    private static final String FALLBACK_STREAM_PROPERTY = "pyronaut.logging.fallback-stream";
    private static final String FALLBACK_STREAM_ENVIRONMENT = "PYRONAUT_LOGGING_FALLBACK_STREAM";

    private final String name;
    private volatile boolean traceEnabled = false;
    private volatile boolean debugEnabled = false;
    private volatile boolean infoEnabled = true;
    private volatile boolean warnEnabled = true;
    private volatile boolean errorEnabled = true;
    private volatile PythonLogger delegate;

    public DelayedConsoleLogger(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isTraceEnabled() {
        PythonLogger d = getDelegate();
        if (d == null) {
            return traceEnabled;
        } else {
            return d.isTraceEnabled();
        }
    }

    PythonLogger getDelegate() {
        if (PythonContextRuntime.isInitialized()) {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        delegate = new PythonLogger(name);
                    }
                }
            }
        }
        return delegate;
    }

    @Override
    public void trace(String msg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (traceEnabled) {
                log(output(), "TRACE", name, msg, null);
            }
        } else {
            d.trace(msg);
        }
    }

    @Override
    public void trace(String format, Object arg) {
        if (traceEnabled) {
            FormattingTuple t = MessageFormatter.format(format, arg);
            trace(formatTuple(t));
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        if (traceEnabled) {
            FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
            trace(formatTuple(t));
        }
    }

    @Override
    public void trace(String format, Object... arguments) {
        if (traceEnabled) {
            FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
            trace(formatTuple(t));
        }
    }

    @Override
    public void trace(String msg, Throwable t) {
        if (traceEnabled) {
            log(output(), "TRACE", name, msg, t);
        }
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return traceEnabled;
    }

    @Override
    public void trace(Marker marker, String msg) {
        if (traceEnabled) {
            trace(msg);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        if (traceEnabled) {
            trace(format, arg);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        if (traceEnabled) {
            trace(format, arg1, arg2);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        if (traceEnabled) {
            trace(format, argArray);
        }
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        trace(msg, t);
    }

    @Override
    public boolean isDebugEnabled() {
        PythonLogger d = getDelegate();
        if (d == null) {
            return debugEnabled;
        } else {
            return d.isDebugEnabled();
        }
    }

    @Override
    public void debug(String msg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (debugEnabled) {
                log(output(), "DEBUG", name, msg, null);
            }
        } else {
            d.debug(msg);
        }
    }

    @Override
    public void debug(String format, Object arg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (debugEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg);
                debug(formatTuple(t));
            }
        } else {
            d.debug(format, arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (debugEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
                debug(formatTuple(t));
            }
        } else {
            d.debug(format, arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (debugEnabled) {
                FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
                debug(formatTuple(t));
            }
        } else {
            d.debug(format, arguments);
        }
    }

    @Override
    public void debug(String msg, Throwable t) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (debugEnabled) {
                log(output(), "DEBUG", name, msg, t);
            }
        } else {
            d.debug(msg, t);
        }
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return debugEnabled;
    }

    @Override
    public void debug(Marker marker, String msg) {
        debug(msg);
    }

    @Override
    public void debug(Marker marker, String format, Object arg) {
        debug(format, arg);
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        debug(format, arg1, arg2);
    }

    @Override
    public void debug(Marker marker, String format, Object... argArray) {
        debug(format, argArray);
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t) {
        debug(msg, t);
    }

    @Override
    public boolean isInfoEnabled() {
        return infoEnabled;
    }

    @Override
    public void info(String msg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (infoEnabled) {
                log(output(), "INFO", name, msg, null);
            }
        } else {
            d.info(msg);
        }
    }

    @Override
    public void info(String format, Object arg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (infoEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg);
                info(formatTuple(t));
            }
        } else {
            d.info(format, arg);
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (infoEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
                info(formatTuple(t));
            }
        } else {
            d.info(format, arg1, arg2);
        }
    }

    @Override
    public void info(String format, Object... arguments) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (infoEnabled) {
                FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
                info(formatTuple(t));
            }
        } else {
            d.info(format, arguments);
        }
    }

    @Override
    public void info(String msg, Throwable t) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (infoEnabled) {
                log(output(), "INFO", name, msg, t);
            }
        } else {
            d.info(msg, t);
        }
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return infoEnabled;
    }

    @Override
    public void info(Marker marker, String msg) {
        info(msg);
    }

    @Override
    public void info(Marker marker, String format, Object arg) {
        info(format, arg);
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        info(format, arg1, arg2);
    }

    @Override
    public void info(Marker marker, String format, Object... argArray) {
        info(format, argArray);
    }

    @Override
    public void info(Marker marker, String msg, Throwable t) {
        info(msg, t);
    }

    @Override
    public boolean isWarnEnabled() {
        return warnEnabled;
    }

    @Override
    public void warn(String msg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (warnEnabled) {
                log(output(), "WARN", name, msg, null);
            }
        } else {
            d.warn(msg);
        }
    }

    @Override
    public void warn(String format, Object arg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (warnEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg);
                warn(formatTuple(t));
            }
        } else {
            d.warn(format, arg);
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (warnEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
                warn(formatTuple(t));
            }
        } else {
            d.warn(format, arg1, arg2);
        }
    }

    @Override
    public void warn(String format, Object... arguments) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (warnEnabled) {
                FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
                warn(formatTuple(t));
            }
        } else {
            d.warn(format, arguments);
        }
    }

    @Override
    public void warn(String msg, Throwable t) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (warnEnabled) {
                log(output(), "WARN", name, msg, t);
            }
        } else {
            d.warn(msg, t);
        }
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return warnEnabled;
    }

    @Override
    public void warn(Marker marker, String msg) {
        warn(msg);
    }

    @Override
    public void warn(Marker marker, String format, Object arg) {
        warn(format, arg);
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        warn(format, arg1, arg2);
    }

    @Override
    public void warn(Marker marker, String format, Object... argArray) {
        warn(format, argArray);
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        warn(msg, t);
    }

    @Override
    public boolean isErrorEnabled() {
        return errorEnabled;
    }

    @Override
    public void error(String msg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (errorEnabled) {
                log(System.err, "ERROR", name, msg, null);
            }
        } else {
            d.error(msg);
        }
    }

    @Override
    public void error(String format, Object arg) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (errorEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg);
                error(formatTuple(t));
            }
        } else {
            d.error(format, arg);
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (errorEnabled) {
                FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
                error(formatTuple(t));
            }
        } else {
            d.error(format, arg1, arg2);
        }
    }

    @Override
    public void error(String format, Object... arguments) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (errorEnabled) {
                FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
                error(formatTuple(t));
            }
        } else {
            d.error(format, arguments);
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        PythonLogger d = getDelegate();
        if (d == null) {
            if (errorEnabled) {
                log(System.err, "ERROR", name, msg, t);
            }
        } else {
            d.error(msg, t);
        }
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return errorEnabled;
    }

    @Override
    public void error(Marker marker, String msg) {
        error(msg);
    }

    @Override
    public void error(Marker marker, String format, Object arg) {
        error(format, arg);
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        error(format, arg1, arg2);
    }

    @Override
    public void error(Marker marker, String format, Object... argArray) {
        error(format, argArray);
    }

    @Override
    public void error(Marker marker, String msg, Throwable t) {
        error(msg, t);
    }

    private void log(PrintStream stream, String level, String loggerName, String message, Throwable throwable) {
        stream.println("[" + level + "] " + loggerName + " - " + message);
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            stream.println(sw);
        }
    }

    private PrintStream output() {
        String stream = System.getProperty(FALLBACK_STREAM_PROPERTY);
        if (stream == null) {
            stream = System.getenv(FALLBACK_STREAM_ENVIRONMENT);
        }
        return "stderr".equalsIgnoreCase(stream) ? System.err : System.out;
    }

    private String formatTuple(FormattingTuple t) {
        if (t.getThrowable() != null) {
            return t.getMessage() + "\n" + getStackTraceAsString(t.getThrowable());
        }
        return t.getMessage();
    }

    private String getStackTraceAsString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}
