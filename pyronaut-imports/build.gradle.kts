plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    // the PythonImportMapper SPI: every tool that loads the facades already has micronaut-inject-python
    compileOnly(mn.micronaut.inject.python)

    testImplementation(mn.micronaut.inject.python)

    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
    // the libraries the facades gather, so the snapshots resolve every facade
    testRuntimeOnly(mn.micronaut.http)
    testRuntimeOnly(mn.micronaut.http.client.core)
    testRuntimeOnly(mn.micronaut.http.server)
    testRuntimeOnly(mn.micronaut.context)
    testRuntimeOnly(mn.micronaut.context.python)
    testRuntimeOnly(mn.micronaut.retry)
    testRuntimeOnly(mn.micronaut.management)
    testRuntimeOnly(mnSerde.micronaut.serde.api)
    testRuntimeOnly(mnValidation.micronaut.validation)
    testRuntimeOnly(mnOpenapi.micronaut.openapi.annotations)
    testRuntimeOnly(mnViews.micronaut.views.core)
    testRuntimeOnly(libs.micronaut.data.model)
    testRuntimeOnly(libs.micronaut.data.jdbc)
    testRuntimeOnly(libs.micronaut.cache.core)
    testRuntimeOnly("io.micronaut.security:micronaut-security")
    testRuntimeOnly("io.micronaut.data:micronaut-data-tx")
    testRuntimeOnly("jakarta.transaction:jakarta.transaction-api")
    testRuntimeOnly("io.swagger.core.v3:swagger-annotations")
    testRuntimeOnly("io.micronaut.security:micronaut-security-jwt")
    testRuntimeOnly("io.micronaut.security:micronaut-security-oauth2")
    testRuntimeOnly("io.projectreactor:reactor-core")
    testRuntimeOnly("io.micronaut.kafka:micronaut-kafka")
    testRuntimeOnly("io.micronaut.rabbitmq:micronaut-rabbitmq")
    testRuntimeOnly("io.micronaut.jms:micronaut-jms-core")
    testRuntimeOnly("io.micronaut.mqtt:micronaut-mqtt-core")
    testRuntimeOnly("io.micronaut.email:micronaut-email")
    testRuntimeOnly("io.micronaut.email:micronaut-email-template")
    testRuntimeOnly("io.micronaut.email:micronaut-email-javamail")
    testRuntimeOnly("io.micronaut:micronaut-websocket")
    testRuntimeOnly("io.micronaut.tracing:micronaut-tracing-annotation")
    testRuntimeOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations")
    testRuntimeOnly("io.micrometer:micrometer-core")
    testRuntimeOnly("io.micronaut.objectstorage:micronaut-object-storage-core")
    testRuntimeOnly("io.micronaut.graphql:micronaut-graphql")
    testRuntimeOnly("io.micronaut.mcp:micronaut-mcp-annotations")
}

tasks.withType<Test>().configureEach {
    systemProperty("pyronaut.facades.snapshots", layout.projectDirectory.dir("src/test/resources/facades").asFile.absolutePath)
    providers.systemProperty("pyronaut.facades.update").orNull?.let { systemProperty("pyronaut.facades.update", it) }
}
