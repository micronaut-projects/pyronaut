import org.gradle.api.attributes.java.TargetJvmVersion
plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    // Direct-source processing must resolve the public @pyronaut.build.Dependency
    // annotation from the installed processor distribution.
    implementation(project(":micronaut-pyronaut-build-annotations"))
    implementation(libs.micronaut.toml)
    implementation(mnPicocli.picocli)
    implementation(mn.micronaut.context.python)
    implementation(mn.micronaut.inject.python)
    // OpenAPI ADOC conversion is executed while the processor handles
    // generated applications. Keep its parser on the processor classpath,
    // rather than relying on an application-only jar discovered later.
    implementation("io.micronaut.openapi:micronaut-openapi-adoc")

    implementation(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(mn.micronaut.http)
    testImplementation(mn.micronaut.router)
    testRuntimeOnly(libs.micronaut.data.jdbc)
    testRuntimeOnly(libs.micronaut.data.processor)
}

application {
    mainClass = "io.micronaut.pyronaut.processor.PyronautProcessorMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}
tasks {
    startScripts {
        applicationName = "pyronaut-processor"
    }

}

configurations.named("testRuntimeClasspath") {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}
