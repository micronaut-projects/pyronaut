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
    runtimeOnly(mn.micronaut.http.server.netty)
    runtimeOnly(libs.slf4j.simple)

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
