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
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintWriter;
import java.io.StringWriter;

import static io.micronaut.context.python.GraalPyRuntimeUtil.PYTHON;

/**
 * SLF4J Logger implementation that delegates to Python's logging module.
 * This logger is only used when a GraalPy context is available.
 *
 * @author Micronaut Team
 * @since 1.0.0
 */
final class PythonLogger implements Logger {

    private final String name;
    private final Value pythonLogger;
    private final Value debugMember;
    private final Value infoMember;
    private final Value warningMember;
    private final Value errorMember;
    private final Value isEnabledMember;

    PythonLogger(String name) {
        this.name = name;
        this.pythonLogger = PythonContextRuntime.getContext()
            .eval(PYTHON, "import logging; logging.getLogger('" + name + "')");
        this.debugMember = pythonLogger.getMember("debug");
        this.infoMember = pythonLogger.getMember("info");
        this.warningMember = pythonLogger.getMember("warning");
        this.errorMember = pythonLogger.getMember("error");
        this.isEnabledMember = pythonLogger.getMember("isEnabledFor");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isTraceEnabled() {
        return pythonLogger.getMember("isEnabledFor").execute(10).asBoolean();
    }

    @Override
    public void trace(String msg) {
        debugMember.execute(msg);
    }

    @Override
    public void trace(String format, Object arg) {
        FormattingTuple t = MessageFormatter.format(format, arg);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void trace(String format, Object... arguments) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void trace(String msg, Throwable t) {
        String message = msg + "\n" + getStackTraceAsString(t);
        debugMember.execute(message);
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return isTraceEnabled();
    }

    @Override
    public void trace(Marker marker, String msg) {
        trace(msg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        trace(format, arg);
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        trace(format, arg1, arg2);
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        trace(format, argArray);
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        trace(msg, t);
    }

    @Override
    public boolean isDebugEnabled() {
        return isEnabledMember.execute(20).asBoolean();
    }

    @Override
    public void debug(String msg) {
        debugMember.execute(msg);
    }

    @Override
    public void debug(String format, Object arg) {
        FormattingTuple t = MessageFormatter.format(format, arg);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void debug(String format, Object... arguments) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
        String message = formatTuple(t);
        debugMember.execute(message);
    }

    @Override
    public void debug(String msg, Throwable t) {
        String message = msg + "\n" + getStackTraceAsString(t);
        debugMember.execute(message);
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return isDebugEnabled();
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
        return isEnabledMember.execute(30).asBoolean();
    }

    @Override
    public void info(String msg) {
        infoMember.execute(msg);
    }

    @Override
    public void info(String format, Object arg) {
        FormattingTuple t = MessageFormatter.format(format, arg);
        String message = formatTuple(t);
        infoMember.execute(message);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
        String message = formatTuple(t);
        infoMember.execute(message);
    }

    @Override
    public void info(String format, Object... arguments) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
        String message = formatTuple(t);
        infoMember.execute(message);
    }

    @Override
    public void info(String msg, Throwable t) {
        String message = msg + "\n" + getStackTraceAsString(t);
        infoMember.execute(message);
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return isInfoEnabled();
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
        return isEnabledMember.execute(40).asBoolean();
    }

    @Override
    public void warn(String msg) {
        warningMember.execute(msg);
    }

    @Override
    public void warn(String format, Object arg) {
        FormattingTuple t = MessageFormatter.format(format, arg);
        String message = formatTuple(t);
        warningMember.execute(message);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
        String message = formatTuple(t);
        warningMember.execute(message);
    }

    @Override
    public void warn(String format, Object... arguments) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
        String message = formatTuple(t);
        warningMember.execute(message);
    }

    @Override
    public void warn(String msg, Throwable t) {
        String message = msg + "\n" + getStackTraceAsString(t);
        warningMember.execute(message);
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return isWarnEnabled();
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
        return isEnabledMember.execute(50).asBoolean();
    }

    @Override
    public void error(String msg) {
        errorMember.execute(msg);
    }

    @Override
    public void error(String format, Object arg) {
        FormattingTuple t = MessageFormatter.format(format, arg);
        String message = formatTuple(t);
        errorMember.execute(message);
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        FormattingTuple t = MessageFormatter.format(format, arg1, arg2);
        String message = formatTuple(t);
        errorMember.execute(message);
    }

    @Override
    public void error(String format, Object... arguments) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, arguments);
        String message = formatTuple(t);
        errorMember.execute(message);
    }

    @Override
    public void error(String msg, Throwable t) {
        String message = msg + "\n" + getStackTraceAsString(t);
        errorMember.execute(message);
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return isErrorEnabled();
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

    private String getStackTraceAsString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private String formatTuple(FormattingTuple t) {
        if (t.getThrowable() != null) {
            return t.getMessage() + "\n" + getStackTraceAsString(t.getThrowable());
        }
        return t.getMessage();
    }
}
