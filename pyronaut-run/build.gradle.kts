import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native") version "0.11.1"
}

val micronautCoreNativeImageExclusion = providers.provider {
    val micronautCoreJar = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .firstOrNull { artifact ->
            artifact.moduleVersion.id.group == "io.micronaut" &&
                artifact.name == "micronaut-core" &&
                artifact.extension == "jar"
        }
        ?.file
        ?: error("Unable to resolve micronaut-core runtime jar for native-image exclusion")
    listOf(
        "--exclude-config",
        "\\Q${micronautCoreJar.toPath().toAbsolutePath().normalize()}\\E",
        "^/META-INF/native-image/.*"
    )
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(mn.micronaut.context)
    implementation(mn.micronaut.context.python)
    implementation(mnPicocli.picocli)
    runtimeOnly(mn.micronaut.http.server)
    runtimeOnly(mn.micronaut.http.server.netty)
    runtimeOnly("io.micronaut:micronaut-discovery-core")
    runtimeOnly(mn.micronaut.json.core)
    runtimeOnly(mnSerde.micronaut.serde.jackson)
    runtimeOnly("io.micronaut.serde:micronaut-serde-api")
    runtimeOnly(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-processor"))
    testRuntimeOnly(mn.micronaut.http.server)
    testRuntimeOnly(mn.micronaut.http.server.netty)
    testRuntimeOnly("io.micronaut:micronaut-discovery-core")
    testRuntimeOnly(mn.micronaut.json.core)
    testRuntimeOnly(mn.micronaut.jackson.databind)
    testRuntimeOnly(project(":micronaut-pyronaut-logback"))
}

application {
    mainClass = "io.micronaut.pyronaut.run.PyronautRunMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
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
    "--enable-native-access=org.graalvm.truffle",
    "-H:+UnlockExperimentalVMOptions",
    "-H:EnableURLProtocols=jar",
    "-H:+RuntimeClassLoading",
    "-H:+AllowJRTFileSystem",
    "--initialize-at-build-time=io.micronaut.core.io",
    "--initialize-at-build-time=io.micronaut.core.optim",
    "--initialize-at-build-time=io.micronaut.core.util",
    "--initialize-at-build-time=io.micronaut.core.bind",
    "--initialize-at-build-time=io.micronaut.core.convert",
    "--initialize-at-build-time=io.micronaut.core.convert.ConversionContext",
    "--initialize-at-build-time=io.micronaut.core.convert.ImmutableArgumentConversionContext",
    "--initialize-at-build-time=io.micronaut.core.type",
    "--initialize-at-build-time=io.micronaut.core.annotation",
    "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValue",
    "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValueResolver",
    "--initialize-at-build-time=io.micronaut.core.reflect.ReflectionUtils",
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
    "-H:Preserve=package=io.micronaut.core.type.*",
    "-H:Preserve=package=io.micronaut.core.util.*",
    "-H:Preserve=package=io.micronaut.core.io.service.*",
    "-H:Preserve=package=io.micronaut.inject.*",
    "-H:Preserve=package=io.micronaut.context.*",
    "-H:Preserve=package=io.micronaut.runtime.*",
    "-H:Preserve=package=io.micronaut.http.*",
    "-H:Preserve=package=io.micronaut.web.router.*",
    "-H:Preserve=package=io.micronaut.pyronaut.logback.*",
    "-H:Preserve=package=org.slf4j.*",
    "-H:Preserve=package=ch.qos.logback.classic.*",
    "-H:Preserve=package=ch.qos.logback.core.*",
    "-H:-PrintRestrictHeapAccessWarnings",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
    "-H:-UnlockExperimentalVMOptions"
)

tasks {
    startScripts {
        applicationName = "pyronaut-run"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-run native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty(
                "pyronaut.run.native.binary",
                nativeCompileTask.get().outputFile.get().asFile.absolutePath
            )
        }
        include("**/PyronautRunNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-run")
            sharedLibrary.set(false)
            buildArgs.addAll(nativeImageCLibraryPathArgs)
            buildArgs.addAll(nativeImageRuntimeClassLoadingArgs)
            buildArgs.addAll(micronautCoreNativeImageExclusion)
        }
        all {
            resources.autodetect()
        }
    }
}
