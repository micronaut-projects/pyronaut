plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

micronautBuild {
    javaVersion = 25
}

repositories {
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
    }
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    annotationProcessor(libs.tamboui.annotation.processor)

    implementation(project(":micronaut-pyronaut-logback"))
    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)

    implementation(libs.tamboui)
    implementation(libs.tamboui.annotations)

    runtimeOnly(libs.tamboui.jline)
    constraints {
        // tamboui-jline3-backend 0.5.0 depends on jline 3.25.1, which is
        // affected by CVE-2026-77420 and CVE-2026-77422 (fixed in 3.30.15).
        runtimeOnly(libs.jline)
    }

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.tui.PyronautTuiMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

tasks.withType<org.gradle.api.plugins.quality.Checkstyle>().configureEach {
    exclude("**/PyronautDelegatingTuiCommand.java")
}

tasks {
    startScripts {
        applicationName = "pyronaut-tui"
    }
    installDist {
        destinationDir = layout.buildDirectory.dir("install/pyronaut-tui").get().asFile
    }
}
