
plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(libs.micronaut.json.schema.configuration.validator)
    implementation(mnSerde.micronaut.serde.jackson)
    implementation(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(mn.micronaut.inject.java)
    testRuntimeOnly(mn.micronaut.context)
    testRuntimeOnly(mn.micronaut.http.server)
}

application {
    mainClass = "io.micronaut.pyronaut.validateconfig.PyronautValidateConfigMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-validate-config"
    }
}
