import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    // processors
    implementation(mn.micronaut.inject.python)
    implementation(project(":micronaut-pyronaut-processor"))
    implementation(mnSerde.micronaut.serde.processor)
    implementation(mnValidation.micronaut.validation.processor)

    // CLI modules
    implementation(project(":micronaut-pyronaut-install"))
    implementation(project(":micronaut-pyronaut-config-model"))
    // TODO: this drags in a huge graph of dependencies so exclude for now
//    implementation(project(":micronaut-pyronaut-create-app"))
    implementation(project(":micronaut-pyronaut-run"))
    implementation(project(":micronaut-pyronaut-test"))
    implementation(project(":micronaut-pyronaut-native-build"))
    implementation(project(":micronaut-pyronaut-validate-config"))
    implementation(project(":micronaut-pyronaut-test-resources-server"))
    implementation(project(":micronaut-pyronaut-logback"))

    implementation(mn.micronaut.context.python)
    implementation(mnPicocli.picocli)
    implementation(mnTest.junit.platform.launcher)

    // runtime build in modules
    runtimeOnly(mnSerde.micronaut.serde.jackson)
//    runtimeOnly(mn.micronaut.context.python.netty)
    runtimeOnly(mn.micronaut.runtime)
    runtimeOnly(mn.micronaut.retry)
    runtimeOnly(libs.micronaut.toml)
    runtimeOnly(mn.micronaut.http.client)
    runtimeOnly(mn.micronaut.http.server)
    runtimeOnly(mn.micronaut.http.server.netty)
    runtimeOnly(mn.micronaut.messaging)
    runtimeOnly(mn.micronaut.websocket)

    runtimeOnly(mnValidation.micronaut.validation)



    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)

    constraints {
        runtimeOnly("org.antlr:antlr4-runtime") {
            version {
                strictly("4.11.1")
            }
            because("tomlj 1.1.1 includes parsers generated with ANTLR 4.11.1")
        }
    }
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

configurations.named("nativeImageClasspath") {
    exclude(group = "org.openrewrite", module = "rewrite-kotlin")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-client")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-server")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-control-panel")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-core")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-ui")
}

application {
    mainClass = "io.micronaut.pyronaut.dev.PyronautDevMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val runtimeMetadataExclusion = providers.provider {
    val excludedArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .filter { artifact ->
            val group = artifact.moduleVersion.id.group
            val name = artifact.name
            name == "micronaut-test-resources-server" ||
                name == "micronaut-test-resources-control-panel" ||
                group == "io.micronaut.controlpanel"
        }
        .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
    buildList {
        excludedArtifacts.forEach { jar ->
            add("--exclude-config")
            add("\\Q$jar\\E")
            add("^/META-INF/native-image/.*")
        }
        configurations.nativeImageClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { artifact ->
                artifact.moduleVersion.id.group == "io.micronaut" && artifact.name == "micronaut-core"
            }
            .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
            .forEach { jar ->
                add("--exclude-config")
                add("\\Q$jar\\E")
                add("^/META-INF/native-image/io\\.micronaut/micronaut-core/native-image\\.properties$")
            }
    }
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

val nativeImageRuntimeArgs = listOf(
    "--emit",
    "build-report",
    "--enable-native-access=org.graalvm.truffle",
    "--add-modules=jdk.compiler,java.net.http,java.naming",
    "-H:+UnlockExperimentalVMOptions",
    "-H:EnableURLProtocols=jar",
    "-H:+RuntimeClassLoading",
    "-H:+AllowJRTFileSystem",
    "-H:+SharedArenaSupport",
    "-H:-SupportCompileInIsolates",
    "-H:Preserve=package=io.micronaut.http.netty.*",
    "--features=io.micronaut.core.io.service.PyronautDevServiceLoaderFeature",
    "--enable-http",
    "--enable-https",
    "-H:Preserve=module=java.base,package=java.*,package=jdk.internal.*,package=sun.*,module=java.sql,package=java.sql,package=java.sql.*,package=javax.sql,module=jdk.compiler,module=java.compiler,package=io.micronaut.*,package=jakarta.annotation,package=jakarta.annotation.*,package=jakarta.inject,package=jakarta.inject.*,package=org.junit.*,package=org.opentest4j.*,package=org.objectweb.asm,package=org.objectweb.asm.*,package=com.github.javaparser,package=com.github.javaparser.*,package=org.apache.maven.*,package=org.eclipse.aether.*,package=org.codehaus.plexus.*",
    "-H:Preserve=package=java.lang.*",
    "-H:Preserve=package=java.lang.invoke.*",
    "-H:Preserve=package=java.text.*",
    "-H:Preserve=package=java.time.*",
    "-H:Preserve=package=java.util.*",
    "-H:Preserve=package=java.net.http",
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
    "-H:Preserve=package=io.micronaut.test.*",
    "-H:Preserve=package=io.micronaut.testresources.*",
    "-H:Preserve=package=io.micronaut.test.pytest.*",
    "-H:Preserve=package=org.junit.platform.*",
    "-H:Preserve=package=org.junit.jupiter.*",
    "-H:Preserve=package=org.apache.maven.*",
    "-H:Preserve=package=org.eclipse.aether.*",
    "-H:Preserve=package=org.codehaus.plexus.*",
    "-H:Preserve=package=com.github.javaparser.*",
    "-H:Preserve=package=jakarta.*",
    "-H:Preserve=package=org.slf4j.*",
    "-H:Preserve=package=ch.qos.logback.*",
    "-H:-PrintRestrictHeapAccessWarnings",
    "--initialize-at-build-time=jakarta.annotation,jakarta.inject",
    "--initialize-at-build-time=com.sun.tools.javac.api.JavacTool",
    "--initialize-at-build-time=io.micronaut.sourcegen.model,org.objectweb.asm",
    "--initialize-at-build-time=io.micronaut.annotation.processing",
    "--initialize-at-build-time=io.micronaut.inject.annotation",
    "--initialize-at-build-time=io.micronaut.inject.beans",
    "--initialize-at-build-time=io.micronaut.inject.beans.visitor",
    "--initialize-at-build-time=io.micronaut.inject.processing",
    "--initialize-at-build-time=io.micronaut.inject.writer",
    "--initialize-at-build-time=io.micronaut.inject.provider",
    "--initialize-at-build-time=io.micronaut.python.compiler",
    "--initialize-at-build-time=io.micronaut.python.processing",
    "--initialize-at-build-time=io.micronaut.python.processing.annotation",
    "--initialize-at-build-time=io.micronaut.python.processing.beans",
    "--initialize-at-build-time=io.micronaut.python.processing.util",
    "--initialize-at-build-time=io.micronaut.python.processing.visitor",
    "--initialize-at-build-time=io.micronaut.aop.mapper",
    "--initialize-at-build-time=io.micronaut.context.visitor",
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
    "--initialize-at-build-time=io.micronaut.pyronaut.install",
    "--initialize-at-build-time=io.micronaut.pyronaut.processor",
    "--initialize-at-build-time=io.micronaut.pyronaut.testresources",
    "--initialize-at-build-time=io.micronaut.pyronaut.nativebuild",
    "--initialize-at-build-time=io.micronaut.pyronaut.config.classloader",
    "--initialize-at-build-time=io.micronaut.pyronaut.config.model",
//    SourceGen
    "--initialize-at-build-time=io.micronaut.sourcegen",
    "--initialize-at-build-time=io.micronaut.sourcegen.annotations",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode.expression",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode.statement",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator.bytecode",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator.visitors",
    "--initialize-at-build-time=io.micronaut.sourcegen.info",
    "--initialize-at-build-time=io.micronaut.sourcegen.javapoet",
    "--initialize-at-build-time=io.micronaut.sourcegen.model",
//    Runtime Init
    "--initialize-at-run-time=io.micronaut",
    "--initialize-at-run-time=io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder",
    "--initialize-at-build-time=io.micronaut.inject.validation",
    "--initialize-at-build-time=io.micronaut.serde.processor",
    "--initialize-at-build-time=io.micronaut.jsonschema.configuration.validator",
    "--initialize-at-run-time=io.micronaut.jsonschema.configuration.validator.DefaultDependencyInjectionValidator",
    "--initialize-at-build-time=io.micronaut.validation",
    "--initialize-at-build-time=io.micronaut.validation.validator",
    "--initialize-at-build-time=io.micronaut.validation.validator.DefaultAnnotatedElementValidator",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm",
    "--initialize-at-build-time=com.github.javaparser",
    "--initialize-at-run-time=io.netty",
    "-H:IncludeResources=com/mysql/cj/.*\\.properties",
    "--initialize-at-run-time=ch.qos.logback",
    "--initialize-at-run-time=com.mysql",
    "--initialize-at-run-time=io.micronaut.testresources.client",
    "--initialize-at-run-time=io.micronaut.testresources.embedded",
    "--initialize-at-run-time=io.micronaut.core.beans.BeanIntrospector,io.micronaut.core.beans.DefaultBeanIntrospector",
    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
    "-H:-UnlockExperimentalVMOptions"
)

val nativeImagePgoArgs = providers.provider {
    val instrument = providers.gradleProperty("pyronautDevPgoInstrument")
        .map(String::toBoolean)
        .orElse(false)
        .get()
    val profile = providers.gradleProperty("pyronautDevPgoProfile")
        .orNull
        ?.takeIf(String::isNotBlank)

    when {
        instrument -> listOf("--pgo-instrument")
        profile != null -> listOf("--pgo=$profile")
        else -> emptyList()
    }
}

tasks {
    startScripts {
        applicationName = "pyronaut-dev"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-dev native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.dev.native.binary", nativeCompileTask.get().outputFile.get().asFile.absolutePath)
        }
        include("**/PyronautDevNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-dev")
            sharedLibrary.set(false)
            buildArgs.addAll(nativeImageCLibraryPathArgs)
            buildArgs.addAll(nativeImageRuntimeArgs)
            buildArgs.addAll(nativeImagePgoArgs)
            buildArgs.addAll(runtimeMetadataExclusion)
        }
        all {
            resources.autodetect()
        }
    }
}
