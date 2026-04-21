plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(mn.micronaut.http.server)
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(mnLogging.logback.classic)
    runtimeOnly(libs.slf4j.jul.to.slf4j)
    runtimeOnly(mn.micronaut.http.server.netty)

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
