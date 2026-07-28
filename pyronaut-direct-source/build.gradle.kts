plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    api(project(":micronaut-pyronaut-build-annotations"))
    api(mn.micronaut.inject.python)
    implementation(mn.micronaut.context.python)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}
