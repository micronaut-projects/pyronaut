plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    api(project(":micronaut-pyronaut-build-annotations"))
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(project(":micronaut-pyronaut-direct-source"))
    implementation(mnSerde.micronaut.serde.jackson)
    implementation(mnPicocli.picocli)

    implementation(libs.maven.resolver.api)
    implementation(libs.maven.resolver.util)
    implementation(libs.maven.resolver.impl)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.file)
    implementation(libs.maven.resolver.transport.jdk)
    implementation(libs.maven.resolver.supplier.mvn3) {
        exclude(group = "org.apache.maven.resolver", module = "maven-resolver-transport-apache")
    }
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(libs.micronaut.test.resources.core)
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

    processResources {
        filesMatching("META-INF/pyronaut/version.properties") {
            expand("pyronautVersion" to project.version)
        }
    }

}
