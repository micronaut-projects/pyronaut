plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    api(project(":micronaut-pyronaut-build-annotations"))
    api(mn.micronaut.inject.python)
    // the curated modules (from pyronaut import http, ...) direct sources may import
    api(project(":micronaut-pyronaut-imports"))
    implementation(mn.micronaut.context.python)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}
