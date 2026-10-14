plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}
