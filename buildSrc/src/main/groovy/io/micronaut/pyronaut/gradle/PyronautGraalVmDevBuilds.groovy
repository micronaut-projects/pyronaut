package io.micronaut.pyronaut.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.UserDefinedFileAttributeView

final class PyronautGraalVmDevBuilds {
    static final String DEV_BUILD_TAG_PROPERTY = "pyronautGraalVmDevTag"
    static final String PROVISION_TASK_NAME = "provisionPyronautGraalVmDevBuild"

    private PyronautGraalVmDevBuilds() {
    }

    static TaskProvider<Task> registerProvisionTask(Project rootProject, Provider<String> tagProvider) {
        Task existing = rootProject.tasks.findByName(PROVISION_TASK_NAME)
        if (existing != null) {
            return rootProject.tasks.named(PROVISION_TASK_NAME)
        }

        rootProject.tasks.register(PROVISION_TASK_NAME) { Task task ->
            task.group = "build setup"
            task.description = "Downloads and installs the configured GraalVM CE dev build for Pyronaut native-image tasks"
            task.inputs.property("graalVmDevTag", tagProvider.map { normalizeDevBuildTag(it) })
            task.outputs.dir(tagProvider.map { installRoot(rootProject, it) })
            task.doLast {
                provision(rootProject, tagProvider.get())
            }
        }
    }

    static Provider<File> javaHomeProvider(Project project, Provider<String> tagProvider) {
        tagProvider.map { String tag ->
            File javaHome = findJavaHome(installRoot(project.rootProject, tag))
            if (javaHome == null) {
                throw new GradleException(
                    "GraalVM CE dev build '${normalizeDevBuildTag(tag)}' has not been provisioned. " +
                        "Make the consuming task depend on :${PROVISION_TASK_NAME}."
                )
            }
            javaHome
        }
    }

    static Provider<JavaLauncher> javaLauncherProvider(Project project, Provider<String> tagProvider) {
        tagProvider.map { String tag ->
            String normalizedTag = normalizeDevBuildTag(tag)
            File javaHome = findJavaHome(installRoot(project.rootProject, tag))
            if (javaHome == null) {
                throw new GradleException(
                    "GraalVM CE dev build '${normalizedTag}' has not been provisioned. " +
                        "Make the consuming task depend on :${PROVISION_TASK_NAME}."
                )
            }
            ensureNativeImageLauncherIsUsable(javaHome)
            ensureMacOsGatekeeperDoesNotBlock(javaHome)
            javaLauncher(project, javaHome, javaVersionFromTag(normalizedTag), normalizedTag)
        }
    }

    static File provision(Project rootProject, String configuredTag) {
        String tag = normalizeDevBuildTag(configuredTag)
        File installRoot = installRoot(rootProject, tag)
        File markerFile = new File(installRoot, ".pyronaut-graalvm-dev-build")
        File existingJavaHome = findJavaHome(installRoot)
        if (existingJavaHome != null && markerFile.isFile() && markerFile.text.trim() == tag) {
            ensureNativeImageLauncherIsUsable(existingJavaHome)
            ensureMacOsGatekeeperDoesNotBlock(existingJavaHome)
            return existingJavaHome
        }

        File archiveFile = archiveFile(rootProject, tag)
        if (!archiveFile.isFile()) {
            download(downloadUrl(tag), archiveFile)
        }

        deleteRecursively(installRoot.toPath())
        installRoot.mkdirs()
        if (archiveFile.name.endsWith(".zip")) {
            rootProject.copy {
                from(rootProject.zipTree(archiveFile))
                into(installRoot)
            }
        } else {
            rootProject.copy {
                from(rootProject.tarTree(rootProject.resources.gzip(archiveFile)))
                into(installRoot)
            }
        }

        File javaHome = findJavaHome(installRoot)
        if (javaHome == null) {
            throw new GradleException("Downloaded GraalVM CE dev build '${tag}' did not contain a bin/java launcher")
        }
        ensureNativeImageLauncherIsUsable(javaHome)
        ensureMacOsGatekeeperDoesNotBlock(javaHome)
        markerFile.text = tag + System.lineSeparator()
        javaHome
    }

    static String normalizeDevBuildTag(String configuredTag) {
        if (configuredTag == null || configuredTag.isBlank()) {
            throw new GradleException("${DEV_BUILD_TAG_PROPERTY} must not be blank")
        }
        String tag = configuredTag.trim()
        tag.startsWith("jdk-") ? tag.substring(4) : tag
    }

    static int javaVersionFromTag(String tag) {
        def matcher = normalizeDevBuildTag(tag) =~ /(\d+)/
        if (!matcher.find()) {
            throw new GradleException("Unable to extract a Java language version from ${DEV_BUILD_TAG_PROPERTY}='${tag}'")
        }
        Integer.parseInt(matcher.group(1))
    }

    static File installRoot(Project rootProject, String tag) {
        new File(rootProject.rootDir, ".gradle/pyronaut/graalvm-dev-builds/${normalizeDevBuildTag(tag)}")
    }

    private static File archiveFile(Project rootProject, String tag) {
        new File(rootProject.rootDir, ".gradle/pyronaut/downloads/${normalizeDevBuildTag(tag)}/${assetNameForCurrentMachine()}")
    }

    private static String downloadUrl(String tag) {
        String normalizedTag = normalizeDevBuildTag(tag)
        "https://github.com/graalvm/graalvm-ce-dev-builds/releases/download/${normalizedTag}/${assetNameForCurrentMachine()}"
    }

    private static String assetNameForCurrentMachine() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)

        String os
        if (osName.contains("mac")) {
            os = "darwin"
        } else if (osName.contains("linux")) {
            os = "linux"
        } else if (osName.contains("win")) {
            os = "windows"
        } else {
            throw new GradleException("Unsupported operating system for GraalVM CE dev toolchain provisioning: ${osName}")
        }

        String arch
        if (archName == "aarch64" || archName == "arm64") {
            arch = "aarch64"
        } else if (archName == "x86_64" || archName == "amd64") {
            arch = "amd64"
        } else {
            throw new GradleException("Unsupported architecture for GraalVM CE dev toolchain provisioning: ${archName}")
        }

        String extension = os == "windows" ? "zip" : "tar.gz"
        "graalvm-community-dev-${os}-${arch}.${extension}"
    }

    private static void download(String url, File destination) {
        destination.parentFile.mkdirs()
        File temporaryFile = new File(destination.parentFile, destination.name + ".tmp")
        temporaryFile.delete()
        URI.create(url).toURL().withInputStream { input ->
            Files.copy(input, temporaryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(temporaryFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private static File findJavaHome(File root) {
        if (!root.isDirectory()) {
            return null
        }
        File directJava = new File(root, "bin/java")
        if (directJava.isFile()) {
            return root
        }
        try (def paths = Files.find(root.toPath(), 6, { Path path, attributes ->
            attributes.isRegularFile() && path.fileName.toString() == "java" && path.parent?.fileName?.toString() == "bin"
        })) {
            Path java = paths.findFirst().orElse(null)
            java == null ? null : java.parent.parent.toFile()
        }
    }

    static void ensureNativeImageLauncherIsUsable(File javaHome) {
        if (javaHome == null) {
            return
        }

        File launcher = new File(javaHome, "bin/native-image")
        File realLauncher = new File(javaHome, "lib/svm/bin/native-image")
        if (!realLauncher.isFile() || !realLauncher.canExecute()) {
            return
        }
        if (launcher.isFile() && launcher.canExecute() && launcher.length() > 0) {
            return
        }

        launcher.parentFile.mkdirs()
        Files.deleteIfExists(launcher.toPath())
        try {
            Files.createSymbolicLink(launcher.toPath(), realLauncher.toPath())
        } catch (UnsupportedOperationException | IOException ignored) {
            Files.copy(realLauncher.toPath(), launcher.toPath(), StandardCopyOption.REPLACE_EXISTING)
            launcher.setExecutable(true, false)
        }
    }

    static void ensureMacOsGatekeeperDoesNotBlock(File javaHome) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
        if (!osName.contains("mac") || javaHome == null) {
            return
        }

        boolean isQuarantined = [javaHome.toPath(), javaHome.toPath().resolve("bin/java")].any { Path candidate ->
            try {
                def view = Files.getFileAttributeView(candidate, UserDefinedFileAttributeView)
                view != null && view.list().contains("com.apple.quarantine")
            } catch (Exception ignored) {
                false
            }
        }

        if (isQuarantined) {
            throw new GradleException(
                """The provisioned GraalVM dev build at '${javaHome.absolutePath}' is blocked by macOS Gatekeeper.

Run:
xattr -r -d com.apple.quarantine '${javaHome.absolutePath}'

If permissions prevent that, rerun the same command with sudo."""
            )
        }
    }

    private static JavaLauncher javaLauncher(Project project, File javaHome, int javaVersion, String tag) {
        RegularFile executablePath = project.layout.file(project.providers.provider { new File(javaHome, "bin/java") }).get()
        Directory installationPath = project.layout.dir(project.providers.provider { javaHome }).get()
        JavaInstallationMetadata metadata = [
            getLanguageVersion: { JavaLanguageVersion.of(javaVersion) },
            getJavaRuntimeVersion: { tag },
            getJvmVersion: { tag },
            getVendor: { "GraalVM Community" },
            getInstallationPath: { installationPath },
            isCurrentJvm: { false }
        ] as JavaInstallationMetadata
        [
            getMetadata: { metadata },
            getExecutablePath: { executablePath }
        ] as JavaLauncher
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return
        }
        try (def paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
