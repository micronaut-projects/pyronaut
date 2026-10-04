// Copyright 2017-2026 original authors

import io.micronaut.pyronaut.gradle.PyronautPgo

// PGO training workload for the pyronaut-dev, pyronaut-run and pyronaut-run-python native images.
// Nothing here is published. The train* tasks run against the instrumented images from
// -Ppyronaut.pgo=instrument builds and deliberately do not depend on nativeCompile: running
// training without that property must not rebuild the image it is about to train.
//
//   ./gradlew -Ppyronaut.pgo=instrument :micronaut-pyronaut-run:nativeCompile
//   ./gradlew :micronaut-pgo-training:trainPyronautRun
//   ./gradlew -Ppyronaut.pgo=optimize :micronaut-pyronaut-run:nativeBundle
//
// The trainJvm* tasks run the same scenarios on the JVM launchers without collecting profiles,
// which checks the workload without building any native image.

plugins {
    base
}

val jvmTools = listOf(
    "dev", "install", "processor", "test", "validate-config", "native-build", "jar-build",
    "run", "run-python", "test-resources-server",
)
val fixtureRepository = project(":micronaut-functional-test").layout.buildDirectory.dir("fixture-repo")
val graalPy = providers.gradleProperty("pyronaut.pgo.graalpy")
    .orElse(providers.gradleProperty("pyronautPyenvVersion").map {
        "${System.getProperty("user.home")}/.pyenv/versions/$it/bin/graalpy"
    })
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val nativeExecutableSuffix = if (isWindows) ".exe" else ""
val launcherSuffix = if (isWindows) ".bat" else ""
val trainingScale = providers.gradleProperty("pyronaut.pgo.trainingScale").orElse("1.0")
// Comma-separated scenarios to leave out, for local debugging only: a release profile must cover every scenario.
val trainingSkip = providers.gradleProperty("pyronaut.pgo.trainingSkip").orElse("")
val micronautVersionArguments = providers.provider {
    listOf(
        "--micronaut-core-version", providers.gradleProperty("pyronaut.micronaut.core.version").get(),
        "--micronaut-platform-version", providers.gradleProperty("pyronaut.micronaut.platform.version").get(),
        "--micronaut-serde-version", libs.versions.micronaut.serde.get(),
        "--micronaut-validation-version", libs.versions.micronaut.validation.get(),
    )
}

fun toolProject(tool: String) = project(":micronaut-pyronaut-$tool")

fun toolExecutable(tool: String): File = toolProject(tool).layout.buildDirectory
    .file("install/micronaut-pyronaut-$tool/bin/pyronaut-$tool$launcherSuffix").get().asFile

val imageManifestTasks = mapOf(
    "pyronaut-dev" to ":micronaut-pyronaut-dev:writeNativeClasspathManifests",
    "pyronaut-run" to ":micronaut-pyronaut-run:writeNativeClasspathManifest",
    "pyronaut-run-python" to ":micronaut-pyronaut-run-python:writeNativeClasspathManifest",
)

fun registerTraining(taskName: String, image: String, jvm: Boolean) = tasks.register<Exec>(taskName) {
    group = "pgo"
    description = if (jvm) {
        "Runs the $image PGO training scenarios on the JVM launchers, without collecting profiles"
    } else {
        "Trains the instrumented $image native image and writes its PGO profiles"
    }
    val imageProject = project(":micronaut-$image")
    jvmTools.forEach { dependsOn(":micronaut-pyronaut-$it:installDist") }
    dependsOn(":micronaut-functional-test:publishFixtureArtifactsToMavenLocal")
    dependsOn(imageManifestTasks.getValue(image))
    // Training always runs: its output is profiles for whichever image was just built.
    outputs.upToDateWhen { false }
    val workDirectory = layout.buildDirectory.dir(if (jvm) "jvm-training/$image" else "training/$image")
    doFirst {
        val javaHome = File(System.getProperty("java.home"))
        val arguments = mutableListOf(
            graalPy.get(),
            layout.projectDirectory.file("src/main/python/pgo_train.py").asFile.absolutePath,
            "--image", image,
            "--dev-install-dir", toolExecutable("dev").parentFile.parentFile.absolutePath,
            "--profiles-dir", PyronautPgo.profilesDirectory(project, image).absolutePath,
            "--work-dir", workDirectory.get().asFile.absolutePath,
            "--apps-dir", layout.projectDirectory.dir("apps").asFile.absolutePath,
            "--repository", fixtureRepository.get().asFile.absolutePath,
            "--cli-source", project(":micronaut-pyronaut").layout.projectDirectory.dir("src/main/python").asFile.absolutePath,
            "--graalpy", graalPy.get(),
            "--java-home", javaHome.absolutePath,
            "--scale", trainingScale.get(),
        )
        arguments += micronautVersionArguments.get()
        jvmTools.forEach { arguments += listOf("--tool", "$it=${toolExecutable(it).absolutePath}") }
        trainingSkip.get().split(",").map(String::trim).filter(String::isNotEmpty).forEach { arguments += listOf("--skip", it) }
        if (jvm) {
            arguments += "--jvm"
        } else {
            arguments += listOf(
                "--executable", imageProject.layout.buildDirectory.file("native/nativeCompile/$image$nativeExecutableSuffix").get().asFile.absolutePath,
                "--instrumented-marker", File(PyronautPgo.imageDirectory(project, image), "instrumented-executable.txt").absolutePath,
                "--manifests-dir", imageProject.layout.buildDirectory.dir("generated/native-classpaths").get().asFile.absolutePath,
            )
        }
        commandLine(arguments)
    }
}

val images = mapOf(
    "PyronautDev" to "pyronaut-dev",
    "PyronautRun" to "pyronaut-run",
    "PyronautRunPython" to "pyronaut-run-python",
)
val trainingTasks = images.map { (suffix, image) -> registerTraining("train$suffix", image, jvm = false) }
val jvmTrainingTasks = images.map { (suffix, image) -> registerTraining("trainJvm$suffix", image, jvm = true) }

// Compares a baseline executable (-Ppyronaut.pgo.baseline=/path/to/image) with the image in the
// image project's build directory, for example a -Ppyronaut.pgo=optimize build.
fun registerBenchmark(taskName: String, image: String) = tasks.register<Exec>(taskName) {
    group = "pgo"
    description = "Benchmarks the built $image against -Ppyronaut.pgo.baseline"
    val imageProject = project(":micronaut-$image")
    jvmTools.forEach { dependsOn(":micronaut-pyronaut-$it:installDist") }
    dependsOn(":micronaut-functional-test:publishFixtureArtifactsToMavenLocal", imageManifestTasks.getValue(image))
    outputs.upToDateWhen { false }
    doFirst {
        val baseline = providers.gradleProperty("pyronaut.pgo.baseline").orNull
            ?: throw GradleException("Set -Ppyronaut.pgo.baseline=/path/to/$image built without PGO")
        val built = imageProject.layout.buildDirectory.file("native/nativeCompile/$image$nativeExecutableSuffix").get().asFile
        val arguments = mutableListOf(
            graalPy.get(),
            layout.projectDirectory.file("src/main/python/pgo_benchmark.py").asFile.absolutePath,
            "--image", image,
            "--candidate", "baseline=$baseline",
            "--candidate", "${providers.gradleProperty("pyronaut.pgo.candidateLabel").getOrElse("pgo")}=${built.absolutePath}",
            "--manifests-dir", imageProject.layout.buildDirectory.dir("generated/native-classpaths").get().asFile.absolutePath,
            "--dev-install-dir", toolExecutable("dev").parentFile.parentFile.absolutePath,
            "--work-dir", layout.buildDirectory.dir("benchmark/$image").get().asFile.absolutePath,
            "--apps-dir", layout.projectDirectory.dir("apps").asFile.absolutePath,
            "--repository", fixtureRepository.get().asFile.absolutePath,
            "--cli-source", project(":micronaut-pyronaut").layout.projectDirectory.dir("src/main/python").asFile.absolutePath,
            "--graalpy", graalPy.get(),
            "--java-home", System.getProperty("java.home"),
        )
        arguments += micronautVersionArguments.get()
        jvmTools.forEach { arguments += listOf("--tool", "$it=${toolExecutable(it).absolutePath}") }
        commandLine(arguments)
    }
}

images.forEach { (suffix, image) -> registerBenchmark("benchmark$suffix", image) }

tasks.register("train") {
    group = "pgo"
    description = "Trains every instrumented native image"
    dependsOn(trainingTasks)
}

tasks.register("trainJvm") {
    group = "pgo"
    description = "Runs every training workload on the JVM launchers"
    dependsOn(jvmTrainingTasks)
}

val testTraining by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the PGO training driver regression tests"
    commandLine("python3", "-m", "unittest", "discover", "-s", "src/test/python", "-p", "test_*.py")
}

tasks.named("check") {
    dependsOn(testTraining)
}
