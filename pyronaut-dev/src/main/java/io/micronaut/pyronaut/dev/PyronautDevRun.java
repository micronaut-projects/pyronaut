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
package io.micronaut.pyronaut.dev;

import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.run.PyronautRunMain;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PyronautDevRun extends PyronautRunMain {
    private static final String OPENAPI_SWAGGER_PATHS = "micronaut.router.static-resources.swagger.paths";
    private static final String OPENAPI_SWAGGER_MAPPING = "micronaut.router.static-resources.swagger.mapping";
    private static final String OPENAPI_SWAGGER_UI_PATHS = "micronaut.router.static-resources.swagger-ui.paths";
    private static final String OPENAPI_SWAGGER_UI_MAPPING = "micronaut.router.static-resources.swagger-ui.mapping";
    private static final String OPENAPI_REDOC_PATHS = "micronaut.router.static-resources.redoc.paths";
    private static final String OPENAPI_REDOC_MAPPING = "micronaut.router.static-resources.redoc.mapping";
    private static final List<TestResourcesProperty> TEST_RESOURCES_PROPERTIES = List.of(
            new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_URI", "micronaut.test.resources.server.uri"),
            new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "micronaut.test.resources.server.access.token"),
            new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "micronaut.test.resources.server.client.read.timeout")
    );
    private static final String EXTERNAL_DEVELOPMENT_MODE = "pyronaut.external.development";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST = "resolved-development-runtime-dependencies";

    @Override
    protected void resolveDefaultConfiguration(Path root, Path configDir) {
        applyDevelopmentOpenApiExposure(root, configDir);
    }

    @Override
    protected void configureEnv(Map<String, String> env) {
        applyTestResourcesProperties(env);
    }

    static void applyTestResourcesProperties(java.util.Map<String, String> environment) {
        for (TestResourcesProperty property : TEST_RESOURCES_PROPERTIES) {
            if (System.getProperty(property.systemProperty()) != null) {
                continue;
            }
            String value = environment.get(property.environmentVariable());
            if (value == null || value.isBlank()) {
                continue;
            }
            System.setProperty(property.systemProperty(), value);
        }
    }

    @Override
    protected Path resolveRunManifest(Path pyronautDir) {
        String environments = System.getProperty("micronaut.environments", "");
        if (Arrays.stream(environments.split(","))
                .map(String::trim)
                .anyMatch("dev"::equalsIgnoreCase)) {
            Path developmentManifest = pyronautDir.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST);
            if (Files.exists(developmentManifest)) {
                return developmentManifest;
            }
        }
        return pyronautDir.resolve(RUNTIME_DEPENDENCIES_MANIFEST);
    }

    @Override
    protected boolean includeClasspathEntry(String name) {
        String fileName = Path.of(name).getFileName().toString();
        if (!Boolean.getBoolean("micronaut.control-panel.enabled") && fileName.startsWith("micronaut-control-panel-")) {
            return true;
        }
        return isNativeImageRuntime() && isNativeUnsupportedJar(fileName);
    }

    /**
     * Whether a jar cannot run in the native launcher when it is loaded at runtime.
     *
     * <p>The macOS watch service in {@code micronaut-runtime-osx} calls into JNA, whose native
     * dispatch library fails in a Crema image ({@code NoClassDefFoundError: java/lang/Object}), and
     * the failure stops the application context. Without these jars the image's default
     * {@code WatchService} is used. The CLI drops them from {@code java.class.path} as well.
     */
    static boolean isNativeUnsupportedJar(String fileName) {
        return fileName.startsWith("micronaut-runtime-osx-") || fileName.startsWith("directory-watcher-");
    }

    private static boolean isNativeImageRuntime() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    @Override
    protected PyronautRunMain.ResolvedProjectLayout resolveExternalProjectLayout(Path root, ExternalProjectLayout external) throws IOException {
        ResolvedProjectLayout layout = super.resolveExternalProjectLayout(root, external);
        ArrayList<URL> urls = new ArrayList<>(layout.classpathUrls());
        boolean isDevMode = Boolean.getBoolean(EXTERNAL_DEVELOPMENT_MODE);
        if (isDevMode) {
            for (Path entry : external.testClasspath()) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith("micronaut-test-resources-client-")
                        || fileName.startsWith("micronaut-test-resources-core-")
                        || fileName.startsWith("micronaut-test-resources-codec-")) {
                    urls.add(entry.toUri().toURL());
                }
            }
        }
        if (isDevMode) {
            URL location = PyronautRunMain.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                urls.add(location);
            }
        }
        return new ResolvedProjectLayout(
                layout.processedClassesRoot(),
                urls
        );
    }

    private static void applyDevelopmentOpenApiExposure(Path projectDir, Path configuredConfigDir) {
        String environments = System.getProperty(MICRONAUT_ENVIRONMENTS, "");
        boolean development = java.util.Arrays.stream(environments.split(","))
                .map(String::trim)
                .anyMatch("dev"::equalsIgnoreCase);
        if (!development || hasExplicitOpenApiExposure(projectDir, configuredConfigDir)) {
            return;
        }
        setDefaultProperty(OPENAPI_SWAGGER_PATHS, "classpath:META-INF/swagger");
        setDefaultProperty(OPENAPI_SWAGGER_MAPPING, "/swagger/**");
        setDefaultProperty(OPENAPI_SWAGGER_UI_PATHS, "classpath:META-INF/swagger/views/swagger-ui");
        setDefaultProperty(OPENAPI_SWAGGER_UI_MAPPING, "/swagger-ui/**");
        setDefaultProperty(OPENAPI_REDOC_PATHS, "classpath:META-INF/swagger/views/redoc");
        setDefaultProperty(OPENAPI_REDOC_MAPPING, "/redoc/**");
    }

    private static boolean hasExplicitOpenApiExposure(Path projectDir, Path configuredConfigDir) {
        Path configDir = configuredConfigDir.isAbsolute() ? configuredConfigDir : projectDir.resolve(configuredConfigDir);
        for (String name : List.of("application.toml", "application.yml", "application.yaml", "application.properties")) {
            Path config = configDir.resolve(name);
            try {
                if (Files.isRegularFile(config)) {
                    String content = Files.readString(config, StandardCharsets.UTF_8);
                    if (content.contains("static-resources") && content.contains("swagger")) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
                // Defaults remain best effort when configuration cannot be read.
            }
        }
        return false;
    }

    private record TestResourcesProperty(String environmentVariable, String systemProperty) {
    }

}
