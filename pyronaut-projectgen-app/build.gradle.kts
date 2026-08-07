plugins {
    id("io.micronaut.minimal.application") version "4.6.1"
    id("com.gradleup.shadow") version "8.3.9"
}
version = "0.1"
group = "io.micronaut.pyronaut.starter"
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content {
            excludeGroupByRegex("io\\.micronaut\\.projectgen")
        }
        mavenContent {
            snapshotsOnly()
        }
    }
}
dependencies {
    implementation(platform(libs.micronaut.projectgen))
    implementation(libs.micronaut.projectgen.http.server)
    implementation(projects.micronautPyronautProjectgen)
    testImplementation(libs.micronaut.projectgen.test)
    implementation(mnViews.micronaut.views.thymeleaf)
    annotationProcessor(mnSerde.micronaut.serde.processor)
    implementation(mnSerde.micronaut.serde.jackson)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor(mnValidation.micronaut.validation.processor)
    implementation(mnValidation.micronaut.validation)
    implementation(mn.micronaut.management)
    runtimeOnly(mnLogging.logback.classic)

    testImplementation(mn.micronaut.http.client)
}
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.micronaut.projectgen") {
            useVersion("0.0.9")
        }
    }
}
application {
    mainClass = "io.micronaut.pyronaut.starter.Application"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    isZip64 = true
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}
micronaut {
    version(libs.versions.micronaut.platform.get())
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.micronaut.pyronaut.starter")
    }
}
