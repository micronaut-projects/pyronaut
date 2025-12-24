plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
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
    implementation(mnPicocli.picocli)
    implementation(libs.tamboui)
    compileOnly(mn.micronaut.context)
    compileOnly(mnTest.junit.platform.launcher)
    implementation(libs.tomlj)
    implementation(libs.gradle.tapi)
    runtimeOnly(mnLogging.logback.classic)
    runtimeOnly(libs.tamboui.jline)
}

application {
    mainClass = "io.micronaut.python.cli.PyronautMainCommand"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

tasks {
    startScripts {
        applicationName = "pyronaut"
    }
    installDist {
        destinationDir = layout.buildDirectory.dir("install/pyronaut").get().asFile
    }
}
