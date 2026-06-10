/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.test.pytest;

import io.micronaut.context.python.ContextHolder;
import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.core.util.StringUtils;
import io.micronaut.test.pytest.discovery.PytestDiscoverySelectorResolver;
import io.micronaut.test.pytest.execution.PytestPreconditionException;
import io.micronaut.test.pytest.execution.PytestTestExecutor;
import org.graalvm.polyglot.Context;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.DirectorySelector;
import org.junit.platform.engine.discovery.FileSelector;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;


/**
 * JUnit 5 TestEngine implementation for running pytest tests using GraalPy.
 * This engine discovers and executes Python tests written with pytest framework.
 */
public class PytestTestEngine implements TestEngine {
 
    public static final String ENGINE_ID = "pyronaut-pytest";
    public static final String TEST_SOURCE_DIR = "pytest.src.dir";
    public static final String TESTS = "pytest.tests";
    public static final String REPORT_DIR = "pytest.report.dir";
    public static final String JUNIT_XML_REPORT = "pytest.report.junit";
    public static final String HTML_REPORT = "pytest.report.html";
    public static final String LAST_NODEID_REPORT = "pytest.report.nodeid";
    public static final String EVENTS_REPORT = "pytest.report.events";
    private static final Logger LOG = LoggerFactory.getLogger(PytestTestEngine.class);
    private Context context = ContextHolder.isInitialized() && ContextHolder.isReuseContext() ? ContextHolder.getContext() : null;
    private String junitXmlReport;
    private String htmlReport;
    private String lastNodeIdReport;
    private String eventsReport;
 
    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        LOG.debug("Starting test discovery with uniqueId: {}", uniqueId);
        var configurationParameters = discoveryRequest.getConfigurationParameters();
        if (this.context == null) {
            createGraalPyContext(configurationParameters);
        } else {
            LOG.debug("Using context: {}", this.context);
        }

        var engineDescriptor = new EngineDescriptor(uniqueId, "Micronaut Pytest Engine");
        var selectorResolver = new PytestDiscoverySelectorResolver(context);
        selectorResolver.setTestFilters(PytestTestFilters.from(discoveryRequest.getConfigurationParameters().get(TESTS).orElse(null)));
        this.junitXmlReport = configurationParameters.get(JUNIT_XML_REPORT).orElse(null);
        this.htmlReport = configurationParameters.get(HTML_REPORT).orElse(null);
        this.lastNodeIdReport = configurationParameters.get(LAST_NODEID_REPORT).orElse(null);
        this.eventsReport = configurationParameters.get(EVENTS_REPORT).orElse(null);

        var testSrc = configurationParameters.get(TEST_SOURCE_DIR).orElse(null);
        Path baseDirectory = null;
        var explicitFileSelectors = discoveryRequest.getSelectorsByType(FileSelector.class);
        var explicitDirectorySelectors = discoveryRequest.getSelectorsByType(DirectorySelector.class);
        if (testSrc != null) {
            var srcPath = Paths.get(testSrc);
            if (Files.exists(srcPath)) {
                baseDirectory = srcPath;
                selectorResolver.setBaseDirectory(baseDirectory);
                if (explicitFileSelectors.isEmpty() && explicitDirectorySelectors.isEmpty()) {
                    var directorySelector = DiscoverySelectors.selectDirectory(testSrc);
                    selectorResolver.resolveSelectors(directorySelector, engineDescriptor);
                }
            }
        }

        // Process discovery selectors
        discoveryRequest.getSelectorsByType(DiscoverySelector.class).forEach(selector -> {
            LOG.debug("Processing selector: {}", selector);
            selectorResolver.resolveSelectors(selector, engineDescriptor);
        });

        // Apply filters
        discoveryRequest.getFiltersByType(ClassNameFilter.class).forEach(filter -> {
            LOG.debug("Applying class name filter: {}", filter);
        });
 
        discoveryRequest.getFiltersByType(PackageNameFilter.class).forEach(filter -> {
            LOG.debug("Applying package name filter: {}", filter);
        });


        LOG.debug("Discovery completed. Found {} test descriptors", engineDescriptor.getChildren().size());

        return engineDescriptor;
    }

    private void createGraalPyContext(ConfigurationParameters configurationParameters) {
        System.setProperty("org.graalvm.python.vfs.allow_multiple", StringUtils.TRUE);
        System.setProperty("org.graalvm.python.vfs.multiple_vfs_checks_as_warning", StringUtils.TRUE);
        Map<String, String> pythonOptions = new LinkedHashMap<>();
        configurationParameters.keySet().forEach(key -> {
            if (key.startsWith("python.")) {
                configurationParameters.get(key).ifPresent(value -> pythonOptions.put(key, value));
            }
        });
        try {
            this.context = GraalPyContextFactory.bootstrapReusableContext(
                PytestTestEngine.class.getClassLoader(),
                pythonOptions
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize GraalPy context: " + e.getMessage(), e);
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        if (context != null) {
            LOG.debug("Starting test execution");

            var rootDescriptor = request.getRootTestDescriptor();
            var executor = new PytestTestExecutor(
                this.context,
                request.getEngineExecutionListener(),
                junitXmlReport,
                htmlReport,
                lastNodeIdReport,
                eventsReport
            );

            try {
                executor.execute(rootDescriptor);
                LOG.debug("Test execution completed successfully");
            } catch (PytestPreconditionException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("Error during test execution", e);
                throw e;
            } finally {
                try {
                    ContextHolder.resetContext();
                    if (!ContextHolder.isReuseContext()) {
                        context.close();
                        context = null;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("io.micronaut.pyronaut");
    }

    @Override
    public Optional<String> getArtifactId() {
        return Optional.of("micronaut-pyronaut-pytest");
    }
}
