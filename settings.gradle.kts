pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "8.0.0-M13"
}

rootProject.name = "pyronaut-parent"

include("pyronaut-cli")
include("pyronaut-config-model")
include("pyronaut-install")
include("pyronaut-projectgen")
include("pyronaut-projectgen-app")
include("pyronaut-pytest")
include("pyronaut-bom")
include("pyronaut-logging")
include("pyronaut-logback")
include("pyronaut-requests")

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
