plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)

    api(mn.micronaut.core)
    api(mn.micronaut.context)
    api(mn.micronaut.http.client)
    implementation(mnSerde.micronaut.serde.jackson) // ensure JsonMapper implementation is present

    // Test dependencies similar to other modules when needed
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mn.micronaut.context.python)
    testImplementation(mn.graalpy) { artifact { type = "pom" } }
    testImplementation(mn.graalpy.embedding)
    testImplementation(mnTest.junit.jupiter.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val pyEnv = providers.environmentVariable("PYENV_VERSION")
    val vEnv = providers.environmentVariable("VIRTUAL_ENV")
    if (pyEnv.isPresent() && vEnv.isPresent()) {
        environment("PYENV_VERSION", pyEnv.get())
        environment("VIRTUAL_ENV", vEnv.get())
    } else {
        enabled = false
    }
}
