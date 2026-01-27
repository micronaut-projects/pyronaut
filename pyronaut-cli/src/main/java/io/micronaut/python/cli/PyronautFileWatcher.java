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

import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;
import io.micronaut.python.cli.util.FileUtils;
import io.micronaut.python.cli.util.MavenArtifact;
import io.micronaut.python.cli.util.PythonMavenRepository;

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
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.micronaut.python.cli.util.FileUtils.recurseDelete;
import static java.nio.file.Files.createDirectories;

/**
 * File watcher for Pyronaut applications that recompiles and restarts
 * the application when source files change.
 */
public class PyronautFileWatcher implements Runnable {
    private final UiController controller;

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;

    private final AtomicReference<WatchService> watchServiceRef = new AtomicReference<>();
    private final AtomicReference<Thread> watcherThread = new AtomicReference<>();

    private final Path rootDirectory;
    private final List<Path> sourceDirectories;
    private final PythonMavenRepository annotationProcessorRepo;
    private final PythonMavenRepository compileClassPathRepo;
    private final Path outputDirectory;
    private final String[] parameters;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final static List<String> WATCHED_DIRECTORIES = List.of("src", "config", "tests");
    private final AtomicInteger runCount = new AtomicInteger(0);
    private final String applicationManagerClassName;
    private final DataOutputStream eventOut;
    // In theory, this should always contain a single application thread. The use of a list is
    // a safety net in case our concurrency is wrong
    private final List<Thread> applicationThreads = Collections.synchronizedList(new ArrayList<>());

    public PyronautFileWatcher(
            Path rootDirectory,
            List<Path> sourceDirectories,
            PythonMavenRepository annotationProcessorRepo,
            PythonMavenRepository compileClassPathRepo,
            String[] parameters,
            String applicationManagerClassName,
            UiController controller,
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
        this.watcherThread.set(Thread.currentThread());
        try {
            var truffleClassloader = createTruffleClassLoader(compileClassPathRepo);

            // Dedicated compile/restart queue and worker
            BlockingQueue<List<UiModel.FileUpdate>> queue = new LinkedBlockingQueue<>();
            var worker = new Thread(() -> this.compileAndRunLoop(truffleClassloader, queue), "Pyronaut-Compiler");
            worker.setDaemon(true);
            worker.start();

            // Prime initial compile with a synthetic change list
            queue.offer(List.of());

            // Set up file watching
            var watchService = FileSystems.getDefault().newWatchService();
            watchServiceRef.set(watchService);
            for (var watchedDirectory : WATCHED_DIRECTORIES) {
                var dir = rootDirectory.resolve(watchedDirectory);
                if (Files.isDirectory(dir)) {
                    registerAll(dir, watchService);
                    //controller.dispatch(new UiAction.AddNotification("Watching for changes in " + dir, UiModel.Severity.INFO));
                    // Optionally write to logger instead, but not to System.out
                }
            }

            var outputPath = outputDirectory.toAbsolutePath();
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                Map<String, UiModel.UpdateType> latestChanges = new HashMap<>();
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

                    String path = changed.toAbsolutePath().toString();
                    UiModel.UpdateType type;
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        type = UiModel.UpdateType.ADDED;
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        type = UiModel.UpdateType.MODIFIED;
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        type = UiModel.UpdateType.DELETED;
                    } else {
                        type = UiModel.UpdateType.CHANGED;
                    }
                    latestChanges.put(path, type);
                }
                List<UiModel.FileUpdate> changedFiles = latestChanges.entrySet().stream()
                        .map(e -> new UiModel.FileUpdate(e.getKey(), e.getValue()))
                        .toList();

                if (!changedFiles.isEmpty()) {
                    controller.updateFiles(changedFiles);
                    controller.startCompiling();
                    // Signal the compiler thread to rebuild
                    queue.offer(changedFiles);
                }

                key.reset();
            }
        } catch (ClosedWatchServiceException ex) {
            // ignore
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void initializeLoggingSystem(URLClassLoader classLoader) {
        try {
            System.out.println("Initializing logging system");
            Class<?> logbackConfig = classLoader.loadClass("io.micronaut.pyronaut.logback.LogbackConfigurer");
            logbackConfig.getDeclaredMethod("initialize").invoke(logbackConfig);
        } catch (Exception e) {
            System.out.println("No logging system found on classpath, using defaults.");
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

    private void compileAndRunLoop(URLClassLoader truffleClassloader, BlockingQueue<List<UiModel.FileUpdate>> queue) {
        ApplicationManager appManager = null;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                List<UiModel.FileUpdate> updates = queue.poll(100, TimeUnit.MILLISECONDS);
                if (updates == null) {
                    continue;
                }
                long sd = System.nanoTime();
                controller.startCompiling();
                if (appManager != null) {
                    appManager.stopApplication();
                }
                if (compile(truffleClassloader) == SUCCESS) {
                    controller.stopCompiling();
                    var classpath = buildUrls(classesDirectory(), rootDirectory.resolve("config"));
                    var classLoader = new URLClassLoader(classpath, truffleClassloader);
                    var status = appManager == null ? "Application start " : "Restart ";
                    if (appManager == null) {
                        initializeLoggingSystem(classLoader);
                        appManager = new ApplicationManagerInvoker(classLoader, applicationManagerClassName);
                        if (eventOut != null) {
                            try {
                                appManager.setEventOutputStream(eventOut);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    ApplicationManager newApp = appManager;
                    var appThread = new Thread(() -> {
                        newApp.startApplication(parameters);
                        long ed = System.nanoTime();
                        var dur = Duration.ofNanos(ed - sd).toMillis();
                        controller.notify(status + "done in " + dur + "ms", UiModel.Severity.SUCCESS);
                    }) {
                        @Override
                        public void interrupt() {
                            try {
                                super.interrupt();
                                newApp.stopApplication();
                            } finally {
                                applicationThreads.remove(this);
                            }
                        }
                    };
                    applicationThreads.add(appThread);
                    appThread.setContextClassLoader(classLoader);
                    appThread.setName("Pyronaut Application Thread - " + runCount.incrementAndGet());
                    appThread.start();
                } else {
                    controller.stopCompiling();
                    controller.notify("Compilation failed, application not restarted", UiModel.Severity.ERROR);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            if (appManager != null) {
                try {
                    appManager.stopApplication();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void interruptSilently(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.interrupt();
        } catch (Exception ignored) {
            // closing context
        }
    }

    public Thread thread() {
        return watcherThread.get();
    }

    public void stop() {
        running.set(false);
        interruptSilently(watcherThread.get());
        try {
            var ws = watchServiceRef.get();
            if (ws != null) {
                ws.close();
            }
        } catch (Exception ignored) {
        }

        for (var applicationThread : applicationThreads) {
            if (applicationThread.isAlive()) {
                interruptSilently(applicationThread);
            }
        }
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
        private final AtomicBoolean started = new AtomicBoolean(false);

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
                started.set(true);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void stopApplication() {
            if (!started.get()) {
                return;
            }
            try {
                stoptMethod.invoke(applicationManager);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                started.set(false);
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
