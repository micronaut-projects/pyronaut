package io.micronaut.build.internal.pyronautwheel;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public abstract class BuildWheelTask extends DefaultTask {

    @Inject
    public BuildWheelTask() {
    }

    @Inject
    protected abstract ExecOperations getExecOperations();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPythonExecutable();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourcesDir();

    @Internal
    public abstract DirectoryProperty getWorkDir();

    @OutputDirectory
    public abstract DirectoryProperty getWheelsDir();

    @InputFile
    public abstract RegularFileProperty getCliZipFile();

    @Input
    public abstract ListProperty<String> getExcludes();

    @Input
    public abstract Property<String> getVersion();

    @TaskAction
    public void buildWheel() {
        var python = getPythonExecutable().map(f -> f.getAsFile().getAbsolutePath()).getOrNull();
        if (python == null || python.isBlank()) {
            throw new GradleException("Missing -PpythonExecutable=/path/to/python (or configure buildWheel.pythonExecutable)");
        }

        var workDir = getWorkDir().get().getAsFile().toPath();
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new GradleException("Failed to create wheel work directory: " + workDir, e);
        }

        validateGraalPy(python);

        var venvDir = workDir.getParent().resolve("venv");
        ensureVenvCreated(python, venvDir);
        var venvPython = resolveVenvPython(venvDir).toString();
        ensureWheelToolingInstalled(venvPython, workDir);

        var sources = getSourcesDir().get().getAsFile().toPath();
        var wheelsDir = getWheelsDir().get().getAsFile().toPath();

        var cliZip = getCliZipFile().get().getAsFile().toPath();
        var stagedSourcesRoot = workDir.resolve("src");
        var stagedCliRuntimeDir = stagedSourcesRoot.resolve("pyronaut").resolve("_runtime").resolve("cli");
        var pyprojectToml = workDir.resolve("pyproject.toml");

        try {
            deleteRecursively(workDir);
            Files.createDirectories(workDir);
            Files.createDirectories(wheelsDir);

            try (var stream = Files.list(wheelsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".whl"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            }
            
            stageDirectory(sources, workDir);
            deleteRecursively(stagedCliRuntimeDir);
            Files.createDirectories(stagedCliRuntimeDir);
            extractZip(cliZip, stagedCliRuntimeDir, List.of());

            writePyprojectToml(pyprojectToml, getVersion().get());

        } catch (IOException e) {
            throw new GradleException("Failed to stage wheel project", e);
        } catch (UncheckedIOException e) {
            throw new GradleException("Failed to clean existing wheels directory: " + wheelsDir, e.getCause());
        }

        getLogger().lifecycle("Building wheel using standards-based tooling (python -m build --wheel)");
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(workDir.toFile());
            spec.commandLine(venvPython, "-m", "build", "--wheel");
        });

        var producedDist = workDir.resolve("dist");
        if (!Files.isDirectory(producedDist)) {
            throw new GradleException("Wheel build did not produce dist/ directory in sandbox: " + producedDist);
        }
        try {
            try (var stream = Files.list(producedDist)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".whl"))
                    .forEach(p -> {
                        try {
                            if (!p.getFileName().toString().endsWith("-py3-none-any.whl")) {
                                throw new GradleException("Expected a universal wheel ending with '-py3-none-any.whl' but was: " + p.getFileName());
                            }
                            Files.copy(p, wheelsDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        } catch (IOException e) {
            throw new GradleException("Failed to copy built wheels", e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw new GradleException("Failed to copy built wheels", io);
            }
            throw e;
        }

        verifyWheelDoesNotContainBundledGraalVm(wheelsDir);
    }

    private void verifyWheelDoesNotContainBundledGraalVm(Path wheelsDir) {
        List<Path> wheels;
        try (var stream = Files.list(wheelsDir)) {
            wheels = stream.filter(p -> p.getFileName().toString().endsWith(".whl")).toList();
        } catch (IOException e) {
            throw new GradleException("Failed to list produced wheels", e);
        }
        if (wheels.isEmpty()) {
            throw new GradleException("No wheel produced in output directory: " + wheelsDir);
        }

        for (var wheel : wheels) {
            var hasGraalvm = false;
            try (var is = Files.newInputStream(wheel);
                 var zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    var name = entry.getName();
                    if (name.startsWith("pyronaut/_runtime/graalvm/")) {
                        hasGraalvm = true;
                        break;
                    }
                }
            } catch (IOException e) {
                throw new GradleException("Failed to inspect wheel contents: " + wheel, e);
            }

            if (hasGraalvm) {
                throw new GradleException("Wheel must not contain bundled GraalVM. Found pyronaut/_runtime/graalvm/** in " + wheel.getFileName());
            }
        }
    }

    private void stageDirectory(Path sourceDir, Path targetDir) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                var rel = sourceDir.relativize(dir);
                if (shouldExclude(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetDir.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var rel = sourceDir.relativize(file);
                if (shouldExclude(rel)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.createDirectories(targetDir.resolve(rel).getParent());
                Files.copy(file, targetDir.resolve(rel), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean shouldExclude(Path relativePath) {
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        var first = relativePath.getName(0).toString();
        if (first.equals(".venv") || first.equals("build") || first.equals("dist") || first.equals(".git") || first.equals(".gradle")) {
            return true;
        }
        var name = relativePath.getFileName().toString();
        if (name.equals("__pycache__") || name.equals(".pytest_cache") || name.equals(".mypy_cache") || name.equals(".ruff_cache")) {
            return true;
        }
        return name.endsWith(".egg-info");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writePyprojectToml(Path pyprojectToml, String version) throws IOException {
        var content = """
            [build-system]
            requires = ["hatchling"]
            build-backend = "hatchling.build"

            [project]
            name = "pyronaut"
            version = "%s"
            requires-python = ">=3.9"

            [project.scripts]
            pyronaut = "pyronaut.cli:main"

            [tool.hatch.build]
            only-packages = true

            [tool.hatch.build.targets.wheel]
            platform-tag = ["any"]
            packages = ["pyronaut"]
            package-dir = {"" = "src"}
            include = [
              "src/pyronaut/**",
            ]

            [tool.hatch.build.targets.wheel.force-include]
            "src/pyronaut" = "pyronaut"

            [tool.hatch.build.targets.wheel.shared-data]
            "src/pyronaut/**" = "pyronaut"

            """.formatted(version);
        Files.writeString(pyprojectToml, content);
    }


    private void ensureWheelToolingInstalled(String python, Path workingDir) {
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(workingDir.toFile());
            spec.commandLine(python, "-m", "pip", "install", "-q", "build", "hatchling", "packaging", "wheel");
        });
    }

    private void validateGraalPy(String python) {
        var implCode = "import sys; print(sys.implementation.name)";
        var exeCode = "import sys; print(sys.executable)";

        var impl = execAndCaptureStdout(python, List.of("-c", implCode), "detect python implementation").trim();
        var exe = execAndCaptureStdout(python, List.of("-c", exeCode), "detect python executable").trim();

        if (!"graalpy".equals(impl)) {
            throw new GradleException(
                "The configured Python interpreter must be GraalPy for wheel builds.\n" +
                    "  - Detected sys.implementation.name: '" + impl + "'\n" +
                    "  - Detected sys.executable: '" + exe + "'\n" +
                    "Fix: provide GraalPy via -PpyronautWheelPython=/path/to/graalpy (or ensure 'python3' resolves to GraalPy)."
            );
        }
    }

    private void ensureVenvCreated(String basePython, Path venvDir) {
        var venvPython = resolveVenvPython(venvDir);
        if (Files.isRegularFile(venvPython)) {
            return;
        }

        try {
            Files.createDirectories(venvDir.getParent());
        } catch (IOException e) {
            throw new GradleException("Failed to create venv parent directory: " + venvDir.getParent(), e);
        }

        getLogger().lifecycle("Creating isolated wheel-build venv: {}", venvDir);
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(venvDir.getParent().toFile());
            spec.commandLine(basePython, "-m", "venv", venvDir.toString());
        });

        if (!Files.isRegularFile(venvPython)) {
            throw new GradleException("Expected venv python to exist after creation but was missing: " + venvPython);
        }

        getExecOperations().exec(spec -> {
            spec.setWorkingDir(venvDir.getParent().toFile());
            spec.commandLine(venvPython.toString(), "-m", "pip", "install", "-q", "--upgrade", "pip");
        });
    }

    private static Path resolveVenvPython(Path venvDir) {
        if (File.separatorChar == '\\') {
            return venvDir.resolve("Scripts").resolve("python.exe");
        }
        return venvDir.resolve("bin").resolve("python");
    }

    private String execAndCaptureStdout(String executable, List<String> args, String purpose) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(args, "args");

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var result = getExecOperations().exec(spec -> {
            List<String> cmd = new ArrayList<>(1 + args.size());
            cmd.add(executable);
            cmd.addAll(args);
            spec.commandLine(cmd);
            spec.setStandardOutput(out);
            spec.setErrorOutput(err);
            spec.setIgnoreExitValue(true);
        });
        if (result.getExitValue() != 0) {
            throw new GradleException("Failed to " + purpose + ". stderr: " + err);
        }
        return out.toString();
    }

    private String computePlatformTag(String python) {
        var code = """
            from packaging.tags import sys_tags
            tag = next(iter(sys_tags()))
            print(tag.platform)
            """;
        try {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            var result = getExecOperations().exec(spec -> {
                spec.commandLine(python, "-c", code);
                spec.setStandardOutput(out);
                spec.setErrorOutput(err);
                spec.setIgnoreExitValue(true);
            });
            if (result.getExitValue() != 0) {
                throw new GradleException("Failed to compute platform tag via python. stderr: " + err);
            }
            var value = out.toString().trim();
            if (value.isBlank()) {
                throw new GradleException("Python platform tag was blank");
            }
            if (value.contains("|")) {
                throw new GradleException("Computed python platform tag contains invalid character '|': " + value);
            }
            return value;
        } catch (Exception e) {
            if (e instanceof GradleException) {
                throw e;
            }
            throw new GradleException("Failed to compute python platform tag", e);
        }
    }

    private String computeWheelTag(String python) {
        var code = """
            from packaging.tags import sys_tags
            tag = next(iter(sys_tags()))
            print(str(tag))
            """;
        try {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            var result = getExecOperations().exec(spec -> {
                spec.commandLine(python, "-c", code);
                spec.setStandardOutput(out);
                spec.setErrorOutput(err);
                spec.setIgnoreExitValue(true);
            });
            if (result.getExitValue() != 0) {
                throw new GradleException("Failed to compute wheel tag via python. stderr: " + err);
            }
            var value = out.toString().trim();
            if (value.isBlank()) {
                throw new GradleException("Python wheel tag was blank");
            }
            if (value.contains("|")) {
                throw new GradleException("Computed python wheel tag contains invalid character '|': " + value);
            }
            if (value.split("-", -1).length != 3) {
                throw new GradleException("Computed python wheel tag must be a PEP425 triple (python-abi-platform) but was: " + value);
            }
            return value;
        } catch (Exception e) {
            if (e instanceof GradleException) {
                throw e;
            }
            throw new GradleException("Failed to compute python wheel tag", e);
        }
    }

    private static void assertExecutableExists(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new GradleException(label + " bundle did not contain expected file: " + path);
        }
    }

    private static void assertBinExists(Path dir, String label) {
        if (!Files.isDirectory(dir)) {
            throw new GradleException(label + " bundle did not contain expected directory: " + dir);
        }
    }

    private static void extractZip(Path zipFile, Path targetDir, List<String> executableSuffixes) throws IOException {
        Objects.requireNonNull(executableSuffixes, "executableSuffixes");
        try (var zf = new ZipFile(zipFile.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                var out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("Refusing to write outside targetDir: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (var is = zf.getInputStream(entry);
                     var os = Files.newOutputStream(out)) {
                    is.transferTo(os);
                }
                if (File.separatorChar != '\\' && isExecutableEntry(entry.getName(), executableSuffixes)) {
                    out.toFile().setExecutable(true, false);
                }
            }
        }
    }

    private static boolean isExecutableEntry(String entryName, List<String> executableSuffixes) {
        var normalized = entryName.replace('\\', '/');
        for (var suffix : executableSuffixes) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static void extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        try (var fis = Files.newInputStream(tarGzFile);
             var gis = new GZIPInputStream(fis)) {
            extractTar(gis, targetDir);
        }
    }

    private static void makeStagedCliLaunchersExecutable(Path stagedCliRuntimeDir) throws IOException {
        if (File.separatorChar == '\\') {
            return;
        }
        if (!Files.isDirectory(stagedCliRuntimeDir)) {
            return;
        }
        var posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        var posixPerms = PosixFilePermissions.fromString("rwxr-xr-x");
        try (var stream = Files.walk(stagedCliRuntimeDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("pyronaut"))
                .filter(p -> {
                    var parent = p.getParent();
                    return parent != null && parent.getFileName().toString().equals("bin");
                })
                .forEach(p -> {
                    try {
                        if (posix) {
                            Files.setPosixFilePermissions(p, posixPerms);
                        } else {
                            var ok = p.toFile().setExecutable(true, false);
                            if (!ok) {
                                throw new IOException("Failed to make executable: " + p);
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
    }

    private static void extractTar(InputStream tarStream, Path targetDir) throws IOException {
        var header = new byte[512];
        while (true) {
            readFully(tarStream, header);
            if (isAllZero(header)) {
                return;
            }

            var name = tarString(header, 0, 100);
            if (name.isBlank()) {
                return;
            }
            var size = parseOctal(header, 124, 12);
            int typeFlag = header[156];

            var out = targetDir.resolve(name).normalize();
            if (!out.startsWith(targetDir)) {
                throw new IOException("Refusing to write outside targetDir: " + name);
            }

            if (typeFlag == '5') {
                Files.createDirectories(out);
                skipN(tarStream, size);
            } else if (typeFlag == '0' || typeFlag == 0) {
                Files.createDirectories(out.getParent());
                try (var os = Files.newOutputStream(out)) {
                    copyN(tarStream, os, size);
                }
            } else {
                skipN(tarStream, size);
            }

            var padding = (512 - (size % 512)) % 512;
            if (padding > 0) {
                skipN(tarStream, padding);
            }
        }
    }

    private static void readFully(InputStream is, byte[] buffer) throws IOException {
        var off = 0;
        while (off < buffer.length) {
            var r = is.read(buffer, off, buffer.length - off);
            if (r == -1) {
                throw new IOException("Unexpected EOF while reading tar header");
            }
            off += r;
        }
    }

    private static boolean isAllZero(byte[] bytes) {
        for (var b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        var end = offset;
        var max = offset + length;
        while (end < max && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset).trim();
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        long result = 0;
        var end = offset + length;
        var i = offset;
        while (i < end && (header[i] == 0 || header[i] == ' ')) {
            i++;
        }
        for (; i < end; i++) {
            var b = header[i];
            if (b < '0' || b > '7') {
                break;
            }
            result = (result << 3) + (b - '0');
        }
        return result;
    }

    private static void copyN(InputStream is, OutputStream os, long bytes) throws IOException {
        var buffer = new byte[8192];
        var remaining = bytes;
        while (remaining > 0) {
            var read = is.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected EOF while reading tar entry");
            }
            os.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipN(InputStream is, long bytes) throws IOException {
        var remaining = bytes;
        while (remaining > 0) {
            var skipped = is.skip(remaining);
            if (skipped <= 0) {
                if (is.read() == -1) {
                    throw new IOException("Unexpected EOF while skipping tar entry");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void bundleGraalVm(Path tmpDir, Path targetDir) throws IOException {
        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);

        var artifact = resolveGraalVmArtifact();
        var sha = tmpDir.resolve(artifact.fileName + ".sha256");

        downloadTo(artifact.sha256Uri, sha);
        var expectedSha = parseSha256File(Files.readString(sha));

        var archive = resolveGraalVmCachePath(expectedSha, artifact.fileName);
        Files.createDirectories(archive.getParent());
        if (Files.isRegularFile(archive)) {
            getLogger().lifecycle("Reusing cached GraalVM archive: {}", archive);
        } else {
            downloadToTmpAndMove(artifact.downloadUri, archive);
        }
        verifySha256WithRetry(artifact.downloadUri, archive, artifact.sha256Uri, sha);

        if (!Files.isRegularFile(archive)) {
            throw new IOException("GraalVM archive missing after download/verification: " + archive);
        }

        var extractRoot = tmpDir.resolve("graalvm-extract");
        deleteRecursively(extractRoot);
        Files.createDirectories(extractRoot);

        if (artifact.fileName.endsWith(".zip")) {
            extractZip(archive, extractRoot, List.of());
        } else if (artifact.fileName.endsWith(".tar.gz")) {
            extractTarGz(archive, extractRoot);
        } else {
            throw new IOException("Unsupported GraalVM archive type: " + artifact.fileName);
        }

        var extractedTop = findSingleChildDirectory(extractRoot);
        stageDirectory(extractedTop, targetDir);
    }

    private static Path resolveGraalVmCachePath(String sha256, String fileName) throws IOException {
        if (sha256 == null || sha256.isBlank()) {
            throw new IOException("sha256 must not be blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("fileName must not be blank");
        }
        var xdg = System.getenv("XDG_CACHE_HOME");
        Path cacheHome;
        if (xdg != null && !xdg.isBlank()) {
            cacheHome = Path.of(xdg);
        } else {
            var home = System.getProperty("user.home", "");
            if (home.isBlank()) {
                throw new IOException("Unable to resolve cache dir: neither $XDG_CACHE_HOME nor user.home is set");
            }
            cacheHome = Path.of(home, ".cache");
        }
        return cacheHome
            .resolve("pyronaut-wheel")
            .resolve("graalvm")
            .resolve(sha256)
            .resolve(fileName);
    }

    private static Path findSingleChildDirectory(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            var children = stream.toList();
            if (children.size() != 1 || !Files.isDirectory(children.get(0))) {
                throw new IOException("Expected single top-level directory in " + dir + " but found " + children);
            }
            return children.get(0);
        }
    }

    private static Path findDistributionRoot(Path extractedDir) throws IOException {
        if (Files.isRegularFile(extractedDir.resolve("bin").resolve("pyronaut"))) {
            return extractedDir;
        }
        var root = findSingleChildDirectory(extractedDir);
        assertBinExists(root.resolve("bin"), "Distribution");
        return root;
    }

    private static final class GraalVmArtifact {
        private final String fileName;
        private final URI downloadUri;
        private final URI sha256Uri;

        private GraalVmArtifact(String fileName, URI downloadUri, URI sha256Uri) {
            this.fileName = fileName;
            this.downloadUri = downloadUri;
            this.sha256Uri = sha256Uri;
        }
    }

    private static GraalVmArtifact resolveGraalVmArtifact() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osId;
        if (os.contains("mac") || os.contains("darwin")) {
            osId = "macos";
        } else if (os.contains("win")) {
            osId = "windows";
        } else {
            osId = "linux";
        }

        String archId;
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            archId = "aarch64";
        } else if (arch.equals("x86_64") || arch.equals("amd64")) {
            archId = "x64";
        } else {
            throw new GradleException("Unsupported architecture for GraalVM bundle: " + arch);
        }

        var platform = osId + "-" + archId;
        var archiveExt = osId.equals("windows") ? "zip" : "tar.gz";
        var version = resolveGraalVmVersion();
        var fileName = "graalvm-community-jdk-" + version + "_" + platform + "_bin." + archiveExt;
        var download = URI.create("https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-" + version + "/" + fileName);
        var sha = URI.create(download.toString() + ".sha256");
        return new GraalVmArtifact(fileName, download, sha);
    }

    private static String resolveGraalVmVersion() {
        var latestRelease = URI.create("https://github.com/graalvm/graalvm-ce-builds/releases/latest");
        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var request = HttpRequest.newBuilder(latestRelease)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var finalUri = response.uri();
            var path = finalUri.getPath();
            var idx = path.lastIndexOf("/tag/jdk-");
            if (idx >= 0) {
                var version = path.substring(idx + "/tag/jdk-".length());
                if (!version.isBlank()) {
                    return version;
                }
            }
            throw new IOException("Unable to resolve GraalVM version from redirect: " + finalUri);
        } catch (IOException e) {
            throw new GradleException("Failed to resolve latest GraalVM CE JDK 25 version", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while resolving latest GraalVM CE JDK 25 version", e);
        }
    }

    private void downloadTo(URI uri, Path targetFile) throws IOException {
        if (Files.isRegularFile(targetFile)) {
            getLogger().info("Skipping download; file already exists: {}", targetFile);
            return;
        }
        getLogger().lifecycle("Downloading {} -> {}", uri, targetFile);
        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", "pyronaut-buildwheel")
            .GET()
            .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            var status = response.statusCode();
            if (status != 200) {
                throw new GradleException("Failed to download " + uri + " (HTTP " + status + ")");
            }
            Files.createDirectories(targetFile.getParent());
            var tmp = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp-" + System.nanoTime());
            Files.deleteIfExists(tmp);
            try (var body = response.body();
                 var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                body.transferTo(out);
            }
            try {
                Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, targetFile);
            } catch (FileAlreadyExistsException e) {
                Files.deleteIfExists(tmp);
                if (Files.isRegularFile(targetFile)) {
                    return;
                }
                throw e;
            }
            try {
                Files.setLastModifiedTime(targetFile, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
        }
    }

    private void verifySha256WithRetry(URI archiveUri, Path archive, URI shaUri, Path shaFile) throws IOException {
        try {
            verifySha256(archive, shaFile);
        } catch (GradleException e) {
            getLogger().warn("SHA-256 verification failed for {}. Deleting and re-downloading once.", archive.getFileName());
            Files.deleteIfExists(archive);
            Files.deleteIfExists(shaFile);
            downloadTo(shaUri, shaFile);
            downloadToTmpAndMove(archiveUri, archive);
            verifySha256(archive, shaFile);
        }
    }

    private void downloadToTmpAndMove(URI uri, Path targetFile) throws IOException {
        if (Files.isRegularFile(targetFile)) {
            getLogger().info("Skipping download; file already exists: {}", targetFile);
            return;
        }
        Files.createDirectories(targetFile.getParent());
        var tmp = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp-" + System.nanoTime());
        Files.deleteIfExists(tmp);
        downloadTo(uri, tmp);
        try {
            Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, targetFile);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(tmp);
            if (Files.isRegularFile(targetFile)) {
                return;
            }
            throw e;
        }
    }

    private static void verifySha256(Path file, Path shaFile) throws IOException {
        var expected = parseSha256File(Files.readString(shaFile));

        var actual = sha256Hex(file);
        if (!Objects.equals(expected, actual)) {
            throw new GradleException("SHA-256 mismatch for " + file.getFileName() + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static String parseSha256File(String contents) throws IOException {
        if (contents == null) {
            throw new IOException("Empty SHA-256 file contents");
        }
        var s = contents.strip();
        if (s.isEmpty()) {
            throw new IOException("Empty SHA-256 file contents");
        }
        var i = 0;
        while (i < s.length()) {
            var c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                i++;
            } else {
                break;
            }
        }
        var hex = s.substring(0, i).toLowerCase(Locale.ROOT);
        if (hex.length() != 64) {
            throw new IOException("Invalid SHA-256 value in .sha256 file: '" + hex + "'");
        }
        return hex;
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var is = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        var hash = digest.digest();
        var sb = new StringBuilder(hash.length * 2);
        for (var b : hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
