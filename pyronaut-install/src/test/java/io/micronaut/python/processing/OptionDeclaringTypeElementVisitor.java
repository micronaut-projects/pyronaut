package io.micronaut.python.processing;

import io.micronaut.inject.visitor.TypeElementVisitor;

import java.util.Set;

/**
 * Test fixture: a visitor in the package the Python stub generators live in, declaring an option
 * an application is expected to be able to set. The package is the point of it.
 */
public final class OptionDeclaringTypeElementVisitor implements TypeElementVisitor<Object, Object> {
    public static final String OPTION = "micronaut.python.pool.ignoreDependencies";

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OPTION);
    }
}
