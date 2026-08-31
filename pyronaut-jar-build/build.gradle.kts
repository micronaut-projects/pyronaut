plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    implementation(mnPicocli.picocli)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-run"))
}

application {
    mainClass = "io.micronaut.pyronaut.jarbuild.PyronautJarBuildMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-jar-build"
    }
}
