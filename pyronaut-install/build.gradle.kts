import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnSerde.micronaut.serde.jackson)
    implementation(mnPicocli.picocli)

    implementation(libs.maven.resolver.api)
    implementation(libs.maven.resolver.util)
    implementation(libs.maven.resolver.impl)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.file)
    implementation(libs.maven.resolver.transport.jdk)
    implementation(libs.maven.resolver.supplier.mvn3)
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(libs.tomlj)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.install.PyronautInstallMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-install"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-install native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty(
                "pyronaut.install.native.binary",
                nativeCompileTask.get().outputFile.get().asFile.absolutePath
            )
        }
        include("**/PyronautInstallNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
    }
}

graalvmNative {
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("run")
            outputDirectories.add("src/main/resources/META-INF/native-image/io.micronaut/micronaut-pyronaut-install")
            mergeWithExisting = true
        }
    }
    binaries {
        named("main") {
            imageName.set("pyronaut-install")
            sharedLibrary.set(false)
        }
        all {
            resources.autodetect()
        }
    }
}
