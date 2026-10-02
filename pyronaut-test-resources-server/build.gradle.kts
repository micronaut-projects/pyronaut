
plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(libs.micronaut.control.panel.core)
//    implementation(mn.micronaut.http.server)
    implementation(libs.micronaut.test.resources.build.tools)
//    implementation(libs.micronaut.test.resources.core)
    compileOnly(libs.micronaut.test.resources.core)
    compileOnly(libs.micronaut.test.resources.control.panel) {
        // The server does not render Handlebars templates, so it does not
        // need the optional Nashorn JavaScript engine pulled by the control
        // panel UI.
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
    }
//    implementation(libs.micronaut.test.resources.server)
    compileOnly(libs.micronaut.test.resources.server)
//    implementation(mnSerde.micronaut.serde.jackson)
//    implementation(mnLogging.logback.classic)
    compileOnly(mnLogging.logback.classic)
    // ServerUtils logs while the launcher starts, connects to, or stops the
    // separately resolved test-resources server. Keep the server runtime
    // dependencies install-time resolved, but provide a backend for the
    // launcher JVM itself.
    runtimeOnly(mnLogging.logback.classic)
//    runtimeOnly(libs.slf4j.jul.to.slf4j)
//    runtimeOnly(mn.micronaut.http.server.netty)

    testImplementation(libs.docker.java.api)
    testImplementation(libs.micronaut.test.resources.core)
    testImplementation(libs.micronaut.test.resources.control.panel) {
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
    }
    testImplementation(libs.micronaut.test.resources.server)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

configurations.testRuntimeClasspath {
    // micronaut-test-resources-server is a fat jar that embeds its own
    // logback-classic and logback-core. A separate logback on the test
    // classpath mixes classes from two logback versions (the build aligns
    // logback to 1.6.x for CVE-2026-19880), so tests use the embedded copy.
    exclude(group = "ch.qos.logback")
}

application {
    mainClass = "io.micronaut.pyronaut.testresources.PyronautTestResourcesServerMain"
}

val runtimeMetadataExclusion = providers.provider {
    val excludedArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .filter { artifact ->
            val group = artifact.moduleVersion.id.group
            val name = artifact.name
            group == "io.netty" ||
                name == "micronaut-test-resources-server" ||
                name == "micronaut-test-resources-control-panel" ||
                name == "micronaut-control-panel-core" ||
                name == "micronaut-control-panel-ui" ||
                name == "micronaut-http-server" ||
                name == "micronaut-http-server-netty" ||
                name == "micronaut-http-netty" ||
                name == "micronaut-buffer-netty" ||
                name == "reactor-core" ||
                name == "testcontainers"
        }
        .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
    buildList {
        excludedArtifacts.forEach { jar ->
            add("--exclude-config")
            add("\\Q$jar\\E")
            add("^/META-INF/native-image/.*")
        }
    }
}

tasks {
    startScripts {
        applicationName = "pyronaut-test-resources-server"
    }
}
