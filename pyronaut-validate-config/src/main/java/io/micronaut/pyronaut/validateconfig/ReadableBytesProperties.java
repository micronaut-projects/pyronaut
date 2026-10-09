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
package io.micronaut.pyronaut.validateconfig;

import io.micronaut.context.ConfigurableBeanContext;
import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.core.convert.format.ReadableBytes;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.jsonschema.configuration.validator.ConfigurationError;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Accepts human-readable byte sizes such as {@code 6MB} for properties bound through
 * {@link ReadableBytes}.
 *
 * <p>The configuration schemas type these properties as plain integers, so the schema validator
 * rejects {@code max-request-size = '6MB'} although Micronaut converts it with
 * {@code ReadableBytesTypeConverter}. The schemas carry no trace of the annotation, and neither do
 * bean definitions, which bind configuration setters in generated code. The annotation is
 * therefore read from the configuration class whose {@link ConfigurationReader} prefix owns the
 * property. Remove once the schemas describe readable byte sizes themselves.</p>
 */
final class ReadableBytesProperties {
    static final String EXPECTED_INTEGER = "Expected integer";
    // ReadableBytesTypeConverter: an optional KB, MB or GB suffix (any case) after a long.
    private static final Pattern READABLE_BYTES = Pattern.compile("([+-]?\\d+)(?:[KMG]B)?", Pattern.CASE_INSENSITIVE);

    private ReadableBytesProperties() {
    }

    /**
     * @param value A configured value
     * @return Whether {@code ReadableBytesTypeConverter} converts the value
     */
    static boolean isReadableBytes(Object value) {
        if (!(value instanceof CharSequence text)) {
            return false;
        }
        Matcher matcher = READABLE_BYTES.matcher(text);
        if (!matcher.matches()) {
            return false;
        }
        try {
            Long.parseLong(matcher.group(1));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * @param errors Validation errors
     * @return Whether any error rejects a readable byte size as a non-integer
     */
    static boolean hasCandidates(Set<ConfigurationError> errors) {
        return errors.stream().anyMatch(ReadableBytesProperties::isCandidate);
    }

    /**
     * Drops errors that reject a readable byte size for a property bound through
     * {@link ReadableBytes}.
     *
     * @param errors Validation errors
     * @param readableBytesProperty Whether a property is bound through {@link ReadableBytes}
     * @return The retained errors
     */
    static Set<ConfigurationError> withoutReadableBytesErrors(Set<ConfigurationError> errors, Predicate<String> readableBytesProperty) {
        return errors.stream()
            .filter(error -> !(isCandidate(error) && readableBytesProperty.test(error.property())))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Resolves properties against the configuration classes of a configured bean context.
     *
     * @param beanContext The configured bean context
     * @return Whether a property is bound through {@link ReadableBytes}
     */
    static Predicate<String> fromBeanContext(ConfigurableBeanContext beanContext) {
        List<BeanDefinitionReference<Object>> readers = beanContext.getBeanDefinitionReferences().stream()
            .filter(reference -> reference.stringValue(ConfigurationReader.class, "prefix").isPresent())
            .toList();
        return property -> {
            int lastDot = property.lastIndexOf('.');
            if (lastDot <= 0) {
                return false;
            }
            String parent = property.substring(0, lastDot);
            String name = NameUtils.camelCase(property.substring(lastDot + 1));
            for (BeanDefinitionReference<Object> reader : readers) {
                String prefix = reader.stringValue(ConfigurationReader.class, "prefix").orElse("");
                if (prefixMatches(prefix, parent) && isReadableBytes(beanType(reader), name)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * @param type A configuration class
     * @param propertyName The camel-case property name
     * @return Whether the class binds the property through {@link ReadableBytes}
     */
    static boolean isReadableBytes(Class<?> type, String propertyName) {
        if (type == null) {
            return false;
        }
        try {
            return hasAnnotatedAccessor(type, propertyName)
                || hasAnnotatedRecordComponent(type, propertyName)
                || hasAnnotatedField(type, propertyName);
        } catch (LinkageError | RuntimeException e) {
            // The class or its reflection metadata is unavailable; keep the error.
            return false;
        }
    }

    private static boolean hasAnnotatedAccessor(Class<?> type, String propertyName) {
        String setter = NameUtils.setterNameFor(propertyName);
        String getter = NameUtils.getterNameFor(propertyName);
        return Arrays.stream(type.getMethods()).anyMatch(method -> switch (method.getParameterCount()) {
            case 0 -> (method.getName().equals(getter) || method.getName().equals(propertyName)) && annotated(method);
            case 1 -> method.getName().equals(setter) && (annotated(method) || annotated(method.getParameters()[0]));
            default -> false;
        });
    }

    private static boolean hasAnnotatedRecordComponent(Class<?> type, String propertyName) {
        return type.isRecord() && Arrays.stream(type.getRecordComponents())
            .anyMatch(component -> component.getName().equals(propertyName) && annotated(component));
    }

    private static boolean hasAnnotatedField(Class<?> type, String propertyName) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (Arrays.stream(current.getDeclaredFields()).anyMatch(field -> field.getName().equals(propertyName) && annotated(field))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCandidate(ConfigurationError error) {
        return error.type() == ConfigurationError.Type.ERROR
            && EXPECTED_INTEGER.equals(error.message())
            && error.property() != null
            && isReadableBytes(error.rawValue());
    }

    private static boolean annotated(AnnotatedElement element) {
        return element.isAnnotationPresent(ReadableBytes.class);
    }

    private static Class<?> beanType(BeanDefinitionReference<Object> reference) {
        try {
            return reference.getBeanType();
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * @param prefix A configuration prefix, where {@code *} stands for one named entry
     * @param path A resolved property path without the property name
     * @return Whether the prefix covers the path
     */
    static boolean prefixMatches(String prefix, String path) {
        String[] prefixSegments = prefix.toLowerCase(Locale.ENGLISH).split("\\.");
        String[] pathSegments = path.toLowerCase(Locale.ENGLISH).split("\\.");
        if (prefixSegments.length != pathSegments.length) {
            return false;
        }
        for (int i = 0; i < prefixSegments.length; i++) {
            if (!prefixSegments[i].equals("*") && !prefixSegments[i].equals(pathSegments[i])) {
                return false;
            }
        }
        return true;
    }
}
