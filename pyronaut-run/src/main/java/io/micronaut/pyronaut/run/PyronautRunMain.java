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
package io.micronaut.pyronaut.run;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.runtime.Micronaut;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Entry point for {@code pyronaut-run}.
 */
@CommandLine.Command(name = "pyronaut-run", mixinStandardHelpOptions = true, description = "Run a processed Pyronaut application")
public final class PyronautRunMain implements Callable<Integer> {
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_MAIN_CLASS = "pyronaut_application.PyronautMain";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--classes-dir", defaultValue = DEFAULT_CLASSES_DIR, description = "Processed classes directory")
    Path classesDir = Path.of(DEFAULT_CLASSES_DIR);

    @CommandLine.Option(names = "--config-dir", defaultValue = DEFAULT_CONFIG_DIR, description = "Configuration directory")
    Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @CommandLine.Option(names = "--main-class", defaultValue = DEFAULT_MAIN_CLASS, description = "Main class to invoke")
    String mainClass = DEFAULT_MAIN_CLASS;

    @CommandLine.Option(
        names = "--debug-vm",
        description = "Enable JVM JDWP debugging on port 5005 (flag is accepted for orchestrator forwarding)"
    )
    boolean debugVm;

    @CommandLine.Parameters
    List<String> appArgs = List.of();

    private final ClassResolver classResolver;
    private final ContextBootstrapper contextBootstrapper;
    private final ApplicationStarter applicationStarter;

    public PyronautRunMain() {
        this(Class::forName, GraalPyContextFactory::bootstrapReusableContext, PyronautRunMain::startMicronautApplication);
    }

    PyronautRunMain(ClassResolver classResolver,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter) {
        this.classResolver = classResolver;
        this.contextBootstrapper = contextBootstrapper;
        this.applicationStarter = applicationStarter;
    }

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            Path resolvedClassesDir = root.resolve(classesDir).normalize();
            if (!Files.isDirectory(resolvedClassesDir)) {
                System.err.println("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
                return 8;
            }

            Class<?> loadedClass = loadConfiguredMainClass();
            contextBootstrapper.bootstrap(resolveApplicationClassLoader(loadedClass));
            if (applicationStarter.start(loadedClass, resolvedClassesDir, appArgs)) {
                blockUntilInterrupted();
            }
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        }
    }

    private static boolean startMicronautApplication(Class<?> loadedClass,
                                                     Path resolvedClassesDir,
                                                     List<String> appArgs) {
        Micronaut micronaut = Micronaut.build(appArgs.toArray(String[]::new));
        if (loadedClass != null) {
            micronaut.mainClass(loadedClass);
        }

        List<String> packages = discoverApplicationPackages(resolvedClassesDir);
        if (!packages.isEmpty()) {
            micronaut.packages(packages.toArray(String[]::new));
        }
        micronaut.start();
        return true;
    }

    private Class<?> loadConfiguredMainClass() throws Exception {
        if (!DEFAULT_MAIN_CLASS.equals(mainClass)) {
            return classResolver.load(mainClass);
        }
        try {
            return classResolver.load(mainClass);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static ClassLoader resolveApplicationClassLoader(Class<?> loadedClass) {
        if (loadedClass != null) {
            return loadedClass.getClassLoader();
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : PyronautRunMain.class.getClassLoader();
    }

    @FunctionalInterface
    interface ClassResolver {
        Class<?> load(String className) throws Exception;
    }

    @FunctionalInterface
    interface ContextBootstrapper {
        void bootstrap(ClassLoader classLoader) throws Exception;
    }

    @FunctionalInterface
    interface ApplicationStarter {
        boolean start(Class<?> loadedClass, Path resolvedClassesDir, List<String> appArgs) throws Exception;
    }

    private static void blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> discoverApplicationPackages(Path classesDirectory) {
        try (Stream<Path> stream = Files.list(classesDirectory)) {
            return stream
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.isBlank())
                .filter(name -> !"META-INF".equals(name))
                .filter(name -> !"pyronaut_application".equals(name))
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautRunMain()).execute(args);
        System.exit(exitCode);
    }
}
