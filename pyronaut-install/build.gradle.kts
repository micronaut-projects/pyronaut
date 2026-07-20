plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnSerde.micronaut.serde.jackson)
    implementation(mnPicocli.picocli)

    implementation(libs.maven.resolver.api)
    implementation(libs.maven.resolver.util)
    implementation(libs.maven.resolver.impl)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.file)
    implementation(libs.maven.resolver.transport.jdk)
    implementation(libs.maven.resolver.supplier.mvn3)
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(libs.tomlj)
    implementation(libs.javaparser.core)

    implementation(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.install.PyronautInstallMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-install"
    }

}
