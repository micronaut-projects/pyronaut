import org.gradle.api.GradleException

pluginManagement {
    includeBuild("gradle/graalvm-dev-toolchain")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.graalvm-dev-toolchain")
    id("io.micronaut.build.shared.settings") version "8.1.5"
}

fun requiredGradleProperty(name: String): String =
    providers.gradleProperty(name).orNull.takeIfNotBlank()
        ?: throw GradleException("Missing required Gradle property '$name'")

fun String?.takeIfNotBlank(): String? = this?.takeIf { it.isNotBlank() }

val micronautVersion = requiredGradleProperty("pyronaut.micronaut.core.version")
val micronautPlatformVersion = requiredGradleProperty("pyronaut.micronaut.platform.version")

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
include("pyronaut-run-python")
include("pyronaut-test")
include("pyronaut-native-build")
include("pyronaut-jar-build")
include("pyronaut-validate-config")
include("pyronaut-test-resources-server")
include("pyronaut-tui")
include("pyronaut-vscode")
include("pyronaut-pytest")
include("pyronaut-bom")
include("pyronaut-logging")
include("pyronaut-logback")
include("pyronaut-requests")
include("test-serialization")
include("functional-test")
project(":functional-test").name = "functional-test"
include("functional-test-docker")
project(":functional-test-docker").name = "functional-test-docker"
include("pgo-training")
project(":pgo-training").name = "pgo-training"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

micronautBuild {
    useStandardizedProjectNames=true
    importMicronautCatalog("micronaut-picocli")
    importMicronautCatalog("micronaut-serde")
    importMicronautCatalog("micronaut-logging")
    importMicronautCatalog("micronaut-test")
    importMicronautCatalog("micronaut-openapi")
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
    }
}

if (micronautVersion.endsWith("-SNAPSHOT")) {
    // An unreleased core is built from source: the micronaut-core submodule tracks the core branch of the
    // snapshot, and including its build substitutes its projects for every io.micronaut core module the
    // build resolves. pyronaut.micronaut.core.checkout points the build at another core checkout instead.
    val micronautCoreCheckout = providers.gradleProperty("pyronaut.micronaut.core.checkout").orNull.takeIfNotBlank()
        ?.let { file(it) }
        ?: file("micronaut-core")
    if (!micronautCoreCheckout.resolve("settings.gradle").isFile) {
        throw GradleException(
            "Micronaut core $micronautVersion is built from source, but ${micronautCoreCheckout.absolutePath} " +
                "is not a core checkout: run 'git submodule update --init micronaut-core', or set " +
                "pyronaut.micronaut.core.checkout to a core checkout"
        )
    }
    gradle.extra["pyronaut.micronaut.core.checkout"] = micronautCoreCheckout
    includeBuild(micronautCoreCheckout) {
        name = "micronaut-core"
    }
}
