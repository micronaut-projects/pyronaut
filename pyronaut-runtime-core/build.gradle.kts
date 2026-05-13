plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    api(mn.micronaut.context)
    api(mn.micronaut.json.core)
    api(mnSerde.micronaut.serde.api)
    runtimeOnly(mn.micronaut.toml)
    runtimeOnly(mnSerde.micronaut.serde.jackson)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}
