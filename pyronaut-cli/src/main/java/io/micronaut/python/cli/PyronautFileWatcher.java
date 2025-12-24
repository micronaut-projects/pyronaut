/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.python.cli;

//import io.micronaut.context.ApplicationContext;
//import io.micronaut.core.naming.Described;

import io.micronaut.python.cli.util.FileUtils;
import io.micronaut.python.cli.util.MavenArtifact;
import io.micronaut.python.cli.util.PythonMavenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static io.micronaut.python.cli.util.FileUtils.recurseDelete;
import static java.nio.file.Files.createDirectories;

/**
 * File watcher for Pyronaut applications that recompiles and restarts
 * the application when source files change.
 */
public class PyronautFileWatcher implements Runnable {
    private final io.micronaut.python.cli.ui.UiController controller;
    private static final Logger LOGGER = LoggerFactory.getLogger(PyronautFileWatcher.class);

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;

    private final Path rootDirectory;
    private final List<Path> sourceDirectories;
    private final PythonMavenRepository annotationProcessorRepo;
    private final PythonMavenRepository compileClassPathRepo;
    private final Path outputDirectory;
    private final String[] parameters;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final static List<String> WATCHED_DIRECTORIES = List.of("src", "config", "tests");
    private final String applicationManagerClassName;
    private final DataOutputStream eventOut;

    public PyronautFileWatcher(
            Path rootDirectory,
            List<Path> sourceDirectories,
            PythonMavenRepository annotationProcessorRepo,
            PythonMavenRepository compileClassPathRepo,
            String[] parameters,
            String applicationManagerClassName,
            io.micronaut.python.cli.ui.UiController controller,
            DataOutputStream eventOut) {
        this.rootDirectory = rootDirectory;
        this.sourceDirectories = sourceDirectories.stream().map(Path::toAbsolutePath).collect(Collectors.toList());
        this.outputDirectory = FileUtils.resolveOutputDirectory(rootDirectory);
        this.annotationProcessorRepo = annotationProcessorRepo;
        this.compileClassPathRepo = compileClassPathRepo;
        this.parameters = parameters;
        this.applicationManagerClassName = applicationManagerClassName;
        this.controller = controller;
        this.eventOut = eventOut;
    }

    @Override
    public void run() {
        ApplicationManager appManager = null;
        try {
            var truffleClassloader = createTruffleClassLoader(compileClassPathRepo);
            // Initial compilation and start
            if (controller != null) controller.startCompiling();
            if (compile(truffleClassloader) != SUCCESS) {
                if (controller != null) controller.stopCompiling();
                // UI context may not exist yet -- just abort here and don't print
                return;
            }
            if (controller != null) controller.stopCompiling();
            var classpath = buildUrls(classesDirectory(), rootDirectory.resolve("config"));
            var classLoader = new URLClassLoader(classpath, truffleClassloader);
            Thread.currentThread().setContextClassLoader(classLoader);
            appManager = new ApplicationManagerInvoker(classLoader, applicationManagerClassName);
            // Set event protocol output if present
            if (eventOut != null) {
                try {
                    appManager.setEventOutputStream(eventOut);
                } catch (Exception e) {
                    // If this fails, proceed without protocol events
                }
            }
            appManager.startApplication(parameters);
            // Set up file watching
            var watchService = FileSystems.getDefault().newWatchService();
            for (var watchedDirectory : WATCHED_DIRECTORIES) {
                var dir = rootDirectory.resolve(watchedDirectory);
                if (Files.isDirectory(dir)) {
                    registerAll(dir, watchService);
                    //controller.dispatch(new UiAction.AddNotification("Watching for changes in " + dir, UiModel.Severity.INFO));
                    // Optionally write to logger instead, but not to System.out
                }
            }

            var outputPath = outputDirectory.toAbsolutePath();
            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                List<String> changedFiles = new ArrayList<>();
                for (WatchEvent<?> event : key.pollEvents()) {
                    var kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    var changed = ((Path) key.watchable()).resolve((Path) event.context());
                    if (Files.isHidden(changed) ||
                            changed.toAbsolutePath().startsWith(outputPath)) {
                        continue;
                    }

                    String label;
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        label = "[ADDED]   ";
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        label = "[UPDATED] ";
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        label = "[REMOVED] ";
                    } else {
                        label = "[CHANGED] ";
                    }
                    changedFiles.add(label + changed.toAbsolutePath());
                }

                if (!changedFiles.isEmpty()) {
                    if (starting.compareAndSet(false, true)) {
                        long sd = System.nanoTime();
                        if (controller != null) {
                            controller.updateFiles(changedFiles);
                            controller.startCompiling();
                        }
                        appManager.stopApplication();
                        if (compile(truffleClassloader) == SUCCESS) {
                            if (controller != null) controller.stopCompiling();
                            appManager.startApplication(parameters);
                            long ed = System.nanoTime();
                            var dur = Duration.ofNanos(ed - sd).toMillis();
                            if (controller != null) controller.notify("Restart done in " + dur + "ms", io.micronaut.python.cli.ui.UiModel.Severity.SUCCESS);
                        } else {
                            if (controller != null) controller.stopCompiling();
                            if (controller != null) controller.notify("Compilation failed, application not restarted", io.micronaut.python.cli.ui.UiModel.Severity.ERROR);
                        }
                    }
                    starting.set(false);
                }

                key.reset();
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            if (appManager != null) {
                appManager.stopApplication();
            }
            // No more socket to close
        }
    }

    private URLClassLoader createTruffleClassLoader(PythonMavenRepository repo) {
        var urls = repo.visitRepo(PyronautFileWatcher::isTruffleJar)
                .stream()
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (MalformedURLException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList()
                .toArray(new URL[0]);
        var parent = this.getClass().getClassLoader().getParent();
        return new URLClassLoader(urls, parent);
    }

    /**
     * Determines if a Maven artifact is supposed to belong to the
     * Truffle/GraalVM engine, in which case it needs to be put in
     * a parent classloader.
     *
     * @param name the name of a file
     * @return true if it belongs to the Truffle runtime
     */
    private static boolean isTruffleJar(MavenArtifact name) {
        if (name.groupId().startsWith("org.graalvm")) {
            return true;
        }
        if (name.groupId().equals("org.bouncycastle")) {
            return true;
        }
        return false;
    }

    private void registerAll(Path start, WatchService watchService) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private int compile(ClassLoader truffleClassloader) {
        // During compilation, show Compiling state/status in the UI, stream output to UI
        // (No StreamsCapture static context available here - move trigger up to caller if needed)
        var compiler = new PyronautCliCompiler();
        compiler.classLoader = truffleClassloader;
        compiler.sourceDirectory = sourceDirectories.stream().map(Path::toFile).collect(Collectors.toList());
        compiler.outputDirectory = classesDirectory().toFile();
        compiler.annotationProcessorPath = annotationProcessorRepo.asClasspath();
        compiler.classpath = compileClassPathRepo.asClasspath();

        try {
            recurseDelete(classesDirectory());
            createDirectories(classesDirectory());
            int result = compiler.call();
            // No StreamsCapture static context available here
            return result;
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            // No StreamsCapture static context available here
            return ERROR;
        }
    }

    private Path classesDirectory() {
        return outputDirectory.resolve(FileUtils.CLASSES_DIR);
    }

    public void stop() {
        running.set(false);
    }

    private URL[] buildUrls(Path... paths) throws MalformedURLException {
        var compileClassPath = compileClassPathRepo.visitRepo(a -> !isTruffleJar(a));
        var result = new ArrayList<URL>(1 + paths.length + compileClassPath.size());
        for (var path : paths) {
            result.add(path.toUri().toURL());
        }
        for (var file : compileClassPath) {
            var url = file.toURI().toURL();
            result.add(url);
        }
        // This is a hack, so that the launcher is on classpath of the user app
        // and it won't work in a native image
        result.add(PyronautFileWatcher.class.getProtectionDomain().getCodeSource().getLocation());
        return result.toArray(new URL[0]);
    }

    /**
     * This application manager is used to invoke an application "reflectively"
     * using method handles. This is done because we have to isolate the classloader
     * of the compiler from the application classloader. Therefore, we cannot use
     * types from Micronaut Context (e.g ApplicationContext) directly, because they
     * would be loaded from different classloaders.
     */
    private static class ApplicationManagerInvoker implements ApplicationManager {
        private final MethodHandle constructor;
        private final MethodHandle startMethod;
        private final MethodHandle stoptMethod;
        private final MethodHandle setEventOutputStreamMethod;
        private final Object applicationManager;

        private ApplicationManagerInvoker(ClassLoader classLoader, String className) {
            try {
                var clazz = classLoader.loadClass(className);
                var lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                var voidType = MethodType.methodType(void.class);
                constructor = lookup.findConstructor(clazz, voidType);
                startMethod = lookup.findVirtual(clazz, "startApplication",
                        MethodType.methodType(void.class, String[].class));
                stoptMethod = lookup.findVirtual(clazz, "stopApplication", voidType);
                setEventOutputStreamMethod = lookup.findVirtual(clazz, "setEventOutputStream", MethodType.methodType(void.class, DataOutputStream.class));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            applicationManager = newManager();
        }

        private Object newManager() {
            try {
                return constructor.invoke();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void startApplication(String[] args) {
            try {
                startMethod.invoke(applicationManager, args);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void stopApplication() {
            try {
                stoptMethod.invoke(applicationManager);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public void setEventOutputStream(DataOutputStream out) {
            if (setEventOutputStreamMethod != null) {
                try {
                    setEventOutputStreamMethod.invoke(applicationManager, out);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }
}
