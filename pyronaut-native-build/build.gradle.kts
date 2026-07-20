plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(project(":micronaut-pyronaut-run"))
    implementation(mnPicocli.picocli)
    implementation(libs.graalvm.reachability.metadata)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.nativebuild.PyronautNativeBuildMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-native-build"
    }

    processResources {
        filesMatching("io/micronaut/pyronaut/nativebuild/metadata-version.txt") {
            expand("metadataVersion" to libs.versions.graalvm.reachability.metadata.get())
        }
    }
}
