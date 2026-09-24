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
    id("io.micronaut.build.shared.settings") version "8.0.1"
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
