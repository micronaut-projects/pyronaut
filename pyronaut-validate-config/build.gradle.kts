plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation("io.micronaut.jsonschema:micronaut-json-schema-configuration-validator:2.0.0-M4")

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.validateconfig.PyronautValidateConfigMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-validate-config"
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-validate-config")
            sharedLibrary.set(false)
        }
    }
}
