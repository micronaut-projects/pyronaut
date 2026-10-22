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

import io.micronaut.pyronaut.run.PyronautRunMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FatJarPackagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsDeterministicRunnableJarWithOrderedRootsAndMetadata() throws Exception {
        Path dependencyOne = temporaryDirectory.resolve("dependency-one");
        compile(dependencyOne, List.of(),
            source("spi/Greeting.java", "package spi; public interface Greeting { String message(); }"),
            source("dep/Value.java", "package dep; public final class Value { public static String value() { return \"first\"; } }"),
            source("providers/One.java", "package providers; public final class One implements spi.Greeting { public String message() { return \"one\"; } }"));
        write(dependencyOne.resolve("META-INF/services/spi.Greeting"), "providers.One\n");
        write(dependencyOne.resolve("message.txt"), "one");
        write(dependencyOne.resolve("versioned-message.txt"), "base");

        Path dependencyTwo = temporaryDirectory.resolve("dependency-two");
        compile(dependencyTwo, List.of(dependencyOne),
            source("dep/Value.java", "package dep; public final class Value { public static String value() { return \"second\"; } }"),
            source("providers/Two.java", "package providers; public final class Two implements spi.Greeting { public String message() { return \"two\"; } }"));
        write(dependencyTwo.resolve("META-INF/services/spi.Greeting"), "providers.Two\n");
        write(dependencyTwo.resolve("message.txt"), "two");

        Path versioned = temporaryDirectory.resolve("versioned");
        compile(versioned, List.of(), source("dep/Value.java",
            "package dep; public final class Value { public static String value() { return \"first-versioned\"; } }"));
        write(versioned.resolve("versioned-message.txt"), "versioned");
        Path dependencyJar = temporaryDirectory.resolve("dependency.jar");
        createDependencyJar(dependencyJar, dependencyOne, versioned);

        Path applicationClasses = temporaryDirectory.resolve("application");
        compile(applicationClasses, List.of(dependencyOne), source("app/Main.java", """
            package app;
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            import java.util.*;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    System.out.println("args=" + String.join(",", args));
                    System.out.println("class=" + dep.Value.value());
                    var values = new ArrayList<String>();
                    for (spi.Greeting greeting : ServiceLoader.load(spi.Greeting.class)) values.add(greeting.message());
                    System.out.println("services=" + String.join(",", values));
                    var resources = Thread.currentThread().getContextClassLoader().getResources("message.txt");
                    var messages = new ArrayList<String>();
                    while (resources.hasMoreElements()) {
                        try (var input = resources.nextElement().openStream()) {
                            messages.add(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    System.out.println("resources=" + String.join(",", messages));
                    try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream("versioned-message.txt")) {
                        System.out.println("versioned-resource=" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    Package metadata = dep.Value.class.getPackage();
                    System.out.println("package=" + metadata.getImplementationVersion() + "," + metadata.isSealed());
                }
            }
            """));
        write(applicationClasses.resolve("META-INF/pyronaut/application-classes.idx"), "stale.Application\n");
        Path applicationResources = temporaryDirectory.resolve("application-resources");
        write(applicationResources.resolve("message.txt"), "application");

        Path outputOne = temporaryDirectory.resolve("one.jar");
        Path outputTwo = temporaryDirectory.resolve("two.jar");
        FatJarRequest request = request(outputOne, applicationClasses, applicationResources, dependencyJar, dependencyTwo);
        FatJarResult result = new FatJarPackager().packageApplication(request);
        new FatJarPackager().packageApplication(
            request(outputTwo, applicationClasses, applicationResources, dependencyJar, dependencyTwo)
        );

        assertEquals(outputOne, result.output());
        assertArrayEquals(Files.readAllBytes(outputOne), Files.readAllBytes(outputTwo));
        try (JarFile jar = new JarFile(outputOne.toFile())) {
            List<String> entryNames = jar.stream().map(JarEntry::getName).toList();
            assertEquals(entryNames.stream().sorted().toList(), entryNames);
            assertTrue(jar.stream().allMatch(entry -> entry.getTime() == 0L));
            assertTrue(jar.getEntry("PYRONAUT-INF/classpath.idx") != null);
            assertTrue(jar.getEntry("PYRONAUT-INF/app/classes/META-INF/pyronaut/application-classes.idx") != null);
            try (var input = jar.getInputStream(jar.getJarEntry(
                "PYRONAUT-INF/app/classes/META-INF/pyronaut/application-classes.idx"
            ))) {
                assertEquals("app.Main\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith(".SF") || entry.getName().endsWith(".RSA")));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith("INDEX.LIST")));
            try (var input = jar.getInputStream(jar.getJarEntry("PYRONAUT-INF/lib/0000/META-INF/MANIFEST.MF"))) {
                Manifest dependencyManifest = new Manifest(input);
                assertFalse(dependencyManifest.getMainAttributes().containsKey(Attributes.Name.CLASS_PATH));
                assertFalse(dependencyManifest.getAttributes("dep/Value.class").containsKey(new Attributes.Name("SHA-256-Digest")));
            }
        }

        Path runDirectory = Files.createDirectory(temporaryDirectory.resolve("run"));
        Process process = new ProcessBuilder(javaExecutable(), "-jar", outputOne.toString(), "alpha", "beta")
            .directory(runDirectory.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("args=alpha,beta"), output);
        assertTrue(output.contains("class=first-versioned"), output);
        assertTrue(output.contains("services=one,two"), output);
        assertTrue(output.contains("resources=application,one,two"), output);
        assertTrue(output.contains("versioned-resource=versioned"), output);
        assertTrue(output.contains("package=1.0,true"), output);
        try (var files = Files.list(runDirectory)) {
            assertEquals(0, files.count(), "launcher must not extract archive entries");
        }
    }

    @Test
    void rejectsMissingInputsAndUnsafeJarEntries() throws Exception {
        Path classes = Files.createDirectory(temporaryDirectory.resolve("classes"));
        FatJarRequest missingClasses = FatJarRequest.builder(
            temporaryDirectory.resolve("missing-classes.jar"), temporaryDirectory.resolve("absent-classes"), "app.Main"
        ).build();
        assertThrows(IllegalArgumentException.class, () -> new FatJarPackager().packageApplication(missingClasses));

        FatJarRequest missingResource = FatJarRequest.builder(
            temporaryDirectory.resolve("missing-resource.jar"), classes, "app.Main"
        ).resourceDirectory(temporaryDirectory.resolve("absent-resources")).build();
        assertThrows(IllegalArgumentException.class, () -> new FatJarPackager().packageApplication(missingResource));

        FatJarRequest missing = FatJarRequest.builder(
            temporaryDirectory.resolve("missing.jar"), classes, "app.Main")
            .classpathEntry(temporaryDirectory.resolve("absent.jar"))
            .build();
        assertThrows(IllegalArgumentException.class, () -> new FatJarPackager().packageApplication(missing));

        Path unsafe = temporaryDirectory.resolve("unsafe.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(unsafe))) {
            output.putNextEntry(new JarEntry("../escape.class"));
            output.write(1);
            output.closeEntry();
        }
        FatJarRequest request = FatJarRequest.builder(
            temporaryDirectory.resolve("unsafe-output.jar"), classes, "app.Main")
            .classpathEntry(unsafe)
            .build();
        assertThrows(IllegalArgumentException.class, () -> new FatJarPackager().packageApplication(request));

        FatJarRequest outputInsideInput = FatJarRequest.builder(
            classes.resolve("nested-output.jar"), classes, "app.Main"
        ).build();
        assertThrows(IllegalArgumentException.class, () -> new FatJarPackager().packageApplication(outputInsideInput));
    }

    @Test
    void enforcesSealingWhenALaterRootAttemptsToSealAnExistingPackage() throws Exception {
        Path unsealed = temporaryDirectory.resolve("unsealed");
        compile(unsealed, List.of(), source("split/First.java", "package split; public final class First { }"));
        Path sealed = temporaryDirectory.resolve("sealed");
        compile(sealed, List.of(), source("split/Second.java", "package split; public final class Second { }"));
        write(sealed.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nSealed: true\n\n");
        Path application = temporaryDirectory.resolve("sealing-application");
        compile(application, List.of(unsealed, sealed), source("app/Main.java", """
            package app;
            public final class Main {
                public static void main(String[] args) {
                    new split.First();
                    new split.Second();
                }
            }
            """));

        Path output = temporaryDirectory.resolve("sealing.jar");
        new FatJarPackager().packageApplication(request(output, application, unsealed, sealed));
        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(process.waitFor() != 0, processOutput);
        assertTrue(processOutput.contains("Sealing violation for package split"), processOutput);
    }

    @Test
    void resolvesResourcesWithSpecialCharactersInEntryNames() throws Exception {
        Path application = temporaryDirectory.resolve("odd-application");
        compile(application, List.of(), source("app/Main.java", """
            package app;
            import java.nio.charset.StandardCharsets;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    String name = "odd dir 100%/data#1 [x].txt";
                    var url = loader.getResource(name);
                    System.out.println("url=" + url);
                    try (var input = url.openStream()) {
                        System.out.println("odd=" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    int count = 0;
                    var all = loader.getResources(name);
                    while (all.hasMoreElements()) {
                        all.nextElement();
                        count++;
                    }
                    System.out.println("count=" + count);
                    System.out.println("missing=" + loader.getResource("odd dir 100%/absent #2.txt"));
                }
            }
            """));
        Path resources = temporaryDirectory.resolve("odd-resources");
        write(resources.resolve("odd dir 100%/data#1 [x].txt"), "odd-value");

        Path output = temporaryDirectory.resolve("odd.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(output, application, "app.Main")
                .applicationName("odd-app")
                .applicationVersion("1.0")
                .resourceDirectory(resources)
                .build()
        );
        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), processOutput);
        assertTrue(processOutput.contains("!/PYRONAUT-INF/app/resources/0000/odd%20dir%20100%25/data%231%20%5Bx%5D.txt"), processOutput);
        assertTrue(processOutput.contains("odd=odd-value"), processOutput);
        assertTrue(processOutput.contains("count=1"), processOutput);
        assertTrue(processOutput.contains("missing=null"), processOutput);
    }

    @Test
    void enumeratesClasspathDirectoriesAsScannableJarRoots() throws Exception {
        Path application = temporaryDirectory.resolve("scan-application");
        compile(application, List.of(), source("app/Main.java", """
            package app;
            import java.net.JarURLConnection;
            import java.net.URL;
            import java.nio.charset.StandardCharsets;
            import java.util.*;
            import java.util.jar.*;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    for (URL url : Collections.list(loader.getResources("db/migration"))) {
                        JarURLConnection connection = (JarURLConnection) url.openConnection();
                        connection.setUseCaches(false);
                        System.out.println("entry=" + connection.getEntryName() + " directory=" + connection.getJarEntry().isDirectory());
                        List<String> names = new ArrayList<>();
                        try (JarFile jar = connection.getJarFile()) {
                            for (JarEntry entry : Collections.list(jar.entries())) {
                                if (entry.getName().startsWith("db/migration/") && !entry.isDirectory()) {
                                    names.add(entry.getName());
                                }
                            }
                            Collections.sort(names);
                            System.out.println("names=" + names);
                            for (String name : names) {
                                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                                    System.out.println("view " + name + "=" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                        String first = names.get(0).substring("db/migration/".length());
                        try (var input = new URL(url, first).openStream()) {
                            System.out.println("relative " + first + "=" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    System.out.println("trailing=" + Collections.list(loader.getResources("db/migration/")).size());
                    System.out.println("single=" + (loader.getResource("db/migration") != null));
                    System.out.println("missing=" + loader.getResource("db/absent"));
                    System.out.println("file=" + loader.getResource("db/migration/V1__first.sql"));
                    // Flyway pairs scanned names with getResources() results by URL path containment.
                    System.out.println("contained=" + loader.getResource("db/migration/V1__first.sql").getPath()
                        .contains(loader.getResource("db/migration").getPath()));
                    try (var input = new URL(loader.getResource("db/migration/nested"), "/db/migration/V1__first.sql").openStream()) {
                        System.out.println("root-relative=" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            """));
        Path firstResources = temporaryDirectory.resolve("scan-resources-1");
        write(firstResources.resolve("db/migration/V1__first.sql"), "first");
        write(firstResources.resolve("db/migration/nested/V2__nested.sql"), "nested");
        Path secondResources = temporaryDirectory.resolve("scan-resources-2");
        write(secondResources.resolve("db/migration/V3__second.sql"), "second");
        Path dependency = temporaryDirectory.resolve("scan-dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency))) {
            addEntry(output, "db/migration/V4__dependency.sql", "dependency");
        }

        Path output = temporaryDirectory.resolve("scan.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(output, application, "app.Main")
                .applicationName("scan-app")
                .applicationVersion("1.0")
                .resourceDirectory(firstResources)
                .resourceDirectory(secondResources)
                .classpath(List.of(dependency))
                .build()
        );
        String processOutput = runJar(output);

        assertTrue(processOutput.contains("entry=db/migration/ directory=true"), processOutput);
        assertTrue(processOutput.contains("names=[db/migration/V1__first.sql, db/migration/nested/V2__nested.sql]"), processOutput);
        assertTrue(processOutput.contains("view db/migration/nested/V2__nested.sql=nested"), processOutput);
        assertTrue(processOutput.contains("relative V1__first.sql=first"), processOutput);
        assertTrue(processOutput.contains("names=[db/migration/V3__second.sql]"), processOutput);
        assertTrue(processOutput.contains("names=[db/migration/V4__dependency.sql]"), processOutput);
        assertTrue(processOutput.contains("relative V4__dependency.sql=dependency"), processOutput);
        assertTrue(processOutput.contains("trailing=3"), processOutput);
        assertTrue(processOutput.contains("single=true"), processOutput);
        assertTrue(processOutput.contains("missing=null"), processOutput);
        assertTrue(processOutput.contains("contained=true"), processOutput);
        assertTrue(processOutput.contains("root-relative=first"), processOutput);
        // Individual resources keep their plain JDK jar: URLs.
        assertTrue(processOutput.contains("file=jar:file:") && processOutput.contains("!/PYRONAUT-INF/app/resources/0000/db/migration/V1__first.sql"), processOutput);
    }

    @Test
    void runsFlywayClasspathMigrationsFromTheJar() throws Exception {
        List<Path> flywayClasspath = Arrays.stream(System.getProperty("pyronaut.test.flyway.classpath").split(System.getProperty("path.separator")))
            .map(Path::of)
            .toList();
        Path application = temporaryDirectory.resolve("flyway-application");
        compile(application, flywayClasspath, source("app/Main.java", """
            package app;
            import org.flywaydb.core.Flyway;
            import java.sql.*;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    String url = "jdbc:h2:mem:pyronaut;DB_CLOSE_DELAY=-1";
                    var result = Flyway.configure().dataSource(url, "sa", "").load().migrate();
                    System.out.println("executed=" + result.migrationsExecuted);
                    try (Connection connection = DriverManager.getConnection(url, "sa", "");
                         ResultSet rows = connection.createStatement().executeQuery("select name from caches order by name")) {
                        while (rows.next()) {
                            System.out.println("row=" + rows.getString(1));
                        }
                    }
                }
            }
            """));
        Path resources = temporaryDirectory.resolve("flyway-resources");
        write(resources.resolve("db/migration/V1__caches.sql"), "create table caches (name varchar(64) primary key);");
        write(resources.resolve("db/migration/V2__seed.sql"), "insert into caches values ('pets');");

        Path output = temporaryDirectory.resolve("flyway.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(output, application, "app.Main")
                .applicationName("flyway-app")
                .applicationVersion("1.0")
                .resourceDirectory(resources)
                .classpath(flywayClasspath)
                .build()
        );
        String processOutput = runJar(output);

        assertTrue(processOutput.contains("executed=2"), processOutput);
        assertTrue(processOutput.contains("row=pets"), processOutput);
    }

    @Test
    void keepsArchiveOpenForThreadsThatOutliveMain() throws Exception {
        Path application = temporaryDirectory.resolve("background-application");
        compile(application, List.of(),
            source("app/Lazy.java", "package app; public final class Lazy { public static String value() { return \"lazy-loaded\"; } }"),
            source("app/Main.java", """
                package app;
                public final class Main {
                    public static void main(String[] args) {
                        Thread worker = new Thread(() -> {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                return;
                            }
                            System.out.println("background=" + app.Lazy.value());
                        });
                        worker.start();
                        System.out.println("main-returned");
                    }
                }
                """));

        Path output = temporaryDirectory.resolve("background.jar");
        new FatJarPackager().packageApplication(request(output, application));
        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), processOutput);
        assertTrue(processOutput.contains("main-returned"), processOutput);
        assertTrue(processOutput.contains("background=lazy-loaded"), processOutput);
        assertFalse(processOutput.contains("zip file closed"), processOutput);
    }

    @Test
    void launchesTheRealProductionRuntimeMain() throws Exception {
        Path classes = temporaryDirectory.resolve("real-application");
        compile(classes, List.of(), source("real/Application.java", """
            package real;
            public final class Application {
                static {
                    System.out.println("packaged-application-discovered");
                    System.exit(0);
                }
            }
            """));
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        classpath.add(Path.of(PyronautRunMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        Arrays.stream(System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .map(Path::of)
            .filter(Files::exists)
            .forEach(classpath::add);
        Path output = temporaryDirectory.resolve("runtime.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(output, classes, PyronautRunMain.class.getName())
                .applicationName("runtime-test")
                .applicationVersion("1.0")
                .classpath(List.copyOf(classpath))
                .build()
        );

        Path runDirectory = Files.createDirectory(temporaryDirectory.resolve("real-run-directory"));
        write(runDirectory.resolve("pom.xml"), "<project/>");
        write(runDirectory.resolve("__pyronaut__/classes/rogue/Stale.class"), "not-a-class");
        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString())
            .directory(runDirectory.toFile())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), processOutput);
        assertTrue(processOutput.contains("packaged-application-discovered"), processOutput);
    }

    @Test
    void startsAMicronautContextFromDependencyBeanDefinitions() throws Exception {
        Path classes = temporaryDirectory.resolve("context-application");
        List<Path> dependencies = Arrays.stream(System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .map(Path::of)
            .filter(Files::exists)
            .toList();
        compile(classes, dependencies, source("context/Main.java", """
            package context;
            import io.micronaut.context.ApplicationContext;
            import io.micronaut.context.event.ApplicationEventPublisher;
            import io.micronaut.context.event.StartupEvent;
            import io.micronaut.core.type.Argument;
            public final class Main {
                public static void main(String[] args) {
                    try (ApplicationContext context = ApplicationContext.run()) {
                        context.getBean(Argument.of(ApplicationEventPublisher.class, StartupEvent.class));
                        System.out.println("context-started");
                    }
                }
            }
            """));
        Path output = temporaryDirectory.resolve("context.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(output, classes, "context.Main")
                .applicationName("context-test")
                .applicationVersion("1.0")
                .classpath(dependencies)
                .build()
        );

        try (JarFile jar = new JarFile(output.toFile())) {
            assertNotNull(jar.getJarEntry("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                + "io.micronaut.context.event.ApplicationEventPublisherFactory"));
        }
        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), processOutput);
        assertTrue(processOutput.contains("context-started"), processOutput);
    }

    @Test
    void commandLineAdapterBuildsRunnableJar() throws Exception {
        Path classes = temporaryDirectory.resolve("cli-classes");
        compile(classes, List.of(), source("cli/Main.java", """
            package cli;
            public final class Main {
                public static void main(String[] args) {
                    System.out.println(String.join(",", args));
                }
            }
            """));
        Path resources = temporaryDirectory.resolve("cli-resources");
        write(resources.resolve("application.properties"), "test=true\n");
        Path classpath = temporaryDirectory.resolve("cli-classpath.txt");
        write(classpath, "");
        Path output = temporaryDirectory.resolve("cli-output.jar");

        int exitCode = new CommandLine(new PyronautJarBuildMain()).execute(
            "--output", output.toString(),
            "--classes-dir", classes.toString(),
            "--resource-dir", resources.toString(),
            "--classpath-file", classpath.toString(),
            "--name", "cli-test",
            "--version", "1.0",
            "--main-class", "cli.Main"
        );
        assertEquals(0, exitCode);

        Process process = new ProcessBuilder(javaExecutable(), "-jar", output.toString(), "one", "two")
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), processOutput);
        assertTrue(processOutput.contains("one,two"), processOutput);
    }

    private FatJarRequest request(Path output, Path classes, Path... classpath) {
        return FatJarRequest.builder(output, classes, "app.Main")
            .applicationName("test-app")
            .applicationVersion("1.2.3")
            .classpath(List.of(classpath))
            .build();
    }

    private FatJarRequest request(Path output, Path classes, Path resources, Path first, Path second) {
        return FatJarRequest.builder(output, classes, "app.Main")
            .applicationName("test-app")
            .applicationVersion("1.2.3")
            .resourceDirectory(resources)
            .classpath(List.of(first, second))
            .build();
    }

    private void createDependencyJar(Path target, Path base, Path versioned) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MULTI_RELEASE, "true");
        attributes.put(Attributes.Name.CLASS_PATH, "/absolute/source/path.jar");
        Attributes packageAttributes = new Attributes();
        packageAttributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0");
        packageAttributes.put(Attributes.Name.SEALED, "true");
        manifest.getEntries().put("dep/", packageAttributes);
        Attributes signedEntry = new Attributes();
        signedEntry.putValue("SHA-256-Digest", "invalid-digest");
        manifest.getEntries().put("dep/Value.class", signedEntry);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            addDirectory(output, base, "");
            addDirectory(output, versioned, "META-INF/versions/9/");
            addEntry(output, "META-INF/TEST.SF", "invalid signature");
            addEntry(output, "META-INF/TEST.RSA", "invalid signature");
            addEntry(output, "META-INF/INDEX.LIST", "invalid index");
        }
    }

    private static void addDirectory(JarOutputStream output, Path directory, String prefix) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(prefix + directory.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static void addEntry(JarOutputStream output, String name, String contents) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private void compile(Path output, List<Path> classpath, Source... sources) throws IOException {
        Files.createDirectories(output);
        Path sourceRoot = Files.createTempDirectory(temporaryDirectory, "sources-");
        List<String> arguments = new ArrayList<>(List.of("-d", output.toString()));
        if (!classpath.isEmpty()) {
            arguments.add("-classpath");
            arguments.add(String.join(System.getProperty("path.separator"), classpath.stream().map(Path::toString).toList()));
        }
        for (Source source : sources) {
            Path sourceFile = sourceRoot.resolve(source.path());
            write(sourceFile, source.contents());
            arguments.add(sourceFile.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
    }

    private static Source source(String path, String contents) {
        return new Source(path, contents);
    }

    private static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8);
    }

    private static String runJar(Path jar) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(javaExecutable(), "-jar", jar.toString())
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), processOutput);
        return processOutput;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Source(String path, String contents) {
    }
}
