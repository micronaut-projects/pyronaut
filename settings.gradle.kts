import me.champeau.gradle.igp.gitRepositories
import org.gradle.api.GradleException
import org.gradle.api.initialization.ConfigurableIncludedBuild
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile

pluginManagement {
    includeBuild("gradle/graalvm-dev-toolchain")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("me.champeau.includegit") version "0.3.2"
    id("io.micronaut.build.graalvm-dev-toolchain")
    id("io.micronaut.build.shared.settings") version "8.0.0-M13"
}

fun versionFromCatalog(path: String, key: String): String {
    val file = layout.settingsDirectory.file(path).asFile
    val pattern = Regex("""^${Regex.escape(key)}\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    return pattern.find(file.readText())?.groupValues?.get(1)
        ?: throw GradleException("Unable to find version '$key' in ${file.absolutePath}")
}

fun String.isSnapshotVersion(): Boolean = endsWith("-SNAPSHOT")

fun booleanGradleProperty(name: String): Boolean? {
    val value = providers.gradleProperty(name).orNull ?: return null
    return value.toBooleanStrictOrNull()
        ?: throw GradleException("Expected Gradle property '$name' to be 'true' or 'false' but got '$value'")
}

fun runCommand(workingDir: java.io.File, vararg command: String): String {
    val output = ByteArrayOutputStream()
    val process = ProcessBuilder(*command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.copyTo(output)
    if (process.waitFor() != 0) {
        throw GradleException(
            buildString {
                append("Command failed in ${workingDir.absolutePath}: ${command.joinToString(" ")}")
                val text = output.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    append("\n$text")
                }
            }
        )
    }
    return output.toString(Charsets.UTF_8).trim()
}

fun mavenVersionDirReady(versionDir: java.io.File, artifactId: String): Boolean {
    if (!versionDir.isDirectory) {
        return false
    }
    if (versionDir.resolve("maven-metadata.xml").isFile || versionDir.resolve("maven-metadata-local.xml").isFile) {
        return true
    }
    val pomFile = versionDir.resolve("$artifactId-${versionDir.name}.pom")
    if (pomFile.isFile) {
        return true
    }
    return versionDir.listFiles()?.any { candidate ->
        candidate.isFile && (candidate.extension == "pom" || candidate.extension == "jar")
    } == true
}

fun graalPyBundleReady(repoDir: java.io.File, graalpyVersion: String): Boolean {
    val versionDir = repoDir.resolve("org/graalvm/python/python/$graalpyVersion")
    return mavenVersionDirReady(versionDir, "python")
}

fun graalPyExtensionsReady(repoDir: java.io.File, graalpyVersion: String): Boolean {
    val pythonVersionDir = repoDir.resolve("org/graalvm/python/python/$graalpyVersion")
    val embeddingVersionDir = repoDir.resolve("org/graalvm/python/python-embedding/$graalpyVersion")
    val embeddingToolsVersionDir = repoDir.resolve("org/graalvm/python/python-embedding-tools/$graalpyVersion")
    return mavenVersionDirReady(pythonVersionDir, "python") &&
        mavenVersionDirReady(embeddingVersionDir, "python-embedding") &&
        mavenVersionDirReady(embeddingToolsVersionDir, "python-embedding-tools")
}

fun isValidZip(file: java.io.File): Boolean {
    if (!file.isFile || file.length() == 0L) {
        return false
    }
    return try {
        ZipFile(file).use { zip ->
            zip.entries().hasMoreElements()
        }
    } catch (e: Exception) {
        false
    }
}

fun resolveGraalPyBundleUrl(graalpyVersion: String): String {
    providers.gradleProperty("pyronaut.graalpy.bundle.url").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val releaseVersion = graalpyVersion.removeSuffix("-SNAPSHOT")
    val releasesJson = runCommand(
        layout.settingsDirectory.asFile,
        "curl",
        "-sSL",
        "https://api.github.com/repos/graalvm/oracle-graalvm-ea-builds/releases"
    )
    val pattern = Regex(
        """https://github\.com/graalvm/oracle-graalvm-ea-builds/releases/download/[^"\s]+/maven-resource-bundle-${Regex.escape(releaseVersion)}[^"\s]*\.zip"""
    )
    return pattern.find(releasesJson)?.value
        ?: throw GradleException("Unable to find a GraalPy Maven bundle release for version $graalpyVersion")
}

fun isUsableGitCheckout(directory: java.io.File): Boolean {
    if (!directory.isDirectory || !directory.resolve(".git").exists()) {
        return false
    }
    return try {
        runCommand(directory, "git", "rev-parse", "--verify", "HEAD")
        true
    } catch (e: GradleException) {
        false
    }
}

fun deleteRecursively(target: java.io.File) {
    if (!target.exists()) {
        return
    }
    Files.walk(target.toPath())
        .sorted(java.util.Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}

fun ensureGraalPyExtensionsCheckout(): java.io.File {
    val localOverride = providers.gradleProperty("local.git.graalpy-extensions").orNull
    if (!localOverride.isNullOrBlank()) {
        val localDir = file(localOverride)
        if (!isUsableGitCheckout(localDir)) {
            throw GradleException("Configured local.git.graalpy-extensions path is not a valid git checkout: ${localDir.absolutePath}")
        }
        return localDir
    }
    val checkoutDir = layout.settingsDirectory.dir("checkouts/graalpy-extensions").asFile
    if (isUsableGitCheckout(checkoutDir)) {
        return checkoutDir
    }
    deleteRecursively(checkoutDir)
    checkoutDir.parentFile.mkdirs()
    runCommand(
        layout.settingsDirectory.asFile,
        "git",
        "clone",
        "--depth",
        "1",
        "--branch",
        "main",
        "https://github.com/oracle/graalpy-extensions.git",
        checkoutDir.absolutePath
    )
    return checkoutDir
}

fun ensureGraalPyBundleRepo(checkoutDir: java.io.File, graalpyVersion: String): java.io.File {
    val explicitRepo = providers.gradleProperty("pyronaut.graalpy.bundle.repo").orNull
    if (!explicitRepo.isNullOrBlank()) {
        val repoDir = file(explicitRepo)
        if (!graalPyExtensionsReady(repoDir, graalpyVersion)) {
            throw GradleException(
                "Configured pyronaut.graalpy.bundle.repo does not contain GraalPy embedding artifacts for $graalpyVersion: ${repoDir.absolutePath}"
            )
        }
        return repoDir
    }

    val checkoutSha = runCommand(checkoutDir, "git", "rev-parse", "--verify", "HEAD").lineSequence().last().trim()
    val repoDir = layout.settingsDirectory.dir("checkouts/maven-bundles/graalpy/$checkoutSha").asFile
    if (graalPyExtensionsReady(repoDir, graalpyVersion)) {
        return repoDir
    }

    repoDir.mkdirs()
    val bundleUrl = resolveGraalPyBundleUrl(graalpyVersion)
    val zipFile = layout.settingsDirectory.file("checkouts/maven-bundles/graalpy/$checkoutSha.zip").asFile
    zipFile.parentFile.mkdirs()
    val leftoverZip = checkoutDir.resolve("maven-resource-bundle.zip")
    if (isValidZip(leftoverZip) && !zipFile.exists()) {
        Files.move(leftoverZip.toPath(), zipFile.toPath())
    }
    if (!isValidZip(zipFile)) {
        zipFile.delete()
        runCommand(layout.settingsDirectory.asFile, "curl", "-L", "-o", zipFile.absolutePath, bundleUrl)
    }
    if (!graalPyBundleReady(repoDir, graalpyVersion)) {
        runCommand(layout.settingsDirectory.asFile, "unzip", "-q", "-o", zipFile.absolutePath, "-d", repoDir.absolutePath)
    }
    zipFile.delete()

    if (!graalPyBundleReady(repoDir, graalpyVersion)) {
        throw GradleException(
            "GraalPy bundle bootstrap completed without publishing runtime artifacts for $graalpyVersion to ${repoDir.absolutePath}"
        )
    }

    if (!graalPyExtensionsReady(repoDir, graalpyVersion)) {
        runCommand(
            checkoutDir,
            "./mvnw",
            "-pl",
            "org.graalvm.python.embedding,org.graalvm.python.embedding.tools",
            "-am",
            "install",
            "-Dmaven.test.skip=true",
            "-DskipTests",
            "-DskipITs",
            "-DskipSigtest=true",
            "-Dspotbugs.skip=true",
            "-Dcheckstyle.skip=true",
            "-Dmaven.javadoc.skip=true",
            "-Dmaven.repo.local=${repoDir.absolutePath}",
            "-Dlocal.repo.url=${repoDir.toURI()}"
        )
    }

    if (!graalPyExtensionsReady(repoDir, graalpyVersion)) {
        throw GradleException(
            "GraalPy extensions bootstrap completed without publishing embedding artifacts for $graalpyVersion to ${repoDir.absolutePath}"
        )
    }
    return repoDir
}

fun ConfigurableIncludedBuild.substituteMicronautCore() {
    dependencySubstitution {
        listOf(
            "aop",
            "buffer-netty",
            "context",
            "context-propagation",
            "context-python",
            "core",
            "core-bom" to "micronaut-core-bom",
            "core-processor",
            "core-reactive",
            "discovery-core",
            "function",
            "function-client",
            "function-web",
            "graal",
            "http",
            "http-client",
            "http-client-core",
            "http-client-jdk",
            "http-netty",
            "http-netty-http3",
            "http-server",
            "http-server-netty",
            "http-validation",
            "inject",
            "inject-groovy",
            "inject-java",
            "inject-kotlin",
            "inject-python",
            "jackson-core",
            "jackson-databind",
            "json-core",
            "management",
            "messaging",
            "module-info",
            "module-info-runtime",
            "retry",
            "router",
            "runtime",
            "runtime-osx",
            "websocket"
        ).map { entry ->
            when (entry) {
                is Pair<*, *> -> entry.first.toString() to entry.second.toString()
                else -> entry.toString() to "micronaut-${entry}"
            }
        }.forEach { (_, moduleName) ->
            substitute(module("io.micronaut:$moduleName"))
                .using(project(":$moduleName"))
        }
    }
}

val micronautVersion = versionFromCatalog("gradle/libs.versions.toml", "micronaut")
val graalpyVersion = versionFromCatalog("gradle/libs.versions.toml", "graalpy")
val useSnapshotSourceDependencies = micronautVersion.isSnapshotVersion() && graalpyVersion.isSnapshotVersion()

val includeMicronautCore = booleanGradleProperty("pyronaut.include.micronaut.core") ?: useSnapshotSourceDependencies
val includeGraalPyExtensions = booleanGradleProperty("pyronaut.include.graalpy.extensions") ?: useSnapshotSourceDependencies

val graalPyBundleRepo = if (includeGraalPyExtensions) {
    ensureGraalPyBundleRepo(ensureGraalPyExtensionsCheckout(), graalpyVersion).also {
        System.setProperty("pyronaut.graalpy.bundle.repo", it.absolutePath)
        System.setProperty("maven.repo.local", it.absolutePath)
    }
} else {
    null
}

if (includeMicronautCore) {
    val localOverride = providers.gradleProperty("local.git.micronaut-core").orNull
    if (!localOverride.isNullOrBlank()) {
        val localDir = file(localOverride)
        if (!isUsableGitCheckout(localDir)) {
            throw GradleException("Configured local.git.micronaut-core path is not a valid git checkout: ${localDir.absolutePath}")
        }
        includeBuild(localDir) {
            name = "micronaut-core"
            substituteMicronautCore()
        }
    } else {
        gitRepositories {
            useGitCli = true
            include("micronaut-core-python") {
                uri.set("https://github.com/graemerocher/micronaut-core.git")
                branch.set("python-ast-experiments")
                includeBuild {
                    name = "micronaut-core"
                    substituteMicronautCore()
                }
            }
        }
    }
}

toolchainManagement {
    jvm {
        javaRepositories {
            repository("graalvmCeDevBuilds") {
                resolverClass.set(io.micronaut.build.GraalVmDevBuildToolchainResolver::class.java)
            }
        }
    }
}

rootProject.name = "pyronaut-parent"

include("pyronaut")
include("pyronaut-config-model")
include("pyronaut-install")
include("pyronaut-processor")
include("pyronaut-run")
include("pyronaut-test")
include("pyronaut-native-build")
include("pyronaut-validate-config")
include("pyronaut-test-resources-server")
include("pyronaut-tui")
include("pyronaut-projectgen")
include("pyronaut-projectgen-app")
include("pyronaut-pytest")
include("pyronaut-bom")
include("pyronaut-logging")
include("pyronaut-logback")
include("pyronaut-requests")
include("functional-test")
project(":functional-test").name = "functional-test"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

micronautBuild {
    useStandardizedProjectNames=true
    importMicronautCatalog("micronaut-picocli")
    importMicronautCatalog("micronaut-serde")
    importMicronautCatalog("micronaut-logging")
    importMicronautCatalog("micronaut-test")
    importMicronautCatalog("micronaut-views")
    importMicronautCatalog("micronaut-validation")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mn") {
            from(files("gradle/mn.libs.versions.toml"))
        }
    }
    repositories {
        graalPyBundleRepo?.let {
            maven(it.toURI()) {
                name = "graalPyBundle"
            }
        }
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}
