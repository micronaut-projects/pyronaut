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
package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.python.processing.PythonProcessingSession;
import picocli.CommandLine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Local transport for serialized compiler daemon requests.
 */
final class CompilerDaemon {
    private static final int MAGIC = 0x50595244;
    private static final int VERSION = 1;
    private static final byte PING = 1;
    private static final byte COMPILE = 2;
    private static final byte PONG = 3;
    private static final byte STDOUT = 4;
    private static final byte STDERR = 5;
    private static final byte EXIT = 6;
    private static final String COMMAND_PREFIX = "pyronaut.processor.daemon.command-prefix";
    private static final String IDLE_TIMEOUT = "pyronaut.processor.daemon.idle-timeout-seconds";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int ACCEPT_POLL_MILLIS = 1_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 30_000;
    private static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 600;
    private static final String METADATA_FILE = "daemon.properties";
    private static final String LOCK_FILE = "daemon.lock";
    private static final String LOG_FILE = "daemon.log";
    private static final int MAX_PROCESSING_SESSIONS = 4;

    private CompilerDaemon() {
    }

    static int execute(Path projectRoot, List<String> arguments) {
        Path directory = (ExternalProjectLayout.isExternal(projectRoot)
            ? ExternalProjectLayout.outputDirectory(projectRoot) : projectRoot.resolve("__pyronaut__")).resolve("daemon");
        String launchId = launchId();
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Endpoint endpoint = null;
            try {
                endpoint = endpoint(directory, launchId);
                return compile(endpoint, arguments);
            } catch (IOException e) {
                lastFailure = e;
                // Drop the stale or unresponsive endpoint so the next attempt starts a
                // replacement daemon under the startup lock.
                invalidate(directory);
            }
        }
        throw new UnavailableException(message(lastFailure), lastFailure);
    }

    static int runServer(Path directory, String token) {
        if (token == null || token.isBlank()) {
            System.err.println("Missing compiler daemon authentication token");
            return PyronautProcessorExitCode.PRECONDITION_FAILED.code();
        }
        SessionCompilerExecutor executor = new SessionCompilerExecutor();
        long idleTimeoutNanos = Duration.ofSeconds(idleTimeoutSeconds()).toNanos();
        try {
            Files.createDirectories(directory);
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                server.setSoTimeout(ACCEPT_POLL_MILLIS);
                writeEndpoint(directory, new Endpoint(
                    server.getLocalPort(),
                    token,
                    ProcessHandle.current().pid(),
                    launchId()
                ));
                long deadline = System.nanoTime() + idleTimeoutNanos;
                while (System.nanoTime() < deadline) {
                    Socket socket;
                    try {
                        socket = server.accept();
                    } catch (SocketTimeoutException ignored) {
                        // Poll the idle deadline.
                        continue;
                    }
                    try (socket) {
                        // Bound the request header phase so a stalled client cannot wedge the daemon.
                        socket.setSoTimeout(REQUEST_TIMEOUT_MILLIS);
                        if (serve(socket, token, executor)) {
                            deadline = System.nanoTime() + idleTimeoutNanos;
                        }
                    } catch (IOException e) {
                        // A failed connection must not take down the daemon.
                        appendDaemonFailure(directory, e);
                    }
                }
            }
            return PyronautProcessorExitCode.SUCCESS.code();
        } catch (Exception e) {
            appendDaemonFailure(directory, e);
            return PyronautProcessorExitCode.INTERNAL_ERROR.code();
        } finally {
            executor.close();
            deleteOwnedEndpoint(directory, token);
        }
    }

    private static boolean serve(Socket socket,
                                 String expectedToken,
                                 SessionCompilerExecutor executor) throws IOException {
        socket.setTcpNoDelay(true);
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            return false;
        }
        byte operation = input.readByte();
        if (!MessageDigest.isEqual(
            expectedToken.getBytes(StandardCharsets.UTF_8),
            readString(input).getBytes(StandardCharsets.UTF_8)
        )) {
            return false;
        }
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        if (operation == PING) {
            output.writeByte(PONG);
            output.flush();
            return true;
        }
        if (operation != COMPILE) {
            return false;
        }
        int argumentCount = input.readInt();
        if (argumentCount < 0 || argumentCount > 10_000) {
            return false;
        }
        String[] arguments = new String[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = readString(input);
        }

        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        PrintStream requestOut = new PrintStream(new FrameOutputStream(output, STDOUT), true, StandardCharsets.UTF_8);
        PrintStream requestErr = new PrintStream(new FrameOutputStream(output, STDERR), true, StandardCharsets.UTF_8);
        int exitCode;
        try {
            System.setOut(requestOut);
            System.setErr(requestErr);
            exitCode = new CommandLine(new PyronautProcessorMain(
                new PyprojectModelReader(),
                executor,
                true
            )).execute(arguments);
        } finally {
            requestOut.flush();
            requestErr.flush();
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        synchronized (output) {
            output.writeByte(EXIT);
            output.writeInt(exitCode);
            output.flush();
        }
        return true;
    }

    private static int compile(Endpoint endpoint, List<String> arguments) throws IOException {
        try (Socket socket = connect(endpoint)) {
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeByte(COMPILE);
            writeString(output, endpoint.token());
            output.writeInt(arguments.size());
            for (String argument : arguments) {
                writeString(output, argument);
            }
            output.flush();

            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            requireHeader(input);
            while (true) {
                byte frame = input.readByte();
                if (frame == EXIT) {
                    return input.readInt();
                }
                int length = input.readInt();
                if (length < 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("Invalid compiler daemon output frame");
                }
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) {
                    throw new IOException("Compiler daemon closed while forwarding output");
                }
                if (frame == STDOUT) {
                    System.out.write(bytes);
                    System.out.flush();
                } else if (frame == STDERR) {
                    System.err.write(bytes);
                    System.err.flush();
                } else {
                    throw new IOException("Unknown compiler daemon output frame");
                }
            }
        }
    }

    private static Endpoint endpoint(Path directory, String launchId) throws IOException {
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(LOCK_FILE);
        try (FileChannel channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            Endpoint endpoint = readEndpoint(directory);
            if (endpoint != null
                && launchId.equals(endpoint.launchId())
                && ProcessHandle.of(endpoint.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                return endpoint;
            }
            Files.deleteIfExists(directory.resolve(METADATA_FILE));
            return start(directory, launchId);
        }
    }

    private static Endpoint start(Path directory, String launchId) throws IOException {
        String token = UUID.randomUUID().toString();
        List<String> command = daemonCommand();
        command.add("--daemon-server");
        command.add(directory.toString());
        command.add("--daemon-token");
        command.add(token);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve(LOG_FILE).toFile()));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Endpoint endpoint = readEndpoint(directory);
            if (endpoint != null
                && token.equals(endpoint.token())
                && launchId.equals(endpoint.launchId())
                && ping(endpoint)) {
                return endpoint;
            }
            if (!process.isAlive()) {
                throw new IOException("Compiler daemon exited during startup with code " + process.exitValue());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while starting compiler daemon", e);
            }
        }
        process.destroy();
        throw new IOException("Timed out waiting for compiler daemon startup");
    }

    private static boolean ping(Endpoint endpoint) {
        try (Socket socket = connect(endpoint)) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeByte(PING);
            writeString(output, endpoint.token());
            output.flush();
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            requireHeader(input);
            return input.readByte() == PONG;
        } catch (IOException e) {
            return false;
        }
    }

    private static Socket connect(Endpoint endpoint) throws IOException {
        Socket socket = new Socket();
        socket.connect(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()),
            CONNECT_TIMEOUT_MILLIS
        );
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void requireHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("Incompatible compiler daemon response");
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("Invalid compiler daemon string");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Compiler daemon closed while reading a request");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static Endpoint readEndpoint(Path directory) {
        Path metadata = directory.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadata)) {
            return null;
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(metadata)) {
            properties.load(input);
            return new Endpoint(
                Integer.parseInt(properties.getProperty("port")),
                properties.getProperty("token"),
                Long.parseLong(properties.getProperty("pid")),
                properties.getProperty("launchId")
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeEndpoint(Path directory, Endpoint endpoint) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("port", Integer.toString(endpoint.port()));
        properties.setProperty("token", endpoint.token());
        properties.setProperty("pid", Long.toString(endpoint.pid()));
        properties.setProperty("launchId", endpoint.launchId());
        Path temporary = Files.createTempFile(directory, "daemon-", ".properties");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Pyronaut compiler daemon");
            }
            try {
                Files.move(
                    temporary,
                    directory.resolve(METADATA_FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                    temporary,
                    directory.resolve(METADATA_FILE),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void invalidate(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve(METADATA_FILE));
        } catch (IOException ignored) {
            // The startup lock will repair stale metadata on the next attempt.
        }
    }

    private static void deleteOwnedEndpoint(Path directory, String token) {
        Endpoint endpoint = readEndpoint(directory);
        if (endpoint != null && token.equals(endpoint.token())) {
            invalidate(directory);
        }
    }

    private static List<String> daemonCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElseThrow(
            () -> new UnavailableException("Unable to determine the current executable")
        );
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String[] processArguments = nativeImage
            ? new String[0]
            : info.arguments().orElseThrow(
                () -> new UnavailableException("Unable to determine JVM launch arguments")
            );
        return daemonCommand(
            executable,
            processArguments,
            nativeImage,
            System.getProperty(COMMAND_PREFIX)
        );
    }

    static List<String> daemonCommand(String executable,
                                      String[] processArguments,
                                      boolean nativeImage,
                                      String commandPrefix) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (!nativeImage) {
            int mainIndex = mainClassIndex(processArguments);
            if (mainIndex < 0) {
                throw new UnavailableException("Unable to determine the processor main class");
            }
            for (int i = 0; i <= mainIndex; i++) {
                command.add(processArguments[i]);
            }
        }
        if (commandPrefix != null && !commandPrefix.isBlank()) {
            command.add(commandPrefix);
        }
        return command;
    }

    private static int mainClassIndex(String[] arguments) {
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].equals(PyronautProcessorMain.class.getName())
                || arguments[i].equals("io.micronaut.pyronaut.dev.PyronautDevMain")) {
                return i;
            }
        }
        return -1;
    }

    private static String launchId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Integer.toString(VERSION));
            ProcessHandle.current().info().command().ifPresent(value -> updatePath(digest, Path.of(value)));
            String classpath = System.getProperty("java.class.path", "");
            update(digest, classpath);
            for (String entry : classpath.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    updatePath(digest, Path.of(entry));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updatePath(MessageDigest digest, Path path) {
        update(digest, path.toAbsolutePath().normalize().toString());
        try {
            update(digest, Long.toString(Files.size(path)));
            update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
        } catch (IOException ignored) {
            update(digest, "missing");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static long idleTimeoutSeconds() {
        return Math.max(1, Long.getLong(IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT_SECONDS));
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    private static String message(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return "unknown failure";
        }
        return exception.getMessage();
    }

    private static void appendDaemonFailure(Path directory, Exception exception) {
        try {
            Files.writeString(
                directory.resolve(LOG_FILE),
                exception + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // There is nowhere else useful for detached daemon diagnostics.
        }
    }

    static final class UnavailableException extends RuntimeException {
        UnavailableException(String message) {
            super(message);
        }

        UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Endpoint(int port, String token, long pid, String launchId) {
    }

    private static final class FrameOutputStream extends OutputStream {
        private final DataOutputStream output;
        private final byte channel;

        private FrameOutputStream(DataOutputStream output, byte channel) {
            this.output = output;
            this.channel = channel;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            synchronized (output) {
                output.writeByte(channel);
                output.writeInt(length);
                output.write(bytes, offset, length);
                output.flush();
            }
        }
    }

    /**
     * The executor of the daemon: one {@link PythonProcessingSession} per distinct class path, kept
     * between requests so a compilation after the first finds the GraalPy context warm, which is
     * half the cost of a cold compilation. Package-private for the tests of that reuse.
     */
    static final class SessionCompilerExecutor implements PyronautCompilerExecutor, AutoCloseable {
        private final Map<String, PythonProcessingSession> sessions =
            new LinkedHashMap<>(MAX_PROCESSING_SESSIONS, 0.75f, true);

        @Override
        public synchronized void compile(CompileRequest request) {
            String requestSignature = signature(request);
            PythonProcessingSession session = sessions.computeIfAbsent(
                requestSignature,
                ignored -> new PythonProcessingSession()
            );
            evictSessions();
            try {
                new PyronautCompilerExecutor.Default(session).compile(request);
            } catch (RuntimeException | Error e) {
                sessions.remove(requestSignature);
                session.close();
                throw e;
            }
        }

        @Override
        public synchronized void close() {
            sessions.values().forEach(PythonProcessingSession::close);
            sessions.clear();
        }

        /**
         * @return The sessions kept, by the signature of the requests they serve
         */
        synchronized Map<String, PythonProcessingSession> sessions() {
            return Map.copyOf(sessions);
        }

        private void evictSessions() {
            while (sessions.size() > MAX_PROCESSING_SESSIONS) {
                var iterator = sessions.entrySet().iterator();
                Map.Entry<String, PythonProcessingSession> eldest = iterator.next();
                iterator.remove();
                eldest.getValue().close();
            }
        }

        private static String signature(CompileRequest request) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, Boolean.toString(request.incremental()));
                request.annotationProcessorPath().forEach(path -> updatePath(digest, path));
                request.classpath().forEach(path -> updatePath(digest, path));
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
