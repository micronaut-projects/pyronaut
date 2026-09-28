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
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))

    api(mn.micronaut.context)
    api(mnPicocli.picocli)
    api(mnOpenapi.micronaut.openapi.annotations)
    api(libs.micronaut.data.model)
    api(libs.micronaut.data.runtime)
    api(libs.micronaut.data.connection)
    api(libs.micronaut.jdbc)
    api(libs.micronaut.cache.core)
    api(libs.micronaut.sourcegen.annotations)
    api(libs.micronaut.reactor)
    api("io.micronaut.views:micronaut-views-core")
    api("io.micronaut:micronaut-management")
    api(mn.micronaut.http.server)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.messaging)
    api(mn.micronaut.websocket)
    api(mn.micronaut.runtime)
    api(mn.micronaut.retry)
    api(libs.micronaut.toml)
    api(mnValidation.micronaut.validation)
    api("io.micronaut:micronaut-discovery-core")
    api(mn.micronaut.json.core)
    api(mnSerde.micronaut.serde.jackson)
    api("io.micronaut.serde:micronaut-serde-api")
    api(project(":micronaut-pyronaut-logback"))
    implementation(project(":micronaut-pyronaut-config-model"))
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-processor"))
    testAnnotationProcessor(mn.micronaut.inject.java)
    testRuntimeOnly(mn.micronaut.http.server)
    testRuntimeOnly(mn.micronaut.http.server.netty)
    testRuntimeOnly("io.micronaut:micronaut-discovery-core")
    testRuntimeOnly(mn.micronaut.json.core)
    testRuntimeOnly(mn.micronaut.jackson.databind)
}

// Control Panel is an optional application feature; it must not be embedded
// in the production runner native image. Explicit project dependencies remain
// on the application's own runtime classpath.
configurations.named("runtimeClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

configurations.named("nativeImageClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

application {
    mainClass = "io.micronaut.pyronaut.run.PyronautRunMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val nativeBuildProject = project(":micronaut-pyronaut-native-build")
val pythonRunProject = project(":micronaut-pyronaut-run-python")
val cremaProjectDirectory = layout.buildDirectory.dir("crema-native-image")
val isWindows = System.getProperty("os.name")
    .lowercase()
    .contains("windows")
val nativeExecutableSuffix = if (isWindows) ".exe" else ""
val cremaOutputArgument = layout.buildDirectory.file("native/nativeCompile/pyronaut-run")
val cremaOutput = layout.buildDirectory.file("native/nativeCompile/pyronaut-run$nativeExecutableSuffix")
val pythonCremaOutput = pythonRunProject.layout.buildDirectory.file(
    "native/nativeCompile/pyronaut-run-python$nativeExecutableSuffix"
)
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
val dockerContext = layout.buildDirectory.dir("docker/pyronaut-run")
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
        applicationName = "pyronaut-run"
    }

    val buildCremaNativeImage = register<Exec>("buildCremaNativeImage") {
        group = "build"
        description = "Builds the production Crema runtime using PyronautNativeImageBuilder"
        dependsOn(nativeBuildProject.tasks.named("installDist"))
        dependsOn(writeNativeClasspathManifest)
        inputs.files(configurations.runtimeClasspath)
        inputs.property("pyronautNativeImageCiArgs", nativeImageCiArgs)
        inputs.property("pyronautPgoMode", PyronautPgo.mode(project).name)
        inputs.property(
            "pyronautPgoPreserveArgs",
            if (PyronautPgo.mode(project) == PyronautPgo.Mode.OFF) emptyList<String>() else PyronautPgo.graalvmPgoPreserveArgs()
        )
        inputs.property("pyronautCodeCompression", PyronautPgo.codeCompression(project, "pyronaut-run"))
        inputs.property("pyronautPgoSampling", PyronautPgo.sampling(project))
        inputs.files(providers.provider {
            if (PyronautPgo.mode(project) == PyronautPgo.Mode.OPTIMIZE) PyronautPgo.profiles(project, "pyronaut-run") else emptyList()
        }).withPropertyName("pyronautPgoProfiles")
        // The image is produced by PyronautNativeImageBuilder, so a change to the builder (its preserve
        // list, for one) must invalidate the image even when the runtime classpath is unchanged.
        inputs.files(nativeBuildInstallDirectory.map { it.dir("lib").asFileTree })
            .withPropertyName("nativeBuildClasspath")
            .withNormalizer(ClasspathNormalizer::class)
        inputs.files(nativeBuildInstallDirectory.map { it.dir("bin").asFileTree })
            .withPropertyName("nativeBuildLaunchers")
            .withPathSensitivity(PathSensitivity.RELATIVE)
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
            PyronautPgo.prepareBuild(project, "pyronaut-run", buildJdk)
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
                "--native-base"
            ))
            nativeBuildArgs.addAll(PyronautPgo.nativeBuildArgs(project, "pyronaut-run"))
            nativeBuildArgs.addAll(nativeImageCiArgs)
            commandLine(nativeBuildArgs)
        }
        doLast {
            PyronautPgo.verifyAndReport(project, "pyronaut-run", cremaOutput.get().asFile)
        }
    }
    val nativeCompileTask = named("nativeCompile") {
        dependsOn(buildCremaNativeImage)
        dependsOn(writeNativeClasspathManifest)
        onlyIf { false }
    }
    val prepareDockerImage = register<Sync>("prepareDockerImage") {
        group = "docker"
        description = "Stages the pyronaut-run native executable for Docker image creation"
        dependsOn(buildCremaNativeImage)
        from(cremaOutput)
        into(dockerContext)
        rename { "pyronaut-run" }
        doLast {
            val dockerfile = dockerContext.get().file("Dockerfile").asFile
            dockerfile.writeText(
                """
                FROM gcr.io/distroless/base
                WORKDIR /opt/pyronaut
                COPY pyronaut-run /opt/pyronaut/bin/pyronaut-run
                """.trimIndent() + "\n"
            )
        }
    }
    register<Exec>("buildDockerImage") {
        group = "docker"
        description = "Builds the pyronaut-run native Docker base image"
        dependsOn(dockerAvailable.map { available: Boolean ->
            if (available) listOf(prepareDockerImage) else emptyList<Any>()
        })
        onlyIf {
            if (!dockerAvailable.get()) {
                logger.lifecycle("Skipping pyronaut-run Docker image: Linux and a running Docker daemon are required.")
                false
            } else {
                true
            }
        }
        inputs.dir(dockerContext)
        doFirst {
            commandLine(
                "docker", "build",
                "-t", "pyronaut-run:${project.version}",
                "-t", "pyronaut-run:latest",
                dockerContext.get().asFile.absolutePath
            )
        }
    }
    val nativeBundle = register<Tar>("nativeBundle") {
        group = "distribution"
        description = "Packages the versioned pyronaut-run native image and runtime metadata"
        dependsOn(nativeCompileTask)
        dependsOn(writeNativeClasspathManifest)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set("pyronaut-run-${nativeBundleOs}-${nativeBundleArch}-${project.version}.tar.gz")
        doFirst {
            PyronautPgo.rejectInstrumentedBundle(project, "pyronaut-run")
        }
        compression = Compression.GZIP
        from(cremaOutput)
        // Crema/native-image emits platform libraries beside the executable;
        // include them in the same bundle directory.
        from(layout.buildDirectory.dir("native/nativeCompile")) {
            include("*.so", "*.dylib")
        }
        from(layout.buildDirectory.dir("native/nativeCompile/resources"))
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
        description = "Runs the Python controller smoke test against pyronaut-run-python"
        dependsOn(pythonRunProject.tasks.named("buildCremaNativeImage"))
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.run.native.binary", pythonCremaOutput.get().asFile.absolutePath)
        }
        include("**/PyronautRunNativeSmokeTest.class")
    }

    register<Test>("javaNativeSmokeTest") {
        group = "verification"
        description = "Runs the Java controller smoke test against pyronaut-run"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.run.native.binary", cremaOutput.get().asFile.absolutePath)
        }
        include("**/PyronautRunJavaNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
        dependsOn(named("javaNativeSmokeTest"))
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
            imageName.set("pyronaut-run")
            sharedLibrary.set(false)
        }
        all {
            resources.autodetect()
        }
    }
}
