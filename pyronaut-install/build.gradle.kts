import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

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
    implementation(libs.javaparser.core)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.install.PyronautInstallMain"
}

val nativeImageCLibraryPathArgs = providers.provider {
    val javaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() } ?: return@provider emptyList<String>()
    val clibrariesDir = File(javaHome, "lib/svm/clibraries")
    if (!clibrariesDir.isDirectory) {
        return@provider emptyList<String>()
    }
    val osArch = System.getProperty("os.arch").lowercase()
    val platformDir = when {
        osArch.contains("aarch64") || osArch.contains("arm64") -> File(clibrariesDir, "darwin-aarch64")
        else -> null
    }
    buildList {
        platformDir?.takeIf { it.isDirectory }?.let { add(it.absolutePath) }
        add(clibrariesDir.absolutePath)
    }.takeIf { it.isNotEmpty() }
        ?.let { listOf("-H:CLibraryPath=${it.joinToString(",")}") }
        ?: emptyList()
}

val nativeImageRuntimeClassLoadingArgs = listOf(
    "-H:+UnlockExperimentalVMOptions",
    "-H:EnableURLProtocols=jar",
    "-H:+RuntimeClassLoading",
    "-H:+AllowJRTFileSystem",
    "-H:Preserve=package=java.lang.*",
    "-H:Preserve=package=java.lang.invoke.*",
    "-H:Preserve=package=java.text.*",
    "-H:Preserve=package=java.time.*",
    "-H:Preserve=package=java.util.*",
    "-H:Preserve=package=jdk.internal.misc.*",
    "-H:Preserve=package=jdk.internal.access.*",
    "-H:Preserve=package=io.micronaut.core.annotation.*",
    "-H:Preserve=package=io.micronaut.core.beans.*",
    "-H:Preserve=package=io.micronaut.core.naming.*",
    "-H:Preserve=package=io.micronaut.core.reflect.*",
    "-H:Preserve=package=io.micronaut.core.util.*",
    "-H:Preserve=package=io.micronaut.core.io.service.*",
    "-H:Preserve=package=io.micronaut.inject.*",
    "-H:Preserve=package=io.micronaut.context.*",
    "-H:Preserve=package=com.github.javaparser.*",
    "-H:Preserve=package=com.github.javaparser.ast.*",
    "-H:Preserve=package=com.github.javaparser.javadoc.*",
    "-H:Preserve=package=com.github.javaparser.metamodel.*",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "-H:-UnlockExperimentalVMOptions"
)

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
            buildArgs.addAll(nativeImageCLibraryPathArgs)
            buildArgs.addAll(nativeImageRuntimeClassLoadingArgs)
        }
        all {
            resources.autodetect()
        }
    }
}
