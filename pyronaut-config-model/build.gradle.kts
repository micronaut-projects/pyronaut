plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    implementation(libs.tomlj)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}
