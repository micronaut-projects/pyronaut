plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("io.micronaut.build.internal.python")
}

dependencies {
    implementation(mn.micronaut.context.python)
    implementation(mn.micronaut.inject.python)
    implementation(mn.micronaut.json.core)
    implementation(mnSerde.micronaut.serde.jackson)
    implementation("io.micronaut.serde:micronaut-serde-api")
    compileOnly(mnSerde.micronaut.serde.processor)

    testImplementation(mn.micronaut.context.python)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mn.micronaut.inject.python)
    testImplementation(mn.micronaut.json.core)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation("io.micronaut.serde:micronaut-serde-api")
    testCompileOnly(mnSerde.micronaut.serde.processor)
    testImplementation(mnTest.junit.jupiter.api)
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
