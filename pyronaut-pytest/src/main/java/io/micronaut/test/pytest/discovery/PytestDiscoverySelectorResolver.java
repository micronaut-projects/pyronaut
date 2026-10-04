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
package io.micronaut.test.pytest.discovery;

import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.PytestTestFilters;
import org.graalvm.polyglot.Context;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.discovery.DirectorySelector;
import org.junit.platform.engine.discovery.FileSelector;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves JUnit 5 discovery selectors into pytest test descriptors.
 */
public final class PytestDiscoverySelectorResolver {

    private static final Pattern JUNIT_MICRONAUT_TEST_IMPORT = Pattern.compile(
        "^from\\s+micronaut\\.test\\.extensions\\.junit5\\.annotation\\s+import\\s+MicronautTest(?:\\s+as\\s+(\\w+))?\\s*$",
        Pattern.MULTILINE
    );

    private static final Logger LOG = LoggerFactory.getLogger(PytestDiscoverySelectorResolver.class);

    private final Context context;
    private Path baseDirectory;
    private PytestTestFilters testFilters = PytestTestFilters.from(null);

    public PytestDiscoverySelectorResolver(Context context) {
        this.context = context;
    }
 
    /**
     * Sets the base directory for computing relative test paths.
     *
     * @param baseDirectory the directory used to compute relative paths for tests
     */
    public void setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public void setTestFilters(PytestTestFilters testFilters) {
        this.testFilters = testFilters == null ? PytestTestFilters.from(null) : testFilters;
    }

    /**
     * Resolves discovery selectors and adds corresponding test descriptors.
     *
     * @param selector The discovery selector to resolve
     * @param engineDescriptor The engine descriptor to add tests to
     */
    public void resolveSelectors(DiscoverySelector selector, EngineDescriptor engineDescriptor) {
        switch (selector) {
            case DirectorySelector directorySelector ->
                resolveDirectorySelector(directorySelector, engineDescriptor);
            case FileSelector fileSelector -> resolveFileSelector(fileSelector, engineDescriptor);
            default ->
                LOG.debug("Unsupported selector type: {}", selector.getClass().getSimpleName());
        }
    }

    /**
     * Resolve a directory selector by scanning for Python files.
     *
     * @param selector The directory selector
     * @param engineDescriptor The engine descriptor to populate
     */
    private void resolveDirectorySelector(DirectorySelector selector, EngineDescriptor engineDescriptor) {
        Path directory = selector.getPath();
        LOG.debug("Resolving directory: {}", directory);
        Path thisBase = this.baseDirectory;
        try {
            this.baseDirectory = directory;
            scanPythonDirectory(directory, engineDescriptor);
        } finally {
            this.baseDirectory = thisBase;
        }
    }

    /**
     * Resolve an individual file selector.
     *
     * @param selector The file selector
     * @param engineDescriptor The engine descriptor to populate
     */
    private void resolveFileSelector(FileSelector selector, EngineDescriptor engineDescriptor) {
        Path file = selector.getPath();
        LOG.debug("Resolving file: {}", file);

        if (file.toString().endsWith(".py")) {
            addPythonFile(file, engineDescriptor, isTestFile(file));
        }
    }

    /**
     * Scan a directory for Python files and add them to the engine descriptor.
     *
     * @param directory The directory to scan
     * @param engineDescriptor The engine descriptor to populate
     */
    private void scanPythonDirectory(Path directory, EngineDescriptor engineDescriptor) {
        LOG.debug("Scanning Python directory: {}", directory);

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".py"))
                 .forEach(path -> addPythonFile(path, engineDescriptor, true));
        } catch (IOException e) {
            LOG.error("Error scanning directory: {}", directory, e);
        }
    }

    private void addPythonFile(Path filePath, EngineDescriptor engineDescriptor, boolean isTestDirectory) {
        LOG.debug("Adding Python file: {}", filePath);

        try {
            if (isJUnitModule(filePath)) {
                LOG.debug("Skipping JUnit Python module from pytest discovery: {}", filePath);
                return;
            }
            PytestAstParser astParser = new PytestAstParser(context);
            List<TestDescriptor> testDescriptors = astParser.parsePythonFileAsTests(filePath, baseDirectory);

            for (TestDescriptor testDescriptor : testDescriptors) {
                if (testDescriptor instanceof PytestTestDescriptor pytestDescriptor) {
                    if (!testFilters.matches(pytestDescriptor.getUniqueId().toString())
                        && !testFilters.matches(pytestDescriptor.getDisplayName())
                        && !testFilters.matches(pytestDescriptor.getPytestNodeId())
                        && !testFilters.matches(pytestDescriptor.getFilePath().toString())) {
                        continue;
                    }
                }
                engineDescriptor.addChild(testDescriptor);
            }
        } catch (Exception e) {
            LOG.error("Error parsing Python file: {}", filePath, e);
        }
    }

    static boolean isJUnitModule(Path filePath) throws IOException {
        String source = Files.readString(filePath);
        Matcher importMatcher = JUNIT_MICRONAUT_TEST_IMPORT.matcher(source);
        if (!importMatcher.find()) {
            return false;
        }
        String annotationName = importMatcher.group(1);
        if (annotationName == null || annotationName.isBlank()) {
            annotationName = "MicronautTest";
        }
        Pattern call = Pattern.compile("^" + Pattern.quote(annotationName) + "\\s*\\(", Pattern.MULTILINE);
        return call.matcher(source).find();
    }

    /**
     * Determine if a file is a test by its name.
     *
     * @param file The file path
     * @return true if the file name starts with 'test_'
     */
    private boolean isTestFile(Path file) {
        return file.getFileName().startsWith("test_");
    }
}
