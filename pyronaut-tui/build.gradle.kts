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
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    annotationProcessor(libs.tamboui.annotation.processor)

    implementation(mnPicocli.picocli)

    implementation(libs.tamboui)
    implementation(libs.tamboui.annotations)

    runtimeOnly(libs.tamboui.jline)

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
