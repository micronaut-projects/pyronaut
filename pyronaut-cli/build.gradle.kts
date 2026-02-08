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
    compileOnly(mn.micronaut.context)
    compileOnly(mnTest.junit.platform.launcher)
    implementation(libs.tomlj)
    implementation(libs.gradle.tapi)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    runtimeOnly(libs.tamboui.panama)
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
