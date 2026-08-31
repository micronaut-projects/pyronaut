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
package io.micronaut.pyronaut.jarbuild;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable inputs for {@link FatJarPackager}.
 */
public final class FatJarRequest {
    private final Path output;
    private final Path applicationClasses;
    private final List<Path> resourceDirectories;
    private final List<Path> classpath;
    private final String applicationName;
    private final String applicationVersion;
    private final String mainClass;

    private FatJarRequest(Builder builder) {
        output = builder.output.toAbsolutePath().normalize();
        applicationClasses = builder.applicationClasses.toAbsolutePath().normalize();
        resourceDirectories = normalize(builder.resourceDirectories);
        classpath = normalize(builder.classpath);
        applicationName = requireText(builder.applicationName, "applicationName");
        applicationVersion = requireText(builder.applicationVersion, "applicationVersion");
        mainClass = requireText(builder.mainClass, "mainClass");
    }

    /**
     * Creates a request builder.
     *
     * @param output output JAR
     * @param applicationClasses processed application classes directory
     * @param mainClass target main class invoked by the FAT JAR launcher
     * @return builder
     */
    public static Builder builder(Path output, Path applicationClasses, String mainClass) {
        return new Builder(output, applicationClasses, mainClass);
    }

    /**
     * Returns the output JAR path.
     *
     * @return output JAR path
     */
    public Path output() {
        return output;
    }

    /**
     * Returns the processed application classes directory.
     *
     * @return processed application classes directory
     */
    public Path applicationClasses() {
        return applicationClasses;
    }

    /**
     * Returns the ordered application resource directories.
     *
     * @return ordered application resource directories
     */
    public List<Path> resourceDirectories() {
        return resourceDirectories;
    }

    /**
     * Returns the ordered dependency roots.
     *
     * @return ordered dependency JAR and directory entries
     */
    public List<Path> classpath() {
        return classpath;
    }

    /**
     * Returns the application name.
     *
     * @return application name written to output metadata
     */
    public String applicationName() {
        return applicationName;
    }

    /**
     * Returns the application version.
     *
     * @return application version written to output metadata
     */
    public String applicationVersion() {
        return applicationVersion;
    }

    /**
     * Returns the target main class.
     *
     * @return target main class invoked by the launcher
     */
    public String mainClass() {
        return mainClass;
    }

    private static List<Path> normalize(List<Path> paths) {
        return paths.stream()
            .map(Objects::requireNonNull)
            .map(path -> path.toAbsolutePath().normalize())
            .toList();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    /**
     * Builder for {@link FatJarRequest}.
     */
    public static final class Builder {
        private final Path output;
        private final Path applicationClasses;
        private final String mainClass;
        private final List<Path> resourceDirectories = new ArrayList<>();
        private final List<Path> classpath = new ArrayList<>();
        private String applicationName = "application";
        private String applicationVersion = "0.1.0";

        private Builder(Path output, Path applicationClasses, String mainClass) {
            this.output = Objects.requireNonNull(output, "output");
            this.applicationClasses = Objects.requireNonNull(applicationClasses, "applicationClasses");
            this.mainClass = mainClass;
        }

        /**
         * Sets the application name.
         *
         * @param applicationName application name
         * @return this builder
         */
        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        /**
         * Sets the application version.
         *
         * @param applicationVersion application version
         * @return this builder
         */
        public Builder applicationVersion(String applicationVersion) {
            this.applicationVersion = applicationVersion;
            return this;
        }

        /**
         * Appends one resource root.
         *
         * @param resourceDirectory resource directory appended to the ordered roots
         * @return this builder
         */
        public Builder resourceDirectory(Path resourceDirectory) {
            resourceDirectories.add(resourceDirectory);
            return this;
        }

        /**
         * Appends resource roots in list order.
         *
         * @param resourceDirectories resource directories appended to the ordered roots
         * @return this builder
         */
        public Builder resourceDirectories(List<Path> resourceDirectories) {
            this.resourceDirectories.addAll(resourceDirectories);
            return this;
        }

        /**
         * Appends one dependency root.
         *
         * @param classpathEntry dependency JAR or directory appended to the ordered classpath
         * @return this builder
         */
        public Builder classpathEntry(Path classpathEntry) {
            classpath.add(classpathEntry);
            return this;
        }

        /**
         * Appends dependency roots in list order.
         *
         * @param classpath dependency JARs and directories appended to the ordered classpath
         * @return this builder
         */
        public Builder classpath(List<Path> classpath) {
            this.classpath.addAll(classpath);
            return this;
        }

        /**
         * Creates the immutable request.
         *
         * @return immutable packaging request
         */
        public FatJarRequest build() {
            return new FatJarRequest(this);
        }
    }
}
