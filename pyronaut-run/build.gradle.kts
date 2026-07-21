import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
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

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))

    // runtime build in modules
    api("io.micronaut.data:micronaut-data-model")
    api("io.micronaut.data:micronaut-data-runtime")
    api("io.micronaut.data:micronaut-data-connection")
    api("io.micronaut.sql:micronaut-jdbc")
    api("io.micronaut.cache:micronaut-cache-core")
    api("io.micronaut.sourcegen:micronaut-sourcegen-annotations")
    api("io.micronaut.views:micronaut-views-core")
    api(mnSerde.micronaut.serde.jackson)
    api(mn.micronaut.context.python.netty)
    api(mn.micronaut.context.python)
    api(mn.micronaut.runtime)
    api(mn.micronaut.retry)
    api(libs.micronaut.toml)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.messaging)
    api(mn.micronaut.websocket)
    api(mnValidation.micronaut.validation)
    implementation(mnPicocli.picocli)
    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(project(":micronaut-pyronaut-logback"))
    runtimeOnly(mnLogging.logback.classic)

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
    "--emit",
    "build-report",
    "--enable-native-access=org.graalvm.truffle",
    "--add-modules=java.net.http,java.naming,java.rmi",
    "-H:+UnlockExperimentalVMOptions",
    "-H:EnableURLProtocols=jar",
    "-H:+RuntimeClassLoading",
    "-H:+AllowJRTFileSystem",
    "-H:+SharedArenaSupport",
    "-H:-SupportCompileInIsolates",
    "-H:Preserve=package=io.micronaut.http.netty.*",
    "--features=io.micronaut.core.io.service.PyronautRunServiceLoaderFeature",
    "--enable-http",
    "--enable-https",
    "-H:Preserve=module=java.base,package=java.*,package=sun.*,module=java.sql,package=java.sql,package=java.sql.*,package=javax.sql,package=io.micronaut.*,package=jakarta.annotation,package=jakarta.annotation.*,package=jakarta.inject,package=jakarta.inject.*",
    "-H:Preserve=package=java.lang.*",
    "-H:Preserve=package=java.lang.invoke.*",
    "-H:Preserve=package=java.text.*",
    "-H:Preserve=package=java.time.*",
    "-H:Preserve=package=java.util.*",
    "-H:Preserve=package=java.net.http",
    "-H:Preserve=package=java.rmi.server",
    "-H:Preserve=package=jdk.internal.misc.*",
    "-H:Preserve=package=jdk.internal.access.*",
    "-H:Preserve=package=io.micronaut.core.annotation.*",
    "-H:Preserve=package=io.micronaut.core.beans.*",
    "-H:Preserve=package=io.micronaut.core.naming.*",
    "-H:Preserve=package=io.micronaut.core.reflect.*",
    "-H:Preserve=package=io.micronaut.core.type.*",
    "-H:Preserve=package=io.micronaut.core.util.*",
    "-H:Preserve=package=io.micronaut.core.io.service.*",
    "-H:Preserve=package=io.micronaut.buffer.netty.*",
    "-H:Preserve=package=io.micronaut.inject.*",
    "-H:Preserve=package=io.micronaut.context.*",
    "-H:Preserve=package=io.micronaut.scheduling.*",
    "-H:Preserve=package=io.micronaut.runtime.*",
    "-H:Preserve=package=io.micronaut.http.*",
    "-H:Preserve=package=io.micronaut.http.netty",
    "-H:Preserve=package=io.micronaut.http.netty.*",
    "-H:Preserve=package=io.netty.channel",
    "-H:Preserve=package=io.netty.channel.nio",
    "-H:Preserve=package=io.netty.resolver.*",
    "-H:Preserve=package=io.netty.handler.codec.http.*",
    "-H:Preserve=package=io.netty.handler.ssl",
    "-H:Preserve=package=io.netty.util",
    "-H:Preserve=package=io.netty.util.concurrent",
    "-H:Preserve=package=io.micronaut.json.*",
    "-H:Preserve=package=io.micronaut.jackson.*",
    "-H:Preserve=package=io.micronaut.toml.*",
    "-H:Preserve=package=io.micronaut.serde.*",
    "-H:Preserve=package=tools.jackson.core.*",
    "-H:Preserve=package=com.fasterxml.jackson.annotation.*",
    "-H:Preserve=package=jakarta.*",
    "-H:Preserve=package=org.slf4j.*",
    "-H:Preserve=package=ch.qos.logback.*",
    "-H:-PrintRestrictHeapAccessWarnings",
    "--initialize-at-build-time=jakarta.annotation,jakarta.inject",
    "--initialize-at-build-time=io.micronaut.inject.annotation",
    "--initialize-at-build-time=io.micronaut.inject.beans",
    "--initialize-at-build-time=io.micronaut.inject.provider",
    "--initialize-at-build-time=io.micronaut.core.io",
    "--initialize-at-build-time=io.micronaut.core.optim",
    "--initialize-at-build-time=io.micronaut.core.async.publisher.PublishersOptimizations",
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
    "--initialize-at-build-time=io.micronaut.core.reflect.ClassUtils\$Optimizations",
    "--initialize-at-build-time=io.micronaut.scheduling.LoomSupport",
//    Pyronaut
    "--initialize-at-build-time=io.micronaut.pyronaut.config.classloader",
    "--initialize-at-build-time=io.micronaut.pyronaut.config.model",
//    Runtime Init
    "--initialize-at-run-time=io.micronaut",
    "--initialize-at-run-time=io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder",
    "--initialize-at-build-time=io.micronaut.inject.validation",
    "--initialize-at-build-time=io.micronaut.validation",
    "--initialize-at-build-time=io.micronaut.validation.validator",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm",
    "--initialize-at-run-time=jdk.internal.shellsupport.doc.JavadocHelper",
    "--initialize-at-run-time=io.netty",
    "--initialize-at-run-time=ch.qos.logback",
    "--initialize-at-run-time=io.micronaut.core.beans.BeanIntrospector,io.micronaut.core.beans.DefaultBeanIntrospector",
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
