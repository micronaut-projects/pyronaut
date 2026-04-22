plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(libs.micronaut.control.panel.core)
    implementation(mn.micronaut.http.server)
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(libs.micronaut.test.resources.core)
    implementation(libs.micronaut.test.resources.control.panel)
    implementation(libs.micronaut.test.resources.server)
    implementation(mnLogging.logback.classic)
    runtimeOnly(libs.slf4j.jul.to.slf4j)
    runtimeOnly(mn.micronaut.http.server.netty)

    testImplementation(libs.docker.java.api)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.testresources.PyronautTestResourcesServerMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-test-resources-server"
    }
}
