plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    implementation(mn.micronaut.context)
    implementation(libs.tomlj)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

val pyronautMicronautCoreVersion = providers.gradleProperty("pyronaut.micronaut.core.version")
val pyronautMicronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")
val pyronautMicronautControlPanelVersion = libs.versions.micronaut.control.panel

tasks.processResources {
    inputs.property("pyronaut.micronaut.core.version", pyronautMicronautCoreVersion)
    inputs.property("pyronaut.micronaut.platform.version", pyronautMicronautPlatformVersion)
    inputs.property("pyronaut.micronaut.control-panel.version", pyronautMicronautControlPanelVersion)
    filesMatching("**/pyronaut-managed-versions.properties") {
        expand(
            mapOf(
                "micronautCoreVersion" to pyronautMicronautCoreVersion.get(),
                "micronautPlatformVersion" to pyronautMicronautPlatformVersion.get(),
                "micronautControlPanelVersion" to pyronautMicronautControlPanelVersion.get(),
            )
        )
    }
}
