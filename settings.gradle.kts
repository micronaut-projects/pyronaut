pluginManagement {
    includeBuild("gradle/graalvm-dev-toolchain")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.graalvm-dev-toolchain")
    id("io.micronaut.build.shared.settings") version "8.0.0-M13"
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
    importMicronautCatalog()
    importMicronautCatalog("micronaut-picocli")
    importMicronautCatalog("micronaut-serde")
    importMicronautCatalog("micronaut-logging")
    importMicronautCatalog("micronaut-views")
    importMicronautCatalog("micronaut-validation")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
