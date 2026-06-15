import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.jvm.tasks.Jar
import java.io.FileInputStream
import java.security.MessageDigest
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
val fixtureIdeStubsDir = fixtureCacheDir.dir("ide-stubs")
val fixtureReportsDir = fixtureCacheDir.dir("reports/tests")
val fixtureStagedRepoDir = layout.buildDirectory.dir("fixture-repo").get()
val fixtureRepositoryId = "repo-0"
val fixtureM2RepoDir = fixtureCacheDir.dir("m2-repository")
val fixtureSchemasDir = fixtureCacheDir.dir("schemas")
val fixtureResolvedEditorArtifacts = fixtureCacheDir.file("resolved-editor-artifacts.json")
val fixtureResolvedBuildDependencies = fixtureCacheDir.file("resolved-build-dependencies")
val fixtureResolvedRuntimeDependencies = fixtureCacheDir.file("resolved-runtime-dependencies")
val fixtureResolvedTestDependencies = fixtureCacheDir.file("resolved-test-dependencies")
val fixtureResolvedTestResourcesServerDependencies = fixtureCacheDir.file("resolved-test-resources-server-dependencies")
val fixtureConfigValidationCache = fixtureCacheDir.file(".config-validation-cache.properties")
val fixtureConfigValidationReportDir = fixtureCacheDir.dir("reports/config-validation/test")
val fixtureProcessedClassesDir = fixtureCacheDir.dir("classes")
val fixtureProcessedTestClassesDir = fixtureCacheDir.dir("test-classes")
val pytestRequirement = "pytest==9.0.3"
val functionalTestMicronautCoreVersion = requiredGradleProperty("pyronaut.micronaut.core.version")
val functionalTestMicronautPlatformVersion = requiredGradleProperty("pyronaut.micronaut.platform.version")
val functionalTestMicronautTestVersion = versionFromCatalog("micronaut-test")
val functionalTestResourcesVersion = versionFromCatalog("micronaut-test-resources")
val functionalTestGraalPyVersion = versionFromCatalog("graalpy")
val includedCoreSourcegenVersion = versionFromIncludedMicronautCoreCatalog("micronaut-sourcegen")
val includedCoreJavaParserVersion = versionFromIncludedMicronautCoreCatalog("managed-java-parser-core")
val useNativeExecutables = providers
    .gradleProperty("native")
    .map(String::toBoolean)
    .orElse(false)
val graalVmDevBuildTag = providers.gradleProperty("pyronautGraalVmDevTag")
val nativeExecutableSuffix = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""

val pyronautDevExecutable = project(":micronaut-pyronaut-dev")
    .layout.buildDirectory.file("install/micronaut-pyronaut-dev/bin/pyronaut-dev")
val pyronautDevNativeExecutable = project(":micronaut-pyronaut-dev")
    .layout.buildDirectory.file("native/nativeCompile/pyronaut-dev$nativeExecutableSuffix")
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

data class IncludedBuildPublishedArtifact(
    val projectDirName: String,
    val artifactId: String,
    val hasJar: Boolean = true,
)

data class ExternalFixtureArtifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val extension: String,
    val classifier: String?,
    val file: java.io.File,
)

data class StagedSnapshotVersion(
    val extension: String,
    val classifier: String?,
    val value: String,
)

val fixturePlatformPom by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies.add(fixturePlatformPom.name, "io.micronaut.platform:micronaut-platform:$functionalTestMicronautPlatformVersion@pom")

val sourcegenFixtureArtifactIds = listOf(
    "micronaut-sourcegen-annotations",
    "micronaut-sourcegen-bytecode-writer",
    "micronaut-sourcegen-generator",
    "micronaut-sourcegen-generator-java",
    "micronaut-sourcegen-model",
)

val micronautTestFixtureArtifactIds = listOf(
    "micronaut-test-core",
    "micronaut-test-junit5",
)

val fixtureSourcegenArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val fixtureSourcegenRuntimeArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
    useJavaRuntimeClasspathAttributes()
}

val fixtureMicronautTestArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val fixtureMicronautTestRuntimeArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
    useJavaRuntimeClasspathAttributes()
}

val fixtureIncludedCoreExternalArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    useJavaRuntimeClasspathAttributes()
}

for (artifactId in sourcegenFixtureArtifactIds) {
    dependencies.add(fixtureSourcegenArtifacts.name, "io.micronaut.sourcegen:$artifactId:$includedCoreSourcegenVersion")
    dependencies.add(fixtureSourcegenArtifacts.name, "io.micronaut.sourcegen:$artifactId:$includedCoreSourcegenVersion@pom")
    dependencies.add(fixtureSourcegenRuntimeArtifacts.name, "io.micronaut.sourcegen:$artifactId:$includedCoreSourcegenVersion")
}

for (artifactId in micronautTestFixtureArtifactIds) {
    dependencies.add(fixtureMicronautTestArtifacts.name, "io.micronaut.test:$artifactId:$functionalTestMicronautTestVersion")
    dependencies.add(fixtureMicronautTestArtifacts.name, "io.micronaut.test:$artifactId:$functionalTestMicronautTestVersion@pom")
}
dependencies.add(fixtureMicronautTestRuntimeArtifacts.name, mnTest.junit.jupiter.api)
dependencies.add(fixtureMicronautTestRuntimeArtifacts.name, mnTest.junit.jupiter.engine)
dependencies.add(fixtureMicronautTestRuntimeArtifacts.name, mnTest.junit.platform.engine)
dependencies.add(fixtureMicronautTestRuntimeArtifacts.name, mnTest.junit.platform.launcher)

dependencies.add(
    fixtureIncludedCoreExternalArtifacts.name,
    "com.github.javaparser:javaparser-symbol-solver-core:$includedCoreJavaParserVersion"
)
dependencies.add(
    fixtureIncludedCoreExternalArtifacts.name,
    "com.github.javaparser:javaparser-core:$includedCoreJavaParserVersion"
)

val stagedPyronautProjectPaths = listOf(
    ":micronaut-pyronaut-logback",
    ":micronaut-pyronaut-pytest",
    ":micronaut-pyronaut-requests",
)

val includedCoreArtifacts = listOf(
    IncludedBuildPublishedArtifact("aop", "micronaut-aop"),
    IncludedBuildPublishedArtifact("buffer-netty", "micronaut-buffer-netty"),
    IncludedBuildPublishedArtifact("context", "micronaut-context"),
    IncludedBuildPublishedArtifact("context-propagation", "micronaut-context-propagation"),
    IncludedBuildPublishedArtifact("context-python", "micronaut-context-python"),
    IncludedBuildPublishedArtifact("core", "micronaut-core"),
    IncludedBuildPublishedArtifact("core-bom", "micronaut-core-bom", hasJar = false),
    IncludedBuildPublishedArtifact("core-processor", "micronaut-core-processor"),
    IncludedBuildPublishedArtifact("core-reactive", "micronaut-core-reactive"),
    IncludedBuildPublishedArtifact("discovery-core", "micronaut-discovery-core"),
    IncludedBuildPublishedArtifact("function", "micronaut-function"),
    IncludedBuildPublishedArtifact("function-client", "micronaut-function-client"),
    IncludedBuildPublishedArtifact("function-web", "micronaut-function-web"),
    IncludedBuildPublishedArtifact("graal", "micronaut-graal"),
    IncludedBuildPublishedArtifact("http", "micronaut-http"),
    IncludedBuildPublishedArtifact("http-client", "micronaut-http-client"),
    IncludedBuildPublishedArtifact("http-client-core", "micronaut-http-client-core"),
    IncludedBuildPublishedArtifact("http-client-jdk", "micronaut-http-client-jdk"),
    IncludedBuildPublishedArtifact("http-netty", "micronaut-http-netty"),
    IncludedBuildPublishedArtifact("http-netty-http3", "micronaut-http-netty-http3"),
    IncludedBuildPublishedArtifact("http-server", "micronaut-http-server"),
    IncludedBuildPublishedArtifact("http-server-netty", "micronaut-http-server-netty"),
    IncludedBuildPublishedArtifact("http-validation", "micronaut-http-validation"),
    IncludedBuildPublishedArtifact("inject", "micronaut-inject"),
    IncludedBuildPublishedArtifact("inject-groovy", "micronaut-inject-groovy"),
    IncludedBuildPublishedArtifact("inject-java", "micronaut-inject-java"),
    IncludedBuildPublishedArtifact("inject-kotlin", "micronaut-inject-kotlin"),
    IncludedBuildPublishedArtifact("inject-python", "micronaut-inject-python"),
    IncludedBuildPublishedArtifact("jackson-core", "micronaut-jackson-core"),
    IncludedBuildPublishedArtifact("jackson-databind", "micronaut-jackson-databind"),
    IncludedBuildPublishedArtifact("json-core", "micronaut-json-core"),
    IncludedBuildPublishedArtifact("management", "micronaut-management"),
    IncludedBuildPublishedArtifact("messaging", "micronaut-messaging"),
    IncludedBuildPublishedArtifact("module-info", "micronaut-module-info"),
    IncludedBuildPublishedArtifact("module-info-runtime", "micronaut-module-info-runtime"),
    IncludedBuildPublishedArtifact("retry", "micronaut-retry"),
    IncludedBuildPublishedArtifact("router", "micronaut-router"),
    IncludedBuildPublishedArtifact("runtime", "micronaut-runtime"),
    IncludedBuildPublishedArtifact("runtime-osx", "micronaut-runtime-osx"),
    IncludedBuildPublishedArtifact("websocket", "micronaut-websocket"),
)

val includedDataArtifacts = listOf(
    IncludedBuildPublishedArtifact("data-connection", "micronaut-data-connection"),
    IncludedBuildPublishedArtifact("data-document-model", "micronaut-data-document-model"),
    IncludedBuildPublishedArtifact("data-document-processor", "micronaut-data-document-processor"),
    IncludedBuildPublishedArtifact("data-model", "micronaut-data-model"),
    IncludedBuildPublishedArtifact("data-mongodb", "micronaut-data-mongodb"),
    IncludedBuildPublishedArtifact("data-processor", "micronaut-data-processor"),
    IncludedBuildPublishedArtifact("data-r2dbc", "micronaut-data-r2dbc"),
    IncludedBuildPublishedArtifact("data-runtime", "micronaut-data-runtime"),
    IncludedBuildPublishedArtifact("data-tx", "micronaut-data-tx"),
)

fun versionFromCatalog(file: java.io.File, key: String): String {
    val pattern = Regex("""^${Regex.escape(key)}\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    return pattern.find(file.readText())?.groupValues?.get(1)
        ?: throw GradleException("Unable to find version '$key' in ${file.absolutePath}")
}

fun versionFromCatalog(key: String): String {
    return versionFromCatalog(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile, key)
}

fun requiredGradleProperty(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing required Gradle property '$name'")

fun Configuration.useJavaRuntimeClasspathAttributes() {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
        )
    }
}

fun versionFromIncludedMicronautCoreCatalog(key: String): String {
    val includedBuild = gradle.includedBuilds.find { it.name == "micronaut-core" }
        ?: throw GradleException("Unable to resolve $key without the included micronaut-core build")
    return versionFromCatalog(
        includedBuild.projectDir.resolve("gradle/libs.versions.toml"),
        key
    )
}

fun defaultFixtureEnv(): Map<String, String> {
    val environment = linkedMapOf<String, String>()
    environment["VIRTUAL_ENV"] = venvDir.asFile.absolutePath
    environment["PYRONAUT_PYTHON_EXECUTABLE"] = venvPython.asFile.absolutePath
    resolveFixtureSitePackagesDir()?.let {
        environment["PYRONAUT_PYTHON_SITE_PACKAGES"] = it.absolutePath
    }
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
        .redirectErrorStream(true)
    processBuilder.environment().putAll(defaultFixtureEnv() + extraEnvironment)
    val process = processBuilder.start()
    val maxCapturedCommandOutputChars = 32 * 1024
    val output = StringBuilder()
    val outputReader = Thread {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                logger.lifecycle(line)
                output.appendLine(line)
                if (output.length > maxCapturedCommandOutputChars) {
                    output.delete(0, output.length - maxCapturedCommandOutputChars)
                }
            }
        }
    }
    outputReader.start()
    val exitCode = process.waitFor()
    outputReader.join()
    if (exitCode != 0) {
        throw GradleException(buildString {
            append("Command failed with exit code ")
            append(exitCode)
            append(": ")
            append(renderedCommand)
            val capturedOutput = output.toString().trimEnd()
            if (capturedOutput.isNotBlank()) {
                append(System.lineSeparator())
                append(System.lineSeparator())
                append("Last command output:")
                append(System.lineSeparator())
                append(capturedOutput)
            }
        })
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
        if (argument.startsWith("-Djava.class.path=")) {
            val classpathEntries = argument.substringAfter("=")
                .split(java.io.File.pathSeparatorChar)
                .count { it.isNotBlank() }
            rendered += "-Djava.class.path=<classpath:$classpathEntries entries>"
            index++
            continue
        }
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

fun pyronautInstallExecutableFile(): java.io.File {
    return if (useNativeExecutables.get()) {
        pyronautDevNativeExecutable.get().asFile
    } else {
        pyronautDevExecutable.get().asFile
    }
}

fun pyronautProcessorExecutableFile(): java.io.File {
    return if (useNativeExecutables.get()) {
        pyronautDevNativeExecutable.get().asFile
    } else {
        pyronautDevExecutable.get().asFile
    }
}

fun pyronautValidateConfigExecutableFile(): java.io.File {
    return if (useNativeExecutables.get()) {
        pyronautDevNativeExecutable.get().asFile
    } else {
        pyronautDevExecutable.get().asFile
    }
}

fun pyronautTestResourcesServerExecutableFile(): java.io.File {
    return if (useNativeExecutables.get()) {
        pyronautDevNativeExecutable.get().asFile
    } else {
        pyronautDevExecutable.get().asFile
    }
}

fun pyronautTestExecutableFile(): java.io.File {
    return if (useNativeExecutables.get()) {
        pyronautDevNativeExecutable.get().asFile
    } else {
        pyronautDevExecutable.get().asFile
    }
}

fun pyronautCommand(command: String, executable: java.io.File, vararg args: String): List<String> {
    return listOf(executable.absolutePath) + nativeJavaHomeJvmArgs() + listOf(command, *args)
}

fun pyronautCommand(command: String, executable: java.io.File, jvmArgs: List<String>, vararg args: String): List<String> {
    return listOf(executable.absolutePath) + nativeJavaHomeJvmArgs() + jvmArgs + listOf(command, *args)
}

fun nativePyronautDevLauncherClasspathJvmArgs(): List<String> {
    if (!useNativeExecutables.get()) {
        return emptyList()
    }
    return listOf(
        "-Djava.class.path=${delegateLibEntries(pyronautDevExecutable.get().asFile).joinToString(separator = java.io.File.pathSeparator)}"
    )
}

fun nativeJavaHomeJvmArgs(): List<String> {
    if (!useNativeExecutables.get()) {
        return emptyList()
    }
    return listOf("-Djava.home=${resolveProvisionedGraalVmDevBuildHome().absolutePath}")
}

fun resolveProvisionedGraalVmDevBuildHome(): java.io.File {
    val configuredTag = graalVmDevBuildTag.orNull?.trim()
        ?: throw GradleException("Native functional-test requires pyronautGraalVmDevTag to resolve the matching java.home")
    val tag = configuredTag.removePrefix("jdk-")
    val tagDir = rootProject.layout.projectDirectory
        .dir(".gradle/pyronaut/graalvm-dev-builds")
        .asFile
        .resolve(tag)
    if (!tagDir.isDirectory) {
        throw GradleException("Missing provisioned GraalVM dev build for '$configuredTag': ${tagDir.absolutePath}")
    }
    return tagDir.walkTopDown()
        .filter { candidate -> candidate.isDirectory && candidate.resolve("bin/java").isFile }
        .sortedBy { candidate -> candidate.absolutePath }
        .firstOrNull()
        ?: throw GradleException("Unable to find java.home in provisioned GraalVM dev build: ${tagDir.absolutePath}")
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

fun readProperties(file: java.io.File): Properties {
    if (!file.isFile) {
        throw GradleException("Missing properties file: ${file.absolutePath}")
    }
    val properties = Properties()
    FileInputStream(file).use(properties::load)
    return properties
}

fun copyMavenArtifact(
    groupId: String,
    artifactId: String,
    version: String,
    pomFile: java.io.File,
    jarFile: java.io.File? = null,
    pomVersionReplacement: Pair<String, String>? = null,
) {
    if (!pomFile.isFile) {
        throw GradleException("Missing generated POM for $groupId:$artifactId:$version: ${pomFile.absolutePath}")
    }
    if (jarFile != null && !jarFile.isFile) {
        throw GradleException("Missing generated JAR for $groupId:$artifactId:$version: ${jarFile.absolutePath}")
    }
    val artifactDir = fixtureStagedRepoDir.asFile
        .resolve(groupId.replace('.', '/'))
        .resolve(artifactId)
        .resolve(version)
    artifactDir.mkdirs()
    val stagedPom = artifactDir.resolve("$artifactId-$version.pom")
    stagedPom.writeText(mavenPomContent(artifactId, pomFile, pomVersionReplacement))
    writeSha1(stagedPom)
    writeFixtureRepositoryMarker(artifactDir, stagedPom.name)
    jarFile?.let {
        val stagedJar = artifactDir.resolve("$artifactId-$version.jar")
        it.copyTo(stagedJar, overwrite = true)
        writeSha1(stagedJar)
        writeFixtureRepositoryMarker(artifactDir, stagedJar.name)
    }
    writeFixtureSnapshotMetadata(groupId, artifactId, version, artifactDir)
}

fun stagedMavenArtifactOutputFiles(
    groupId: String,
    artifactId: String,
    version: String,
    hasJar: Boolean = true,
): List<java.io.File> {
    val artifactDir = fixtureStagedRepoDir.asFile
        .resolve(groupId.replace('.', '/'))
        .resolve(artifactId)
        .resolve(version)
    return buildList {
        add(artifactDir.resolve("$artifactId-$version.pom"))
        add(artifactDir.resolve("$artifactId-$version.pom.sha1"))
        if (hasJar) {
            add(artifactDir.resolve("$artifactId-$version.jar"))
            add(artifactDir.resolve("$artifactId-$version.jar.sha1"))
        }
        add(artifactDir.resolve("_remote.repositories"))
    }
}

fun writeFixtureRepositoryMarker(artifactDir: java.io.File, fileName: String) {
    val marker = artifactDir.resolve("_remote.repositories")
    val entries = marker.takeIf { it.isFile }
        ?.readLines()
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("$fileName>") }
        ?.toMutableList()
        ?: mutableListOf()
    entries.add("$fileName>$fixtureRepositoryId=")
    marker.writeText(
        buildString {
            appendLine("#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.")
            appendLine("#${java.util.Date()}")
            entries.distinct().sorted().forEach { entry ->
                appendLine(entry)
            }
        }
    )
}

fun mavenPomContent(
    artifactId: String,
    pomFile: java.io.File,
    versionReplacement: Pair<String, String>? = null,
): String {
    var content = pomFile.readText()
    versionReplacement?.let { (sourceVersion, targetVersion) ->
        content = content.replace(">$sourceVersion<", ">$targetVersion<")
    }
    val sourcegenPlaceholder = "${'$'}{micronaut.sourcegen.version}"
    if (artifactId == "micronaut-core-bom" && content.contains(sourcegenPlaceholder)) {
        val sourcegenVersion = versionFromIncludedMicronautCoreCatalog("micronaut-sourcegen")
        content = content.replace(
            "  </properties>",
            "    <micronaut.sourcegen.version>$sourcegenVersion</micronaut.sourcegen.version>\n  </properties>"
        )
    }
    if (artifactId == "micronaut-core-bom") {
        content = replacePomProperty(content, "graal.version", functionalTestGraalPyVersion)
        content = removeImportedBomDependencies(content)
    }
    if (artifactId == "micronaut-platform") {
        content = replacePomProperty(content, "graal.version", functionalTestGraalPyVersion)
        content = replacePomProperty(content, "graalpy.embedding.version", functionalTestGraalPyVersion)
        content = replacePomProperty(content, "micronaut.sourcegen.version", versionFromIncludedMicronautCoreCatalog("micronaut-sourcegen"))
        content = replacePomProperty(content, "micronaut.test.version", functionalTestMicronautTestVersion)
        content = replacePomProperty(content, "micronaut.test.resources.version", functionalTestResourcesVersion)
        content = replacePomProperty(content, "micronaut.testresources.version", functionalTestResourcesVersion)
        content = removeImportedBomDependencies(content)
    }
    if (artifactId.startsWith("micronaut-sourcegen-")) {
        content = removeImportedBomDependencies(content)
        content = removePomDependenciesByGroup(content, "io.micronaut")
    }
    if (artifactId.startsWith("micronaut-test-")) {
        content = removeImportedBomDependencies(content)
        content = removePomDependenciesByGroup(content, "io.micronaut")
    }
    return content
}

fun replacePomProperty(content: String, propertyName: String, value: String): String {
    return content.replace(
        Regex("""<${Regex.escape(propertyName)}>[^<]+</${Regex.escape(propertyName)}>"""),
        "<$propertyName>$value</$propertyName>"
    )
}

fun removeImportedBomDependencies(content: String): String {
    return content.replace(
        Regex("""(?s)\n\s*<dependency>\s*(?:(?!</dependency>).)*?<type>pom</type>\s*<scope>import</scope>\s*(?:(?!</dependency>).)*?</dependency>"""),
        ""
    )
}

fun removePomDependenciesByGroup(content: String, groupId: String): String {
    return content.replace(
        Regex("""(?s)\n\s*<dependency>\s*<groupId>${Regex.escape(groupId)}</groupId>\s*(?:(?!</dependency>).)*?</dependency>"""),
        ""
    )
}

fun writeSha1(file: java.io.File) {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    file.resolveSibling("${file.name}.sha1").writeText(
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    )
}

fun Project.stageLocalPyronautFixtureArtifacts() {
    val bomProject = project(":micronaut-pyronaut-bom")
    val bomVersion = bomProject.version.toString()
    copyMavenArtifact(
        groupId = bomProject.group.toString(),
        artifactId = bomProject.name,
        version = bomVersion,
        pomFile = bomProject.layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile,
    )
    for (projectPath in stagedPyronautProjectPaths) {
        val artifact = fixturePublishedArtifact(projectPath)
        copyMavenArtifact(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            pomFile = artifact.pomFile,
            jarFile = artifact.jarFile,
            pomVersionReplacement = pyronautMicronautCoreVersionReplacement(artifact.pomFile),
        )
    }
}

fun Project.stageMicronautPlatformFixtureArtifact() {
    copyMavenArtifact(
        groupId = "io.micronaut.platform",
        artifactId = "micronaut-platform",
        version = functionalTestMicronautPlatformVersion,
        pomFile = fixturePlatformPom.singleFile,
    )
}

fun micronautDataVersionFromPlatformPom(): String =
    readMavenPomTag(fixturePlatformPom.singleFile, "micronaut.data.version")

fun Project.stageIncludedMicronautCoreFixtureArtifacts() {
    val includedBuild = gradle.includedBuilds.find { it.name == "micronaut-core" }
        ?: throw GradleException("functional-test requires the included micronaut-core build when using $functionalTestMicronautCoreVersion")
    val properties = readProperties(includedBuild.projectDir.resolve("gradle.properties"))
    val coreGroupId = properties.getProperty("projectGroupId")
    val sourceVersion = properties.getProperty("projectVersion")
    val targetVersion = functionalTestMicronautCoreVersion
    for (artifact in includedCoreArtifacts) {
        val projectDir = includedBuild.projectDir.resolve(artifact.projectDirName)
        copyMavenArtifact(
            groupId = coreGroupId,
            artifactId = artifact.artifactId,
            version = targetVersion,
            pomFile = projectDir.resolve("build/publications/maven/pom-default.xml"),
            jarFile = if (artifact.hasJar) {
                projectDir.resolve("build/libs/${artifact.artifactId}-$sourceVersion.jar")
            } else {
                null
            },
            pomVersionReplacement = sourceVersion to targetVersion,
        )
    }
}

fun Project.stageIncludedMicronautDataFixtureArtifacts() {
    val includedBuild = gradle.includedBuilds.find { it.name == "micronaut-data" } ?: return
    val targetVersion = micronautDataVersionFromPlatformPom()
    for (artifact in includedDataArtifacts) {
        val projectDir = includedBuild.projectDir.resolve(artifact.projectDirName)
        val pomFile = projectDir.resolve("build/publications/maven/pom-default.xml")
        val sourceVersion = readMavenPomTag(pomFile, "version")
        copyMavenArtifact(
            groupId = readMavenPomTag(pomFile, "groupId"),
            artifactId = readMavenPomTag(pomFile, "artifactId"),
            version = targetVersion,
            pomFile = pomFile,
            jarFile = if (artifact.hasJar) {
                projectDir.resolve("build/libs/${artifact.artifactId}-$sourceVersion.jar")
            } else {
                null
            },
            pomVersionReplacement = sourceVersion to targetVersion,
        )
    }
}

fun readMavenPomTag(pomFile: java.io.File, tagName: String): String {
    if (!pomFile.isFile) {
        throw GradleException("Missing generated POM: ${pomFile.absolutePath}")
    }
    return Regex("""<${Regex.escape(tagName)}>([^<]+)</${Regex.escape(tagName)}>""")
        .find(pomFile.readText())
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Unable to find <$tagName> in ${pomFile.absolutePath}")
}

fun readMavenDependencyVersion(
    pomFile: java.io.File,
    groupId: String,
    artifactId: String,
): String? {
    if (!pomFile.isFile) {
        throw GradleException("Missing generated POM: ${pomFile.absolutePath}")
    }
    val dependencyPattern = """
        (?s)<dependency>\s*
        <groupId>${Regex.escape(groupId)}</groupId>\s*
        <artifactId>${Regex.escape(artifactId)}</artifactId>\s*
        <version>([^<]+)</version>.*?</dependency>
    """.trimIndent().replace("\n", "")
    return Regex(dependencyPattern)
        .find(pomFile.readText())
        ?.groupValues
        ?.get(1)
}

fun pyronautMicronautCoreVersionReplacement(pomFile: java.io.File): Pair<String, String>? =
    readMavenDependencyVersion(pomFile, "io.micronaut", "micronaut-core-bom")
        ?.takeIf { it != functionalTestMicronautCoreVersion }
        ?.let { it to functionalTestMicronautCoreVersion }

fun Project.stageSourcegenFixtureArtifacts() {
    stageResolvedFixtureArtifacts(fixtureSourcegenArtifacts.resolvedConfiguration.resolvedArtifacts)
    stageResolvedFixtureArtifacts(
        fixtureSourcegenRuntimeArtifacts.resolvedConfiguration.resolvedArtifacts,
        includeArtifact = ::isExternalFixtureArtifact,
        includePom = true,
    )
}

fun Project.stageMicronautTestFixtureArtifacts() {
    stageResolvedFixtureArtifacts(fixtureMicronautTestArtifacts.resolvedConfiguration.resolvedArtifacts)
    stageResolvedFixtureArtifacts(
        fixtureMicronautTestRuntimeArtifacts.resolvedConfiguration.resolvedArtifacts,
        includeArtifact = ::isExternalFixtureArtifact,
        includePom = true,
    )
}

fun Project.stageIncludedCoreExternalFixtureArtifacts() {
    stageResolvedFixtureArtifacts(
        fixtureIncludedCoreExternalArtifacts.resolvedConfiguration.resolvedArtifacts,
        includeArtifact = ::isExternalFixtureArtifact,
        includePom = true,
    )
}

fun Project.stageResolvedFixtureArtifacts(
    resolvedArtifacts: Set<org.gradle.api.artifacts.ResolvedArtifact>,
    includeArtifact: (ExternalFixtureArtifact) -> Boolean = { true },
    includePom: Boolean = false,
) {
    val artifacts = resolvedArtifacts.map { artifact ->
        val moduleVersion = artifact.moduleVersion.id
        ExternalFixtureArtifact(
            groupId = moduleVersion.group,
            artifactId = artifact.name,
            version = moduleVersion.version,
            extension = artifact.extension ?: artifact.file.extension,
            classifier = artifact.classifier,
            file = artifact.file,
        )
    }
    for (artifact in artifacts) {
        if (!includeArtifact(artifact)) {
            continue
        }
        copyMavenArtifactFile(artifact)
        if (includePom && artifact.extension != "pom") {
            resolveAndCopyMavenPom(artifact)
        }
    }
}

fun isExternalFixtureArtifact(artifact: ExternalFixtureArtifact): Boolean {
    return !artifact.groupId.startsWith("io.micronaut")
}

fun Project.resolveAndCopyMavenPom(artifact: ExternalFixtureArtifact) {
    val pomConfiguration = configurations.detachedConfiguration(
        dependencies.create("${artifact.groupId}:${artifact.artifactId}:${artifact.version}@pom")
    )
    pomConfiguration.isTransitive = false
    copyMavenArtifactFile(
        ExternalFixtureArtifact(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            extension = "pom",
            classifier = null,
            file = pomConfiguration.singleFile,
        )
    )
}

fun copyMavenArtifactFile(artifact: ExternalFixtureArtifact) {
    if (!artifact.file.isFile) {
        throw GradleException("Missing resolved artifact file: ${artifact.file.absolutePath}")
    }
    val artifactDir = fixtureStagedRepoDir.asFile
        .resolve(artifact.groupId.replace('.', '/'))
        .resolve(artifact.artifactId)
        .resolve(artifact.version)
    artifactDir.mkdirs()
    val classifier = artifact.classifier?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
    val stagedFile = artifactDir.resolve("${artifact.artifactId}-${artifact.version}$classifier.${artifact.extension}")
    if (artifact.extension == "pom") {
        stagedFile.writeText(mavenPomContent(artifact.artifactId, artifact.file))
    } else {
        artifact.file.copyTo(stagedFile, overwrite = true)
    }
    writeSha1(stagedFile)
    writeFixtureRepositoryMarker(artifactDir, stagedFile.name)
    writeFixtureSnapshotMetadata(artifact.groupId, artifact.artifactId, artifact.version, artifactDir)
}

fun writeFixtureSnapshotMetadata(
    groupId: String,
    artifactId: String,
    version: String,
    artifactDir: java.io.File,
) {
    if (!version.endsWith("-SNAPSHOT")) {
        return
    }
    val snapshotVersions = artifactDir.listFiles()
        ?.filter { file ->
            file.isFile &&
                file.name.startsWith("$artifactId-$version") &&
                !file.name.endsWith(".sha1")
        }
        ?.mapNotNull { file -> stagedSnapshotVersion(artifactId, version, file.name) }
        ?.sortedWith(compareBy<StagedSnapshotVersion> { it.extension }.thenBy { it.classifier.orEmpty() })
        .orEmpty()
    if (snapshotVersions.isEmpty()) {
        return
    }

    val metadataFile = artifactDir.resolve("maven-metadata.xml")
    metadataFile.writeText(
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<metadata modelVersion="1.1.0">""")
            appendLine("  <groupId>$groupId</groupId>")
            appendLine("  <artifactId>$artifactId</artifactId>")
            appendLine("  <versioning>")
            appendLine("    <snapshot>")
            appendLine("      <localCopy>true</localCopy>")
            appendLine("    </snapshot>")
            appendLine("    <snapshotVersions>")
            snapshotVersions.forEach { snapshotVersion ->
                appendLine("      <snapshotVersion>")
                snapshotVersion.classifier?.let { classifier ->
                    appendLine("        <classifier>$classifier</classifier>")
                }
                appendLine("        <extension>${snapshotVersion.extension}</extension>")
                appendLine("        <value>${snapshotVersion.value}</value>")
                appendLine("      </snapshotVersion>")
            }
            appendLine("    </snapshotVersions>")
            appendLine("  </versioning>")
            appendLine("  <version>$version</version>")
            appendLine("</metadata>")
        }
    )
    writeSha1(metadataFile)

    val versionMetadataFile = artifactDir.parentFile.resolve("maven-metadata.xml")
    versionMetadataFile.writeText(
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<metadata>")
            appendLine("  <groupId>$groupId</groupId>")
            appendLine("  <artifactId>$artifactId</artifactId>")
            appendLine("  <versioning>")
            appendLine("    <latest>$version</latest>")
            appendLine("    <versions>")
            appendLine("      <version>$version</version>")
            appendLine("    </versions>")
            appendLine("  </versioning>")
            appendLine("</metadata>")
        }
    )
    writeSha1(versionMetadataFile)
}

fun stagedSnapshotVersion(artifactId: String, version: String, fileName: String): StagedSnapshotVersion? {
    val suffix = fileName.removePrefix("$artifactId-$version")
    if (suffix.isEmpty() || !suffix.contains(".")) {
        return null
    }
    val classifier = suffix
        .substringBeforeLast(".")
        .removePrefix("-")
        .takeIf { it.isNotBlank() }
    val extension = suffix.substringAfterLast(".")
    return StagedSnapshotVersion(extension, classifier, version)
}

fun stagedExternalFixtureArtifactOutputFiles(
    artifact: ExternalFixtureArtifact,
    includePom: Boolean = false,
): List<java.io.File> {
    val artifactDir = fixtureStagedRepoDir.asFile
        .resolve(artifact.groupId.replace('.', '/'))
        .resolve(artifact.artifactId)
        .resolve(artifact.version)
    val classifier = artifact.classifier?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
    val fileName = "${artifact.artifactId}-${artifact.version}$classifier.${artifact.extension}"
    return buildList {
        add(artifactDir.resolve(fileName))
        add(artifactDir.resolve("$fileName.sha1"))
        add(artifactDir.resolve("_remote.repositories"))
        if (includePom && artifact.extension != "pom") {
            addAll(stagedMavenArtifactOutputFiles(artifact.groupId, artifact.artifactId, artifact.version, hasJar = false))
        }
    }.distinct()
}

fun resolvedFixtureArtifactOutputFiles(
    resolvedArtifacts: Set<org.gradle.api.artifacts.ResolvedArtifact>,
    includeArtifact: (ExternalFixtureArtifact) -> Boolean = { true },
    includePom: Boolean = false,
): List<java.io.File> {
    return resolvedArtifacts.flatMap { artifact ->
        val moduleVersion = artifact.moduleVersion.id
        val fixtureArtifact = ExternalFixtureArtifact(
            groupId = moduleVersion.group,
            artifactId = artifact.name,
            version = moduleVersion.version,
            extension = artifact.extension ?: artifact.file.extension,
            classifier = artifact.classifier,
            file = artifact.file,
        )
        if (includeArtifact(fixtureArtifact)) {
            stagedExternalFixtureArtifactOutputFiles(fixtureArtifact, includePom)
        } else {
            emptyList<java.io.File>()
        }
    }.distinct()
}

fun stagedRepositoryCopyOutputFiles(sourceRepository: java.io.File): List<java.io.File> {
    if (!sourceRepository.isDirectory) {
        return emptyList()
    }
    val sourceRoot = sourceRepository.toPath()
    val targetRoot = fixtureStagedRepoDir.asFile.toPath()
    return sourceRepository
        .walkTopDown()
        .filter { it.isFile }
        .map { sourceFile -> targetRoot.resolve(sourceRoot.relativize(sourceFile.toPath())).toFile() }
        .toList()
}

fun Project.stageGraalPyFixtureArtifacts() {
    val bundleRepo = System.getProperty("pyronaut.graalpy.bundle.repo")
        ?.takeIf { it.isNotBlank() }
        ?.let { java.io.File(it) }
        ?: throw GradleException("Missing pyronaut.graalpy.bundle.repo system property for GraalPy fixture artifacts")
    if (!bundleRepo.isDirectory) {
        throw GradleException("Missing GraalPy fixture repository: ${bundleRepo.absolutePath}")
    }
    copy {
        from(bundleRepo)
        into(fixtureStagedRepoDir)
    }
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
    val sitePackagesDir = resolveFixtureSitePackagesDir() ?: return false
    val pytestPackageDir = sitePackagesDir.resolve("pytest")
    val privatePytestPackageDir = sitePackagesDir.resolve("_pytest")
    return pytestPackageDir.isDirectory && privatePytestPackageDir.isDirectory
}

fun resolveFixtureSitePackagesDir(): java.io.File? {
    val libDir = venvDir.dir("lib").asFile
    if (!libDir.isDirectory) {
        return null
    }
    val pythonLibDir = libDir.listFiles { file -> file.isDirectory && file.name.startsWith("python") }
        ?.sortedBy { it.name }
        ?.lastOrNull()
        ?: return null
    val sitePackagesDir = pythonLibDir.resolve("site-packages")
    if (!sitePackagesDir.isDirectory) {
        return null
    }
    return sitePackagesDir
}

fun installAppOutputsPresent(): Boolean {
    return fixtureResolvedBuildDependencies.asFile.isFile &&
        fixtureResolvedRuntimeDependencies.asFile.isFile &&
        fixtureResolvedTestDependencies.asFile.isFile &&
        fixtureResolvedEditorArtifacts.asFile.isFile &&
        fixtureResolvedTestResourcesServerDependencies.asFile.isFile &&
        fixtureM2RepoDir.asFile.isDirectory &&
        fixtureSchemasDir.asFile.isDirectory &&
        fixtureIdeStubsDir.asFile.isDirectory
}

fun requireFixtureFileContains(file: java.io.File, expected: String, description: String) {
    if (!file.isFile) {
        throw GradleException("Missing $description: ${file.absolutePath}")
    }
    val content = file.readText()
    if (!content.contains(expected)) {
        throw GradleException("Expected $description to contain '$expected': ${file.absolutePath}")
    }
}

fun buildFixtureApplicationClasspathEntries(): List<String> {
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
    return classpathEntries.distinct()
}

fun fixtureApplicationClasspathEntriesWithoutDuplicateVfsJars(): List<String> {
    val bundledVfsJarNames = nativeLauncherProvidedJarNames()
        .filter { jarName -> containsGraalPyVirtualFileSystem(pyronautDevExecutable.get().asFile.parentFile.parentFile.resolve("lib").resolve(jarName)) }
        .toSet()
    return buildFixtureApplicationClasspathEntries()
        .filterNot { entry -> bundledVfsJarNames.contains(java.io.File(entry).name) }
        .distinct()
}

fun nativeFixtureApplicationClasspathEntries(): List<String> {
    val launcherProvidedJarNames = nativeLauncherProvidedJarNames()
    val launcherProvidedArtifactIds = nativeLauncherProvidedArtifactIds()
    return fixtureApplicationClasspathEntriesWithoutDuplicateVfsJars()
        .filterNot { entry ->
            val fileName = java.io.File(entry).name
            val artifactId = versionedJarArtifactId(fileName)
            isNativeTestResourcesClientArtifact(fileName) ||
                launcherProvidedJarNames.contains(fileName) ||
                launcherProvidedArtifactIds.contains(artifactId)
        }
        .distinct()
}

fun nativeTestResourcesClientClasspathEntries(): List<String> {
    val cacheDir = fixtureAppDir.dir("__pyronaut__").asFile
    return buildList {
        addAll(readManifestEntries(cacheDir.resolve("resolved-test-dependencies")))
        val runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies")
        if (runtimeManifest.isFile) {
            addAll(readManifestEntries(runtimeManifest))
        }
    }
        .filter { entry -> isNativeTestResourcesClientArtifact(java.io.File(entry).name) }
        .distinct()
}

fun nativeLauncherProvidedJarNames(): Set<String> {
    return delegateLibEntries(pyronautDevExecutable.get().asFile)
        .map { java.io.File(it).name }
        .toSet()
}

fun nativeLauncherProvidedArtifactIds(): Set<String> {
    return nativeLauncherProvidedJarNames()
        .mapNotNull(::versionedJarArtifactId)
        .toSet()
}

fun versionedJarArtifactId(fileName: String): String? {
    if (!fileName.endsWith(".jar")) {
        return null
    }
    val baseName = fileName.removeSuffix(".jar")
    val versionSeparator = Regex("-(?=\\d)").find(baseName) ?: return null
    return baseName.substring(0, versionSeparator.range.first)
}

fun isNativeTestResourcesClientArtifact(fileName: String): Boolean {
    return fileName.startsWith("micronaut-test-resources-client-") ||
        fileName.startsWith("micronaut-test-resources-core-") ||
        fileName.startsWith("micronaut-test-resources-codec-")
}

fun containsGraalPyVirtualFileSystem(file: java.io.File): Boolean {
    if (!file.isFile || file.extension != "jar") {
        return false
    }
    return java.util.jar.JarFile(file).use { jarFile ->
        jarFile.entries().asSequence().any { entry ->
            entry.name.startsWith("META-INF/GRAALPY-VFS/micronaut-application/")
        }
    }
}

fun testResourcesJvmArgs(testResourcesEnv: Map<String, String>): List<String> {
    return listOfNotNull(
        testResourcesEnv["MICRONAUT_TEST_RESOURCES_SERVER_URI"]
            ?.takeIf(String::isNotBlank)
            ?.let { "-Dmicronaut.test.resources.server.uri=$it" },
        testResourcesEnv["MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"]
            ?.takeIf(String::isNotBlank)
            ?.let { "-Dmicronaut.test.resources.server.access.token=$it" },
        testResourcesEnv["MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT"]
            ?.takeIf(String::isNotBlank)
            ?.let { "-Dmicronaut.test.resources.server.client.read.timeout=$it" },
    )
}

fun buildPyronautTestCommand(testResourcesEnv: Map<String, String> = emptyMap()): List<String> {
    if (useNativeExecutables.get()) {
        val nativeClasspathEntries = nativeFixtureApplicationClasspathEntries()
        val testResourcesClientClasspathEntries = nativeTestResourcesClientClasspathEntries()
        return listOf(
            pyronautTestExecutableFile().absolutePath,
            *nativeJavaHomeJvmArgs().toTypedArray(),
            "-Djava.class.path=${nativeClasspathEntries.joinToString(separator = java.io.File.pathSeparator)}",
            "-Dpyronaut.dev.test.resources.client.classpath=${testResourcesClientClasspathEntries.joinToString(separator = java.io.File.pathSeparator)}",
            *testResourcesJvmArgs(testResourcesEnv).toTypedArray(),
            "test",
            "--project-dir",
            fixtureAppDir.asFile.absolutePath,
        )
    }
    val reducedClasspathEntries = fixtureApplicationClasspathEntriesWithoutDuplicateVfsJars().toMutableList()
    reducedClasspathEntries += delegateLibEntries(pyronautDevExecutable.get().asFile)

    return listOf(
        resolveJavaExecutable(),
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        "-Dpyronaut.test.render-failure-output=true",
        "-Dpyronaut.use.system.application.classloader=true",
        "-cp",
        reducedClasspathEntries.distinct().joinToString(separator = java.io.File.pathSeparator),
        "io.micronaut.pyronaut.dev.PyronautDevMain",
        "test",
        "--project-dir",
        fixtureAppDir.asFile.absolutePath,
    )
}

val stagePyronautFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages local Pyronaut fixture modules into the functional-test file repository."
    val bomProject = project(":micronaut-pyronaut-bom")
    val taskDependencies = mutableListOf<Any>()
    for (projectPath in stagedPyronautProjectPaths) {
        val stagedProject = project(projectPath)
        val jarTask = stagedProject.tasks.named("jar", Jar::class.java)
        taskDependencies.add(jarTask)
        taskDependencies.add(stagedProject.tasks.named("generatePomFileForMavenPublication"))
        inputs.files(jarTask.flatMap { it.archiveFile })
        inputs.file(stagedProject.layout.buildDirectory.file("publications/maven/pom-default.xml"))
    }
    inputs.file(bomProject.layout.buildDirectory.file("publications/maven/pom-default.xml"))
    dependsOn(
        bomProject.tasks.named("generatePomFileForMavenPublication"),
        taskDependencies,
    )
    outputs.files(providers.provider {
        val outputs = mutableListOf<java.io.File>()
        outputs.addAll(
            stagedMavenArtifactOutputFiles(
                bomProject.group.toString(),
                bomProject.name,
                bomProject.version.toString(),
                hasJar = false,
            )
        )
        for (projectPath in stagedPyronautProjectPaths) {
            val artifact = project.fixturePublishedArtifact(projectPath)
            outputs.addAll(stagedMavenArtifactOutputFiles(artifact.groupId, artifact.artifactId, artifact.version))
        }
        outputs.distinct()
    })
    doLast {
        project.stageLocalPyronautFixtureArtifacts()
    }
}

val stageMicronautPlatformFixtureArtifact by tasks.registering {
    group = "build setup"
    description = "Stages the Micronaut Platform BOM used by the functional-test file repository."
    inputs.files(fixturePlatformPom)
    outputs.files(
        stagedMavenArtifactOutputFiles(
            "io.micronaut.platform",
            "micronaut-platform",
            functionalTestMicronautPlatformVersion,
            hasJar = false,
        )
    )
    doLast {
        project.stageMicronautPlatformFixtureArtifact()
    }
}

val stageMicronautCoreFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages included Micronaut Core artifacts into the functional-test file repository."
    val micronautCoreIncludedBuild = gradle.includedBuilds.find { it.name == "micronaut-core" }
    val taskDependencies = mutableListOf<Any>()
    if (micronautCoreIncludedBuild != null) {
        for (artifact in includedCoreArtifacts) {
            val projectDir = micronautCoreIncludedBuild.projectDir.resolve(artifact.projectDirName)
            taskDependencies.add(micronautCoreIncludedBuild.task(":${artifact.artifactId}:generatePomFileForMavenPublication"))
            inputs.file(projectDir.resolve("build/publications/maven/pom-default.xml"))
            if (artifact.hasJar) {
                taskDependencies.add(micronautCoreIncludedBuild.task(":${artifact.artifactId}:jar"))
                inputs.dir(projectDir.resolve("build/libs"))
            }
        }
    }
    dependsOn(taskDependencies)
    outputs.files(providers.provider {
        val includedBuild = gradle.includedBuilds.find { it.name == "micronaut-core" } ?: return@provider emptyList<java.io.File>()
        val properties = readProperties(includedBuild.projectDir.resolve("gradle.properties"))
        val coreGroupId = properties.getProperty("projectGroupId")
        includedCoreArtifacts
            .flatMap { artifact -> stagedMavenArtifactOutputFiles(coreGroupId, artifact.artifactId, functionalTestMicronautCoreVersion, artifact.hasJar) }
            .distinct()
    })
    doLast {
        project.stageIncludedMicronautCoreFixtureArtifacts()
    }
}

val stageMicronautDataFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages included Micronaut Data artifacts into the functional-test file repository."
    inputs.files(fixturePlatformPom)
    val micronautDataIncludedBuild = gradle.includedBuilds.find { it.name == "micronaut-data" }
    val taskDependencies = mutableListOf<Any>()
    if (micronautDataIncludedBuild != null) {
        for (artifact in includedDataArtifacts) {
            val projectDir = micronautDataIncludedBuild.projectDir.resolve(artifact.projectDirName)
            taskDependencies.add(micronautDataIncludedBuild.task(":${artifact.artifactId}:generatePomFileForMavenPublication"))
            inputs.file(projectDir.resolve("build/publications/maven/pom-default.xml"))
            if (artifact.hasJar) {
                taskDependencies.add(micronautDataIncludedBuild.task(":${artifact.artifactId}:jar"))
                inputs.dir(projectDir.resolve("build/libs"))
            }
        }
    }
    dependsOn(taskDependencies)
    outputs.files(providers.provider {
        if (gradle.includedBuilds.none { it.name == "micronaut-data" }) {
            emptyList<java.io.File>()
        } else {
            val targetVersion = micronautDataVersionFromPlatformPom()
            includedDataArtifacts
                .flatMap { artifact ->
                    stagedMavenArtifactOutputFiles("io.micronaut.data", artifact.artifactId, targetVersion, artifact.hasJar)
                }
                .distinct()
        }
    })
    doLast {
        project.stageIncludedMicronautDataFixtureArtifacts()
    }
}

val stageSourcegenFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages SourceGen artifacts needed by included Micronaut Core Python artifacts."
    inputs.files(fixtureSourcegenArtifacts, fixtureSourcegenRuntimeArtifacts)
    outputs.files(providers.provider {
        resolvedFixtureArtifactOutputFiles(fixtureSourcegenArtifacts.resolvedConfiguration.resolvedArtifacts) +
            resolvedFixtureArtifactOutputFiles(
                fixtureSourcegenRuntimeArtifacts.resolvedConfiguration.resolvedArtifacts,
                includeArtifact = ::isExternalFixtureArtifact,
                includePom = true,
            )
    })
    doLast {
        project.stageSourcegenFixtureArtifacts()
    }
}

val stageMicronautTestFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages Micronaut Test artifacts needed by the fixture test scope."
    inputs.files(fixtureMicronautTestArtifacts, fixtureMicronautTestRuntimeArtifacts)
    outputs.files(providers.provider {
        resolvedFixtureArtifactOutputFiles(fixtureMicronautTestArtifacts.resolvedConfiguration.resolvedArtifacts) +
            resolvedFixtureArtifactOutputFiles(
                fixtureMicronautTestRuntimeArtifacts.resolvedConfiguration.resolvedArtifacts,
                includeArtifact = ::isExternalFixtureArtifact,
                includePom = true,
            )
    })
    doLast {
        project.stageMicronautTestFixtureArtifacts()
    }
}

val stageIncludedCoreExternalFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages external artifacts referenced by included Micronaut Core POMs."
    inputs.files(fixtureIncludedCoreExternalArtifacts)
    outputs.files(providers.provider {
        resolvedFixtureArtifactOutputFiles(
            fixtureIncludedCoreExternalArtifacts.resolvedConfiguration.resolvedArtifacts,
            includeArtifact = ::isExternalFixtureArtifact,
            includePom = true,
        )
    })
    doLast {
        project.stageIncludedCoreExternalFixtureArtifacts()
    }
}

val stageGraalPyFixtureArtifacts by tasks.registering {
    group = "build setup"
    description = "Stages GraalPy snapshot artifacts into the functional-test file repository."
    val bundleRepo = System.getProperty("pyronaut.graalpy.bundle.repo")
    if (!bundleRepo.isNullOrBlank()) {
        inputs.dir(java.io.File(bundleRepo))
    }
    outputs.files(providers.provider {
        bundleRepo
            ?.takeIf { it.isNotBlank() }
            ?.let { stagedRepositoryCopyOutputFiles(java.io.File(it)) }
            ?: emptyList<java.io.File>()
    })
    doLast {
        project.stageGraalPyFixtureArtifacts()
    }
}

val publishFixtureArtifactsToMavenLocal by tasks.registering {
    group = "build setup"
    description = "Stages local fixture artifacts into the functional-test file repository."
    dependsOn(
        stagePyronautFixtureArtifacts,
        stageMicronautPlatformFixtureArtifact,
        stageMicronautCoreFixtureArtifacts,
        stageMicronautDataFixtureArtifacts,
        stageSourcegenFixtureArtifacts,
        stageIncludedCoreExternalFixtureArtifacts,
        stageMicronautTestFixtureArtifacts,
        stageGraalPyFixtureArtifacts,
    )
}

val installFixtureLaunchers by tasks.registering {
    group = "build setup"
    description = "Builds the local Pyronaut launchers used by the functional-test app."
    dependsOn(
        project(":micronaut-pyronaut-dev").tasks.named("installDist"),
    )
    if (useNativeExecutables.get()) {
        dependsOn(
            project(":micronaut-pyronaut-dev").tasks.named("nativeCompile"),
        )
    }
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
    )
    inputs.property("pyronautExecutableMode", providers.provider { if (useNativeExecutables.get()) "native" else "jvm" })
    inputs.file(providers.provider { pyronautInstallExecutableFile() })
    inputs.dir(fixtureStagedRepoDir)
    outputs.file(installAppMarker)
    outputs.upToDateWhen {
        installAppMarker.get().asFile.isFile && installAppOutputsPresent()
    }
    doLast {
        val installExecutable = pyronautInstallExecutableFile()
        if (!installExecutable.isFile) {
            throw GradleException("Missing pyronaut-install executable: ${installExecutable.absolutePath}")
        }
        logger.lifecycle(
            "Using {} pyronaut-install executable: {}",
            if (useNativeExecutables.get()) "native" else "JVM",
            installExecutable.absolutePath
        )
        project.runFixtureCommand(
            pyronautCommand("install", installExecutable, "--project-dir", fixtureAppDir.asFile.absolutePath)
        )
        installAppMarker.get().asFile.parentFile.mkdirs()
        installAppMarker.get().asFile.writeText("ready\n")
    }
}

val verifyEditorSupport by tasks.registering {
    group = "verification"
    description = "Verifies that pyronaut-install generated IDE stubs and VS Code settings for the fixture app."
    dependsOn(installApp)
    inputs.files(
        fixtureResolvedEditorArtifacts,
        fixtureIdeStubsDir.file("micronaut/http/annotation/__init__.pyi"),
        fixtureIdeStubsDir.file("micronaut/http/__init__.pyi"),
        fixtureIdeStubsDir.file("jakarta/inject/__init__.pyi"),
        fixtureIdeStubsDir.file("logback/config.py"),
        fixtureIdeStubsDir.file("pyronaut/test/__init__.pyi"),
        fixtureAppDir.file(".vscode/settings.json"),
    )
    doLast {
        requireFixtureFileContains(
            fixtureResolvedEditorArtifacts.asFile,
            "\"scope\":\"runtime\"",
            "resolved editor artifact manifest"
        )
        requireFixtureFileContains(
            fixtureAppDir.file(".vscode/settings.json").asFile,
            "__pyronaut__/ide-stubs",
            "VS Code settings"
        )
        requireFixtureFileContains(
            fixtureAppDir.file(".vscode/settings.json").asFile,
            "site-packages",
            "VS Code pytest resolution"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("micronaut/http/annotation/__init__.pyi").asFile,
            "def Get(value: str = ...",
            "Micronaut annotation stubs"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("micronaut/http/annotation/__init__.pyi").asFile,
            "def Get(target: _T, /) -> _T: ...",
            "Micronaut annotation stub docstrings"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("micronaut/http/__init__.pyi").asFile,
            "class HttpResponse(",
            "Micronaut HTTP stubs"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("micronaut/http/__init__.pyi").asFile,
            "def status(status: HttpStatus)",
            "Micronaut HTTP response status stubs"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("jakarta/inject/__init__.pyi").asFile,
            "def Inject() -> Callable[[_T], _T]: ...",
            "Jakarta inject stubs"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("jakarta/inject/__init__.pyi").asFile,
            "Identifies injectable constructors, methods, and fields.",
            "Jakarta annotation stub docstrings"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("logback/config.py").asFile,
            "def dictConfig(config):",
            "Packaged Python VFS sources"
        )
        requireFixtureFileContains(
            fixtureIdeStubsDir.file("pyronaut/test/__init__.pyi").asFile,
            "def micronaut_test_fixture(request: Any, micronaut_test: MicronautTest | None = ...)",
            "Pyronaut pytest support stubs"
        )
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
    inputs.property("pyronautExecutableMode", providers.provider { if (useNativeExecutables.get()) "native" else "jvm" })
    inputs.file(providers.provider { pyronautValidateConfigExecutableFile() })
    outputs.files(fixtureConfigValidationCache)
    outputs.dir(fixtureConfigValidationReportDir)
    doLast {
        val validateConfigExecutable = pyronautValidateConfigExecutableFile()
        if (!validateConfigExecutable.isFile) {
            throw GradleException("Missing pyronaut-validate-config executable: ${validateConfigExecutable.absolutePath}")
        }
        logger.lifecycle(
            "Using {} pyronaut-validate-config executable: {}",
            if (useNativeExecutables.get()) "native" else "JVM",
            validateConfigExecutable.absolutePath
        )
        project.runFixtureCommand(
            pyronautCommand(
                "validate-config",
                validateConfigExecutable,
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
    inputs.property(
        "pyronautExecutableMode",
        providers.provider { if (useNativeExecutables.get()) "native" else "jvm" }
    )
    inputs.files(
        fixtureResolvedBuildDependencies,
        fixtureResolvedRuntimeDependencies,
        fixtureResolvedTestDependencies,
    )
    inputs.file(providers.provider { pyronautProcessorExecutableFile() })
    outputs.dirs(
        fixtureProcessedClassesDir,
        fixtureProcessedTestClassesDir,
    )
    doLast {
        val processorExecutable = pyronautProcessorExecutableFile()
        if (!processorExecutable.isFile) {
            throw GradleException("Missing pyronaut-processor executable: ${processorExecutable.absolutePath}")
        }
        logger.lifecycle(
            "Using {} pyronaut-processor executable: {}",
            if (useNativeExecutables.get()) "native" else "JVM",
            processorExecutable.absolutePath
        )
        project.runFixtureCommand(
            pyronautCommand("process", processorExecutable, "--project-dir", fixtureAppDir.asFile.absolutePath)
        )
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the functional-test app through pyronaut-test using local project launchers."
    dependsOn(process, verifyEditorSupport)
    inputs.dir(fixtureAppDir)
    doLast {
        val testResourcesExecutable = pyronautTestResourcesServerExecutableFile()
        val startCommand = pyronautCommand(
            "test-resources-server",
            testResourcesExecutable,
            nativePyronautDevLauncherClasspathJvmArgs(),
            "start",
            "--project-dir",
            fixtureAppDir.asFile.absolutePath,
        )
        val stopCommand = pyronautCommand(
            "test-resources-server",
            testResourcesExecutable,
            nativePyronautDevLauncherClasspathJvmArgs(),
            "stop",
            "--project-dir",
            fixtureAppDir.asFile.absolutePath,
        )
        runFixtureCommand(startCommand)
        try {
            val testResourcesEnv = readFixtureTestResourcesEnvironment(fixtureTestResourcesSettingsFile.asFile)
            runFixtureCommand(buildPyronautTestCommand(testResourcesEnv), testResourcesEnv)
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
