plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(platform(libs.micronaut.projectgen))
    implementation(libs.micronaut.projectgen.core)
    implementation(project(":micronaut-pyronaut-projectgen"))
    implementation(mn.micronaut.context)
    implementation(mnPicocli.picocli)

    runtimeOnly(mnSerde.micronaut.serde.jackson)
    implementation(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.createapp.PyronautCreateAppMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-create"
    }
}

micronautBuild {
    binaryCompatibility {
        enabled = false
    }
}
