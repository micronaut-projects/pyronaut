import io.micronaut.pyronaut.gradle.PyronautPgo
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import java.nio.file.Files
import java.util.concurrent.TimeUnit

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

// This distribution has no Java sources of its own; the convention's empty
// Checkstyle input causes Checkstyle to fail before it can do any work.
tasks.named("checkstyleMain") {
    enabled = false
}

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")
val nativeBundleOs = when {
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    else -> System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"), "-")
}
val nativeBundleArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "x86_64", "amd64" -> "amd64"
    else -> System.getProperty("os.arch").lowercase()
}

dependencies {
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))
    api(project(":micronaut-pyronaut-run"))
    // GraalPy only needs this optional support module for legacy private-key formats.
    api(mn.micronaut.context.python) {
        exclude(group = "org.graalvm.python", module = "python-bouncycastle-support")
    }
    api(mn.micronaut.context.python.netty) {
        exclude(group = "org.graalvm.python", module = "python-bouncycastle-support")
    }
    api(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

// The production Python runner must not embed the optional Control Panel.
// Applications may still supply those dependencies explicitly on their own
// runtime classpath.
configurations.named("runtimeClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

// Keep the optional Control Panel out of the native image itself. Excluding
// only runtimeClasspath is insufficient because GraalVM resolves the native
// image classpath separately during the production image build.
configurations.named("nativeImageClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

application {
    mainClass = "io.micronaut.pyronaut.run.PyronautRunMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val nativeBuildProject = project(":micronaut-pyronaut-native-build")
val cremaProjectDirectory = layout.buildDirectory.dir("crema-native-image")
val nativeImageOutputDirectory = layout.buildDirectory.dir("native/nativeCompile")
val isWindows = System.getProperty("os.name")
    .lowercase()
    .contains("windows")
val nativeExecutableSuffix = if (isWindows) ".exe" else ""
val cremaOutputArgument = nativeImageOutputDirectory.map { it.file("pyronaut-run-python") }
val cremaOutput = nativeImageOutputDirectory.map { it.file("pyronaut-run-python$nativeExecutableSuffix") }
val nativeImageCiArgs = providers.gradleProperty("pyronautNativeImageCiArgs")
    .map { it.trim().split(Regex("\\s+")).filter(String::isNotBlank) }
    .orElse(emptyList())
    .get()
val nativeBuildInstallDirectory = nativeBuildProject.layout.buildDirectory.dir(
    "install/micronaut-pyronaut-native-build"
)
val nativeBuildExecutable = nativeBuildProject.layout.buildDirectory.file(
    "install/micronaut-pyronaut-native-build/bin/pyronaut-native-build"
)
val dockerContext = layout.buildDirectory.dir("docker/pyronaut-run-python")
val dockerBundle = dockerContext.map { it.dir("bundle") }
val dockerAvailable = providers.provider<Boolean> {
    if (!System.getProperty("os.name").lowercase().contains("linux")) {
        false
    } else {
        try {
            val process = ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
            try {
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            false
        }
    }
}
val writeNativeClasspathManifest by tasks.registering {
    val outputDirectory = layout.buildDirectory.dir("generated/native-classpaths")
    outputs.dir(outputDirectory)
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("native-provided-classpath.txt").writeText(
            configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                .map { "${it.moduleVersion.id.group}:${it.name}" }
                .distinct()
                .sorted()
                .joinToString("\n", postfix = "\n")
        )
        val runtimeClasspath = configurations.runtimeClasspath.get()
        val compileArtifactsByFile = runtimeClasspath.resolvedConfiguration.resolvedArtifacts
            .associateBy { it.file.canonicalFile }
        val compileEntries = runtimeClasspath.files
            .map { file ->
                val artifact = checkNotNull(compileArtifactsByFile[file.canonicalFile]) {
                    "Missing Maven coordinates for native classpath entry: $file"
                }
                listOf(
                    "maven",
                    artifact.moduleVersion.id.group,
                    artifact.name,
                    artifact.moduleVersion.id.version,
                    artifact.extension,
                    artifact.classifier ?: "",
                    artifact.file.name
                ).joinToString("\t")
            }
            .distinct()
        compileEntries.forEach { entry ->
            val fields = entry.split("\t")
            check(fields.size == 7 && fields[0] == "maven") { "Invalid native classpath descriptor: $entry" }
            check(fields.subList(1, 5).all { it.isNotBlank() && '/' !in it && '\\' !in it }) {
                "Invalid native classpath coordinate: $entry"
            }
            check('/' !in fields[5] && '\\' !in fields[5]) { "Invalid native classpath classifier: $entry" }
            val classifier = fields[5].takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
            val expectedFileName = "${fields[2]}-${fields[3]}$classifier.${fields[4]}"
            check(fields[6] == expectedFileName && !File(fields[6]).isAbsolute && File(fields[6]).name == fields[6]) {
                "Native classpath descriptor contains a host path: $entry"
            }
        }
        directory.resolve("native-compile-classpath.txt").writeText(
            compileEntries.joinToString("\n", postfix = "\n")
        )
    }
}

tasks {
    startScripts {
        applicationName = "pyronaut-run-python"
    }

    val buildCremaNativeImage = register<Exec>("buildCremaNativeImage") {
        group = "build"
        description = "Builds the Python production Crema runtime using PyronautNativeImageBuilder"
        dependsOn(nativeBuildProject.tasks.named("installDist"))
        dependsOn(writeNativeClasspathManifest)
        inputs.files(configurations.runtimeClasspath)
        inputs.property("pyronautNativeImageCiArgs", nativeImageCiArgs)
        inputs.property("pyronautPgoMode", PyronautPgo.mode(project).name)
        inputs.property("pyronautCodeCompression", PyronautPgo.codeCompression(project))
        inputs.files(providers.provider {
            if (PyronautPgo.mode(project) == PyronautPgo.Mode.OPTIMIZE) PyronautPgo.profiles(project, "pyronaut-run-python") else emptyList()
        }).withPropertyName("pyronautPgoProfiles")
        outputs.file(cremaOutput)
        doFirst {
            // The native build runs in its own process, so it takes JAVA_HOME from the daemon's
            // environment rather than from the JVM Gradle is running on. A daemon started from an older
            // shell hands it a different GraalVM, and the failure arrives twelve minutes later as
            //   Could not find required field Lcom/oracle/truffle/runtime/OptimizedDirectCallNode;.callCount
            // which says nothing about the JDK. Pin it to the build JVM and check it up front.
            val buildJdk = File(System.getProperty("java.home"))
            val nativeImageLauncher = File(buildJdk, if (isWindows) "bin/native-image.cmd" else "bin/native-image")
            if (!nativeImageLauncher.exists()) {
                throw GradleException(
                    "Gradle is running on $buildJdk, which has no native-image launcher, so the Crema " +
                        "runtime cannot be built. Point JAVA_HOME (or org.gradle.java.home) at a GraalVM " +
                        "JDK and run ./gradlew --stop first: a running daemon keeps the environment it " +
                        "was started with."
                )
            }
            PyronautPgo.prepareBuild(project, "pyronaut-run-python", buildJdk)
            environment("JAVA_HOME", buildJdk.absolutePath)
            environment(
                "PATH",
                File(buildJdk, "bin").absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
            )
            val projectDirectory = cremaProjectDirectory.get().asFile.toPath()
            Files.createDirectories(projectDirectory.resolve("__pyronaut__/classes"))
            Files.writeString(
                projectDirectory.resolve("__pyronaut__/resolved-runtime-dependencies"),
                configurations.runtimeClasspath.get().files.joinToString(System.lineSeparator()) { it.absolutePath } + System.lineSeparator()
            )
            val nativeBuildArgs = mutableListOf<Any>()
            if (isWindows) {
                nativeBuildArgs.addAll(listOf(
                    File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
                    "-cp", nativeBuildInstallDirectory.get().dir("lib").asFile.resolve("*").absolutePath,
                    "io.micronaut.pyronaut.nativebuild.PyronautNativeBuildMain",
                    "--native-image-executable",
                    File(System.getProperty("java.home"), "bin/native-image.cmd").absolutePath
                ))
            } else {
                nativeBuildArgs.add(nativeBuildExecutable.get().asFile.absolutePath)
            }
            nativeBuildArgs.addAll(listOf(
                "--project-dir", projectDirectory.toString(),
                "--output", cremaOutputArgument.get().asFile.absolutePath,
                "--native-base",
                "--include-python"
            ))
            nativeBuildArgs.addAll(PyronautPgo.nativeBuildArgs(project, "pyronaut-run-python"))
            nativeBuildArgs.addAll(nativeImageCiArgs)
            commandLine(nativeBuildArgs)
        }
        doLast {
            PyronautPgo.verifyAndReport(project, "pyronaut-run-python", cremaOutput.get().asFile)
        }
    }
    val nativeCompileTask = named("nativeCompile") {
        dependsOn(buildCremaNativeImage)
        dependsOn(writeNativeClasspathManifest)
        onlyIf { false }
    }
    val prepareDockerImage = register<Sync>("prepareDockerImage") {
        group = "docker"
        description = "Stages the pyronaut-run-python native executable for Docker image creation"
        dependsOn(buildCremaNativeImage)
        from(cremaOutput) {
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
        from(nativeImageOutputDirectory) {
            include("*.so", "*.dylib", "*.dll", "resources/**")
        }
        into(dockerBundle)
        // The Dockerfile lives beside the synced bundle directory rather than
        // inside it, so declare it explicitly to keep up-to-date checks honest.
        outputs.file(dockerContext.map { it.file("Dockerfile") })
        doLast {
            val dockerfile = dockerContext.get().file("Dockerfile").asFile
            dockerfile.writeText(
                """
                FROM gcr.io/distroless/base
                WORKDIR /opt/pyronaut
                COPY bundle/ /opt/pyronaut/bin/
                """.trimIndent() + "\n"
            )
        }
    }
    register<Exec>("buildDockerImage") {
        group = "docker"
        description = "Builds the pyronaut-run-python native Docker base image"
        dependsOn(dockerAvailable.map { available: Boolean ->
            if (available) listOf(prepareDockerImage) else emptyList<Any>()
        })
        onlyIf {
            if (!dockerAvailable.get()) {
                logger.lifecycle("Skipping pyronaut-run-python Docker image: Linux and a running Docker daemon are required.")
                false
            } else {
                true
            }
        }
        inputs.dir(dockerContext)
        doFirst {
            commandLine(
                "docker", "build",
                "-t", "pyronaut-run-python:${project.version}",
                "-t", "pyronaut-run-python:latest",
                dockerContext.get().asFile.absolutePath
            )
        }
    }
    val nativeBundle = register<Tar>("nativeBundle") {
        group = "distribution"
        description = "Packages the versioned pyronaut-run-python native image and runtime metadata"
        dependsOn(nativeCompileTask)
        dependsOn(writeNativeClasspathManifest)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set("pyronaut-run-python-${nativeBundleOs}-${nativeBundleArch}-${project.version}.tar.gz")
        doFirst {
            PyronautPgo.rejectInstrumentedBundle(project, "pyronaut-run-python")
        }
        compression = Compression.GZIP
        from(cremaOutput) {
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
        // Crema/native-image emits platform libraries beside the executable;
        // include them in the same bundle directory.
        from(nativeImageOutputDirectory) {
            include("*.so", "*.dylib", "*.dll", "resources/**")
        }
        from(layout.buildDirectory.dir("generated/native-classpaths")) {
            include("native-compile-classpath.txt", "native-provided-classpath.txt")
        }
    }
    named("assemble") {
        dependsOn(nativeBundle)
    }
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-run-python native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
    }
}

distributions {
    named("main") {
        contents {
            into("bin") {
                from(writeNativeClasspathManifest)
            }
        }
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-run-python")
            sharedLibrary.set(false)
        }
        all {
            resources.autodetect()
        }
    }
}
