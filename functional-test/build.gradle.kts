import org.gradle.api.GradleException
import org.gradle.jvm.tasks.Jar
import java.io.FileInputStream
import java.util.Properties

plugins {
    base
}

val fixtureAppDir = layout.projectDirectory.dir("app")
val venvDir = layout.buildDirectory.dir("venv").get()
val venvPython = venvDir.file("bin/python")
val venvConfig = venvDir.file("pyvenv.cfg")
val venvReadyMarker = layout.buildDirectory.file("task-state/venv-ready.txt")
val pytestInstallMarker = layout.buildDirectory.file("task-state/pytest-installed.txt")
val installAppMarker = layout.buildDirectory.file("task-state/install-app-ready.txt")
val fixtureTestResourcesSettingsFile = fixtureAppDir.file(".micronaut/test-resources/test-resources.properties")
val fixtureCacheDir = fixtureAppDir.dir("__pyronaut__")
val fixtureReportsDir = fixtureCacheDir.dir("reports/tests")
val fixtureStagedRepoDir = layout.buildDirectory.dir("fixture-repo").get()
val fixtureM2RepoDir = fixtureCacheDir.dir("m2-repository")
val fixtureSchemasDir = fixtureCacheDir.dir("schemas")
val fixtureResolvedBuildDependencies = fixtureCacheDir.file("resolved-build-dependencies")
val fixtureResolvedRuntimeDependencies = fixtureCacheDir.file("resolved-runtime-dependencies")
val fixtureResolvedTestDependencies = fixtureCacheDir.file("resolved-test-dependencies")
val fixtureResolvedTestResourcesServerDependencies = fixtureCacheDir.file("resolved-test-resources-server-dependencies")
val fixtureConfigValidationCache = fixtureCacheDir.file(".config-validation-cache.properties")
val fixtureConfigValidationReportDir = fixtureCacheDir.dir("reports/config-validation/test")
val fixtureProcessedClassesDir = fixtureCacheDir.dir("classes")
val fixtureProcessedTestClassesDir = fixtureCacheDir.dir("test-classes")
val pytestRequirement = "pytest==9.0.3"

val pyronautInstallExecutable = project(":micronaut-pyronaut-install")
    .layout.buildDirectory.file("install/micronaut-pyronaut-install/bin/pyronaut-install")
val pyronautProcessorExecutable = project(":micronaut-pyronaut-processor")
    .layout.buildDirectory.file("install/micronaut-pyronaut-processor/bin/pyronaut-processor")
val pyronautTestExecutable = project(":micronaut-pyronaut-test")
    .layout.buildDirectory.file("install/micronaut-pyronaut-test/bin/pyronaut-test")
val pyronautValidateConfigExecutable = project(":micronaut-pyronaut-validate-config")
    .layout.buildDirectory.file("install/micronaut-pyronaut-validate-config/bin/pyronaut-validate-config")
val pyronautTestResourcesServerExecutable = project(":micronaut-pyronaut-test-resources-server")
    .layout.buildDirectory.file("install/micronaut-pyronaut-test-resources-server/bin/pyronaut-test-resources-server")

data class FixturePublishedArtifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val jarFile: java.io.File,
    val pomFile: java.io.File,
)

fun defaultFixtureEnv(): Map<String, String> {
    val environment = linkedMapOf<String, String>()
    environment["VIRTUAL_ENV"] = venvDir.asFile.absolutePath
    val pyEnvVersion = System.getenv("PYENV_VERSION").orEmpty()
    if (pyEnvVersion.isNotBlank()) {
        environment["PYENV_VERSION"] = pyEnvVersion
    }
    environment["TESTCONTAINERS_HOST_OVERRIDE"] = "localhost"
    val existingPath = System.getenv("PATH").orEmpty()
    environment["PATH"] = buildString {
        append(venvDir.dir("bin").asFile.absolutePath)
        if (existingPath.isNotBlank()) {
            append(java.io.File.pathSeparatorChar)
            append(existingPath)
        }
    }
    return environment
}

fun org.gradle.process.ExecSpec.configureFixtureEnvironment(extra: Map<String, String> = emptyMap()) {
    workingDir = fixtureAppDir.asFile
    environment(defaultFixtureEnv() + extra)
}

fun readFixtureTestResourcesEnvironment(settingsFile: java.io.File): Map<String, String> {
    if (!settingsFile.isFile) {
        throw GradleException("Missing test resources settings file: ${settingsFile.absolutePath}")
    }
    val properties = Properties()
    FileInputStream(settingsFile).use(properties::load)
    return buildMap {
        properties.getProperty("server.uri")?.takeIf { it.isNotBlank() }?.let {
            put("MICRONAUT_TEST_RESOURCES_SERVER_URI", it)
        }
        properties.getProperty("server.access.token")?.takeIf { it.isNotBlank() }?.let {
            put("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", it)
        }
        properties.getProperty("server.client.read.timeout")?.takeIf { it.isNotBlank() }?.let {
            put("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", it)
        }
    }
}

fun Project.runFixtureCommand(command: List<String>, extraEnvironment: Map<String, String> = emptyMap()) {
    val renderedCommand = formatFixtureCommand(command)
    logger.lifecycle(renderedCommand)
    val processBuilder = ProcessBuilder(command)
        .directory(fixtureAppDir.asFile)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    processBuilder.environment().putAll(defaultFixtureEnv() + extraEnvironment)
    val process = processBuilder.start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed with exit code $exitCode: $renderedCommand")
    }
}

fun formatFixtureCommand(command: List<String>): String {
    if (command.isEmpty()) {
        return ">"
    }
    val rendered = mutableListOf<String>()
    var index = 0
    while (index < command.size) {
        val argument = command[index]
        rendered += argument
        if ((argument == "-cp" || argument == "-classpath" || argument == "--class-path") && index + 1 < command.size) {
            val classpathEntries = command[index + 1]
                .split(java.io.File.pathSeparatorChar)
                .count { it.isNotBlank() }
            rendered += "<classpath:$classpathEntries entries>"
            index += 2
            continue
        }
        index++
    }
    return rendered.joinToString(prefix = "> ")
}

fun readManifestEntries(file: java.io.File): List<String> {
    if (!file.isFile) {
        throw GradleException("Missing classpath manifest: ${file.absolutePath}")
    }
    return file.readLines()
        .map(String::trim)
        .filter(String::isNotEmpty)
}

fun delegateLibEntries(executable: java.io.File): List<String> {
    val libDir = executable.parentFile.parentFile.resolve("lib")
    val jars = libDir.listFiles { file -> file.isFile && file.extension == "jar" }
        ?.sortedBy { it.name }
        .orEmpty()
    if (jars.isEmpty()) {
        throw GradleException("Unable to resolve delegate jars from ${libDir.absolutePath}")
    }
    return jars.map { it.absolutePath }
}

fun resolveJavaExecutable(): String {
    val javaHome = System.getenv("JAVA_HOME").orEmpty()
    if (javaHome.isNotBlank()) {
        val javaBin = java.io.File(javaHome, "bin/java")
        if (javaBin.isFile) {
            return javaBin.absolutePath
        }
    }
    return "java"
}

fun resolveFixturePythonExecutable(): String {
    val pythonHome = System.getenv("PYENV_VERSION").orEmpty()
    if (!pythonHome.startsWith("graalpy")) {
        throw GradleException("functional-test requires a GraalPy interpreter. Current PYENV_VERSION='$pythonHome'")
    }
    return "python"
}

fun Project.fixturePublishedArtifact(projectPath: String): FixturePublishedArtifact {
    val dependencyProject = project(projectPath)
    val jarTask = dependencyProject.tasks.named("jar", Jar::class.java).get()
    return FixturePublishedArtifact(
        groupId = dependencyProject.group.toString(),
        artifactId = dependencyProject.name,
        version = dependencyProject.version.toString(),
        jarFile = jarTask.archiveFile.get().asFile,
        pomFile = dependencyProject.layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile,
    )
}

fun isGraalPyVenv(configFile: java.io.File): Boolean {
    if (!configFile.isFile) {
        return false
    }
    return configFile.readText().contains("graalpy", ignoreCase = true)
}

fun markerContains(file: java.io.File, expected: String): Boolean {
    if (!file.isFile) {
        return false
    }
    return file.readText().trim() == expected
}

fun pytestInstalled(): Boolean {
    val libDir = venvDir.dir("lib").asFile
    if (!libDir.isDirectory) {
        return false
    }
    val pythonLibDir = libDir.listFiles { file -> file.isDirectory && file.name.startsWith("python") }
        ?.sortedBy { it.name }
        ?.lastOrNull()
        ?: return false
    val sitePackagesDir = pythonLibDir.resolve("site-packages")
    if (!sitePackagesDir.isDirectory) {
        return false
    }
    val pytestPackageDir = sitePackagesDir.resolve("pytest")
    val privatePytestPackageDir = sitePackagesDir.resolve("_pytest")
    return pytestPackageDir.isDirectory && privatePytestPackageDir.isDirectory
}

fun installAppOutputsPresent(): Boolean {
    return fixtureResolvedBuildDependencies.asFile.isFile &&
        fixtureResolvedRuntimeDependencies.asFile.isFile &&
        fixtureResolvedTestDependencies.asFile.isFile &&
        fixtureResolvedTestResourcesServerDependencies.asFile.isFile &&
        fixtureM2RepoDir.asFile.isDirectory &&
        fixtureSchemasDir.asFile.isDirectory
}

fun buildPyronautTestCommand(): List<String> {
    val cacheDir = fixtureAppDir.dir("__pyronaut__").asFile
    val classpathEntries = mutableListOf<String>()
    classpathEntries += readManifestEntries(cacheDir.resolve("resolved-test-dependencies"))
    val runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies")
    if (runtimeManifest.isFile) {
        classpathEntries += readManifestEntries(runtimeManifest)
    }
    val buildManifest = cacheDir.resolve("resolved-build-dependencies")
    if (buildManifest.isFile) {
        classpathEntries += readManifestEntries(buildManifest)
    }
    val testClassesDir = cacheDir.resolve("test-classes")
    val classesDir = cacheDir.resolve("classes")
    when {
        testClassesDir.isDirectory -> classpathEntries += testClassesDir.absolutePath
        classesDir.isDirectory -> classpathEntries += classesDir.absolutePath
        else -> throw GradleException("Missing processed classes directory under ${cacheDir.absolutePath}")
    }
    val configDir = fixtureAppDir.dir("config").asFile
    if (configDir.isDirectory) {
        classpathEntries += configDir.absolutePath
    }
    classpathEntries += delegateLibEntries(pyronautTestExecutable.get().asFile)

    return listOf(
        resolveJavaExecutable(),
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        "-Dpyronaut.test.render-failure-output=true",
        "-cp",
        classpathEntries.distinct().joinToString(separator = java.io.File.pathSeparator),
        "io.micronaut.pyronaut.test.PyronautTestMain",
        "--project-dir",
        fixtureAppDir.asFile.absolutePath,
    )
}

val publishFixtureArtifactsToMavenLocal by tasks.registering {
    group = "build setup"
    description = "Stages local Pyronaut fixture modules into the functional-test file repository."
    val logbackProject = project(":micronaut-pyronaut-logback")
    val pytestProject = project(":micronaut-pyronaut-pytest")
    dependsOn(
        logbackProject.tasks.named("jar"),
        logbackProject.tasks.named("generatePomFileForMavenPublication"),
        pytestProject.tasks.named("jar"),
        pytestProject.tasks.named("generatePomFileForMavenPublication"),
    )
    val logbackVersion = logbackProject.version.toString()
    val pytestVersion = pytestProject.version.toString()
    inputs.files(
        logbackProject.layout.buildDirectory.file("libs/${logbackProject.name}-${logbackVersion}.jar"),
        logbackProject.layout.buildDirectory.file("publications/maven/pom-default.xml"),
        pytestProject.layout.buildDirectory.file("libs/${pytestProject.name}-${pytestVersion}.jar"),
        pytestProject.layout.buildDirectory.file("publications/maven/pom-default.xml"),
    )
    outputs.files(
        fixtureStagedRepoDir.file("io/micronaut/pyronaut/${logbackProject.name}/${logbackVersion}/${logbackProject.name}-${logbackVersion}.jar"),
        fixtureStagedRepoDir.file("io/micronaut/pyronaut/${logbackProject.name}/${logbackVersion}/${logbackProject.name}-${logbackVersion}.pom"),
        fixtureStagedRepoDir.file("io/micronaut/pyronaut/${pytestProject.name}/${pytestVersion}/${pytestProject.name}-${pytestVersion}.jar"),
        fixtureStagedRepoDir.file("io/micronaut/pyronaut/${pytestProject.name}/${pytestVersion}/${pytestProject.name}-${pytestVersion}.pom"),
    )
    doLast {
        val stagedArtifacts = listOf(
            project.fixturePublishedArtifact(":micronaut-pyronaut-logback"),
            project.fixturePublishedArtifact(":micronaut-pyronaut-pytest"),
        )
        stagedArtifacts.forEach { artifact ->
            val artifactDir = fixtureStagedRepoDir.asFile
                .resolve(artifact.groupId.replace('.', '/'))
                .resolve(artifact.artifactId)
                .resolve(artifact.version)
            artifactDir.mkdirs()
            artifact.jarFile.copyTo(
                artifactDir.resolve("${artifact.artifactId}-${artifact.version}.jar"),
                overwrite = true
            )
            artifact.pomFile.copyTo(
                artifactDir.resolve("${artifact.artifactId}-${artifact.version}.pom"),
                overwrite = true
            )
        }
    }
}

val installFixtureLaunchers by tasks.registering {
    group = "build setup"
    description = "Builds the local Pyronaut launchers used by the functional-test app."
    dependsOn(
        project(":micronaut-pyronaut-install").tasks.named("installDist"),
        project(":micronaut-pyronaut-processor").tasks.named("installDist"),
        project(":micronaut-pyronaut-test").tasks.named("installDist"),
        project(":micronaut-pyronaut-validate-config").tasks.named("installDist"),
        project(":micronaut-pyronaut-test-resources-server").tasks.named("installDist"),
    )
}

val prepareVenv by tasks.registering {
    group = "build setup"
    description = "Creates an isolated Python virtual environment for the functional-test app."
    inputs.property("venvDir", venvDir.asFile.absolutePath)
    inputs.property("pythonExecutable", providers.provider { resolveFixturePythonExecutable() })
    outputs.file(venvReadyMarker)
    outputs.upToDateWhen {
        venvConfig.asFile.isFile && venvReadyMarker.get().asFile.isFile
    }
    onlyIf {
        !(venvConfig.asFile.isFile && venvReadyMarker.get().asFile.isFile)
    }
    doFirst {
        val configFile = venvConfig.asFile
        if (venvPython.asFile.exists() && !isGraalPyVenv(configFile)) {
            logger.lifecycle("Removing non-GraalPy fixture virtual environment at ${venvDir.asFile.absolutePath}")
            project.delete(venvDir)
        }
        pytestInstallMarker.get().asFile.delete()
    }
    doLast {
        project.runFixtureCommand(
            listOf(resolveFixturePythonExecutable(), "-m", "venv", venvDir.asFile.absolutePath)
        )
        venvReadyMarker.get().asFile.parentFile.mkdirs()
        venvReadyMarker.get().asFile.writeText(resolveFixturePythonExecutable() + "\n")
    }
}

val installPytest by tasks.registering {
    group = "build setup"
    description = "Installs pytest into the functional-test virtual environment."
    dependsOn(prepareVenv)
    inputs.files(venvPython, venvConfig)
    inputs.property("pytestRequirement", pytestRequirement)
    outputs.file(pytestInstallMarker)
    outputs.upToDateWhen {
        venvConfig.asFile.isFile &&
            markerContains(pytestInstallMarker.get().asFile, pytestRequirement) &&
            pytestInstalled()
    }
    onlyIf {
        !(venvConfig.asFile.isFile && markerContains(pytestInstallMarker.get().asFile, pytestRequirement) && pytestInstalled())
    }
    doLast {
        project.runFixtureCommand(
            listOf(venvPython.asFile.absolutePath, "-m", "pip", "install", "--no-compile", pytestRequirement),
            mapOf("PIP_DISABLE_PIP_VERSION_CHECK" to "1")
        )
        pytestInstallMarker.get().asFile.parentFile.mkdirs()
        pytestInstallMarker.get().asFile.writeText(pytestRequirement + "\n")
    }
}

val installApp by tasks.registering {
    group = "verification"
    description = "Resolves functional-test app dependencies into __pyronaut__."
    dependsOn(publishFixtureArtifactsToMavenLocal, installFixtureLaunchers, installPytest)
    inputs.files(
        fixtureAppDir.file("pyproject.toml"),
        fixtureAppDir.file("setup.py"),
        pytestInstallMarker,
        pyronautInstallExecutable,
    )
    outputs.file(installAppMarker)
    outputs.upToDateWhen {
        installAppMarker.get().asFile.isFile && installAppOutputsPresent()
    }
    onlyIf {
        !(installAppMarker.get().asFile.isFile && installAppOutputsPresent())
    }
    doLast {
        project.runFixtureCommand(
            listOf(pyronautInstallExecutable.get().asFile.absolutePath, "--project-dir", fixtureAppDir.asFile.absolutePath)
        )
        installAppMarker.get().asFile.parentFile.mkdirs()
        installAppMarker.get().asFile.writeText("ready\n")
    }
}

val validateConfig by tasks.registering {
    group = "verification"
    description = "Validates the functional-test app configuration with the local validator."
    dependsOn(installApp)
    inputs.files(
        fixtureAppDir.file("pyproject.toml"),
        fixtureAppDir.file("config/application.toml"),
        fixtureResolvedRuntimeDependencies,
        fixtureSchemasDir,
    )
    outputs.files(fixtureConfigValidationCache)
    outputs.dir(fixtureConfigValidationReportDir)
    doLast {
        project.runFixtureCommand(
            listOf(
                pyronautValidateConfigExecutable.get().asFile.absolutePath,
                "--project-dir",
                fixtureAppDir.asFile.absolutePath,
                "--scenario",
                "test",
            )
        )
    }
}

val process by tasks.registering {
    group = "verification"
    description = "Processes the functional-test app sources with the local processor."
    dependsOn(validateConfig)
    inputs.dir(fixtureAppDir.dir("src"))
    inputs.dir(fixtureAppDir.dir("tests"))
    inputs.files(
        fixtureResolvedBuildDependencies,
        fixtureResolvedRuntimeDependencies,
        fixtureResolvedTestDependencies,
    )
    outputs.dirs(
        fixtureProcessedClassesDir,
        fixtureProcessedTestClassesDir,
    )
    doLast {
        project.runFixtureCommand(
            listOf(pyronautProcessorExecutable.get().asFile.absolutePath, "--project-dir", fixtureAppDir.asFile.absolutePath)
        )
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the functional-test app through pyronaut-test using local project launchers."
    dependsOn(process)
    inputs.dir(fixtureAppDir)
    doLast {
        val startCommand = listOf(
            pyronautTestResourcesServerExecutable.get().asFile.absolutePath,
            "start",
            "--project-dir",
            fixtureAppDir.asFile.absolutePath,
        )
        val stopCommand = listOf(
            pyronautTestResourcesServerExecutable.get().asFile.absolutePath,
            "stop",
            "--project-dir",
            fixtureAppDir.asFile.absolutePath,
        )
        runFixtureCommand(startCommand)
        try {
            val testResourcesEnv = readFixtureTestResourcesEnvironment(fixtureTestResourcesSettingsFile.asFile)
            runFixtureCommand(buildPyronautTestCommand(), testResourcesEnv)
        } finally {
            try {
                runFixtureCommand(stopCommand)
            } catch (e: Exception) {
                logger.warn("Unable to stop fixture test resources server cleanly: ${e.message}")
            }
        }
    }
}

tasks.named("check") {
    dependsOn("test")
}

tasks.named("clean") {
    delete(
        fixtureAppDir.dir(".venv"),
        fixtureAppDir.dir("__pyronaut__"),
        fixtureAppDir.dir(".micronaut"),
        fixtureAppDir.dir(".pytest_cache"),
        fixtureAppDir.dir("logs"),
        fixtureAppDir.dir("dist"),
        fixtureAppDir.dir("build"),
        fixtureAppDir.dir("pyronaut_demo.egg-info"),
        fixtureAppDir.file("junit.xml"),
        fixtureAppDir.file("index.html"),
        fixtureAppDir.file(".pyronaut-last-nodeid.txt"),
    )
}
