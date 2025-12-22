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
include("pyronaut-projectgen")
include("pyronaut-projectgen-app")
include("pyronaut-bom")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

micronautBuild {
    useStandardizedProjectNames=true
    importMicronautCatalog()
    importMicronautCatalog("micronaut-picocli")
    importMicronautCatalog("micronaut-serde")
    importMicronautCatalog("micronaut-views")
    importMicronautCatalog("micronaut-validation")
    requiresDevelopmentVersion("micronaut-core", "cc/pyronaut-spike-pyproject")
}
