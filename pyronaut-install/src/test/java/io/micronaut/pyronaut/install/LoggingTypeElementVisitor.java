package io.micronaut.pyronaut.install;

import io.micronaut.inject.visitor.TypeElementVisitor;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** Test fixture: a processor that logs while being constructed. */
public final class LoggingTypeElementVisitor implements TypeElementVisitor<Object, Object> {
    public static final String OPTION = "pyronaut.test.logging-visitor";

    public LoggingTypeElementVisitor() {
        LoggerFactory.getLogger(LoggingTypeElementVisitor.class).debug("constructed");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OPTION);
    }
}
