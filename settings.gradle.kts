import me.champeau.gradle.igp.gitRepositories
import org.gradle.api.GradleException
import org.gradle.api.initialization.ConfigurableIncludedBuild
import java.io.ByteArrayOutputStream

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
    id("io.micronaut.build.shared.settings") version "8.0.1"
}

fun requiredGradleProperty(name: String): String =
    providers.gradleProperty(name).orNull.takeIfNotBlank()
        ?: throw GradleException("Missing required Gradle property '$name'")

fun String.isSnapshotVersion(): Boolean = endsWith("-SNAPSHOT")

fun booleanGradleProperty(name: String): Boolean? {
    val value = providers.gradleProperty(name).orNull ?: return null
    return value.toBooleanStrictOrNull()
        ?: throw GradleException("Expected Gradle property '$name' to be 'true' or 'false' but got '$value'")
}

fun String?.takeIfNotBlank(): String? = this?.takeIf { it.isNotBlank() }

fun configuredValue(gradlePropertyName: String, vararg environmentNames: String): String? {
    providers.gradleProperty(gradlePropertyName).orNull.takeIfNotBlank()?.let { return it }
    environmentNames.forEach { name ->
        providers.environmentVariable(name).orNull.takeIfNotBlank()?.let { return it }
    }
    return null
}

fun runCommand(workingDir: java.io.File, command: List<String>, displayCommand: List<String> = command): String {
    val output = ByteArrayOutputStream()
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.copyTo(output)
    if (process.waitFor() != 0) {
        throw GradleException(
            buildString {
                append("Command failed in ${workingDir.absolutePath}: ${displayCommand.joinToString(" ")}")
                val text = output.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    append("\n$text")
                }
            }
        )
    }
    return output.toString(Charsets.UTF_8).trim()
}

fun runCommand(workingDir: java.io.File, vararg command: String): String =
    runCommand(workingDir, command.toList())

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

fun ConfigurableIncludedBuild.substituteMicronautCore() {
    dependencySubstitution {
        listOf(
            "aop",
            "buffer-netty",
            "context",
            "context-propagation",
            "context-python",
            "context-python-netty",
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

val micronautVersion = requiredGradleProperty("pyronaut.micronaut.core.version")
val micronautPlatformVersion = requiredGradleProperty("pyronaut.micronaut.platform.version")
val includeMicronautCore = booleanGradleProperty("pyronaut.include.micronaut.core") ?: micronautVersion.isSnapshotVersion()
val privateGitToken = configuredValue("pyronaut.git.token", "PYRONAUT_GIT_TOKEN", "GH_TOKEN")
val gitHubUsername = configuredValue("pyronaut.git.username", "PYRONAUT_GIT_USERNAME", "GH_USERNAME") ?: "x-access-token"


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
            useGitCli = privateGitToken == null
            privateGitToken?.let { token ->
                defaultAuthentication {
                    basic {
                        username.set(gitHubUsername)
                        password.set(token)
                    }
                }
            }
            include("micronaut-core-python") {
                uri.set("https://github.com/graemerocher/micronaut-core.git")
                branch.set("python-support")
                includeBuild {
                    name = "micronaut-core"
                    substituteMicronautCore()
                }
            }
        }
    }
}

providers.gradleProperty("local.git.micronaut-data").orNull.takeIfNotBlank()?.let { localOverride ->
    val localDir = file(localOverride)
    if (!isUsableGitCheckout(localDir)) {
        throw GradleException("Configured local.git.micronaut-data path is not a valid git checkout: ${localDir.absolutePath}")
    }
    includeBuild(localDir) {
        name = "micronaut-data"
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
include("pyronaut-build-annotations")
include("pyronaut-direct-source")
include("pyronaut-install")
include("pyronaut-processor")
include("pyronaut-dev")
include("pyronaut-run")
include("pyronaut-test")
include("pyronaut-create-app")
include("pyronaut-native-build")
include("pyronaut-validate-config")
include("pyronaut-test-resources-server")
include("pyronaut-tui")
include("pyronaut-vscode")
include("pyronaut-projectgen")
include("pyronaut-projectgen-app")
include("pyronaut-pytest")
include("pyronaut-bom")
include("pyronaut-logging")
include("pyronaut-logback")
include("pyronaut-requests")
include("test-serialization")
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
        create("libs") {
            version("micronaut", micronautVersion)
            version("micronaut-platform", micronautPlatformVersion)
        }
        create("mn") {
            from(files("gradle/mn.libs.versions.toml"))
            version("micronaut", micronautVersion)
        }
    }
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}
