import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")
val controlPanelRuntime by configurations.creating

dependencies {
    implementation(project(":micronaut-pyronaut-build-annotations"))
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    // platform processors
    implementation(mn.micronaut.inject.python)
    implementation(project(":micronaut-pyronaut-direct-source"))
    implementation(project(":micronaut-pyronaut-processor"))
    implementation(mnSerde.micronaut.serde.processor)
    implementation(mnValidation.micronaut.validation.processor)
    implementation("io.micronaut.data:micronaut-data-processor")
    implementation("io.micronaut.security:micronaut-security-processor")
    implementation("io.micronaut.micrometer:micronaut-micrometer-annotation")
    implementation("io.micronaut.jaxrs:micronaut-jaxrs-processor")
    implementation("io.micronaut.sourcegen:micronaut-sourcegen-generator-java")
    implementation("io.micronaut.sourcegen:micronaut-sourcegen-model")
    implementation("io.micronaut.openapi:micronaut-openapi")

    // CLI modules
    implementation(project(":micronaut-pyronaut-install"))
    implementation(project(":micronaut-pyronaut-config-model"))
    // TODO: this drags in a huge graph of dependencies so exclude for now
//    implementation(project(":micronaut-pyronaut-create-app"))
    implementation(project(":micronaut-pyronaut-run"))
    implementation(project(":micronaut-pyronaut-test"))
    implementation(project(":micronaut-pyronaut-test-resources-server"))
    implementation(project(":micronaut-pyronaut-pytest"))
    implementation(project(":micronaut-pyronaut-native-build"))
    implementation(project(":micronaut-pyronaut-validate-config"))
    implementation(project(":micronaut-pyronaut-logback"))


    implementation(mnPicocli.picocli)

    // Bundled only for --control-panel direct development launches. These
    // artifacts are intentionally outside the normal launcher runtime graph.
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-core:${libs.versions.micronaut.control.panel.get()}")
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-management:${libs.versions.micronaut.control.panel.get()}")
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-ui:${libs.versions.micronaut.control.panel.get()}")


    // runtime build in modules
    api("io.micronaut.openapi:micronaut-openapi-annotations")
    api("io.micronaut.data:micronaut-data-model")
    api("io.micronaut.data:micronaut-data-runtime")
    api("io.micronaut.data:micronaut-data-connection")
    api("io.micronaut.sql:micronaut-jdbc")
    api("io.micronaut.cache:micronaut-cache-core")
    api("io.micronaut.sourcegen:micronaut-sourcegen-annotations")
    api("io.micronaut.views:micronaut-views-core")
    api("io.micronaut:micronaut-management")
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

    // testing API
    api(mnTest.micronaut.test.junit5)
    api(mnTest.junit.jupiter.api)

    implementation(mnTest.junit.jupiter.engine)
    implementation(mnTest.junit.platform.launcher)

    constraints {
        runtimeOnly("org.antlr:antlr4-runtime") {
            version {
                strictly("4.11.1")
            }
            because("tomlj 1.1.1 includes parsers generated with ANTLR 4.11.1")
        }
    }
}

val bundleControlPanelJars by tasks.registering(Sync::class) {
    from(controlPanelRuntime)
    into(layout.buildDirectory.dir("generated/control-panel-libs"))
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
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-management")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-ui")
}

configurations.named("runtimeClasspath") {
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-server")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-control-panel")
}

// The native parent image already contains these modules.  Keep their Maven
// identities alongside the distribution so runtime launchers can remove a
// project dependency even when it resolved to a different version.
val nativeCompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.api.get())
}

val writeNativeClasspathManifests by tasks.registering {
    val outputDirectory = layout.buildDirectory.dir("generated/native-classpaths")
    outputs.dir(outputDirectory)
    inputs.files(nativeCompileClasspath)
    inputs.files(configurations.nativeImageClasspath)
    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        val nativeArtifacts = configurations.nativeImageClasspath.get().resolvedConfiguration.resolvedArtifacts
        directory.resolve("native-provided-classpath.txt").writeText(
            nativeArtifacts
                .map { "${it.moduleVersion.id.group}:${it.name}" }
                .distinct()
                .sorted()
                .joinToString("\n", postfix = "\n")
        )
        directory.resolve("native-compile-classpath.txt").writeText(
            nativeCompileClasspath.resolvedConfiguration.resolvedArtifacts
                .map { it.file.toPath().toAbsolutePath().normalize().toString() }
                .distinct()
                .sorted()
                .joinToString("\n", postfix = "\n")
        )
    }
}

distributions {
    named("main") {
        contents {
            into("lib/control-panel") {
                from(bundleControlPanelJars)
            }
            into("bin") {
                from(writeNativeClasspathManifests)
            }
        }
    }
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
    "--add-modules=jdk.compiler,java.net.http,java.naming,java.rmi",
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
    "-H:Preserve=package=io.swagger.v3.oas.models.*",
    "-H:IncludeResources=templates/.*",
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
    "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport",
    "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport\$PrivateLoomCondition",
    "--initialize-at-build-time=io.micronaut.http.MediaType",
    "--initialize-at-build-time=io.micronaut.http.annotation",
    "--initialize-at-build-time=io.micronaut.json",
    "--initialize-at-build-time=io.micronaut.json.bind",
    "--initialize-at-build-time=io.micronaut.json.body",
    "--initialize-at-build-time=io.micronaut.json.convert",
    "--initialize-at-build-time=io.micronaut.json.codec",
    "--initialize-at-build-time=io.micronaut.json.tree",
    "--initialize-at-build-time=io.micronaut.messaging",
    "--initialize-at-build-time=io.micronaut.messaging.annotation",
    "--initialize-at-build-time=io.micronaut.messaging.exceptions",
    "--initialize-at-build-time=io.micronaut.management.endpoint",
    "--initialize-at-build-time=io.micronaut.management.endpoint.annotation",
    "--initialize-at-build-time=io.micronaut.management.endpoint.health",
    "--initialize-at-build-time=io.micronaut.management.endpoint.indicator.annotation",
    "--initialize-at-build-time=io.micronaut.retry.annotation",
    "--initialize-at-build-time=io.micronaut.retry.event",
    "--initialize-at-build-time=io.micronaut.retry.exception",
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
    "--initialize-at-build-time=io.swagger.v3.oas.models",
    "--initialize-at-build-time=io.micronaut.openapi.javadoc",
    "--initialize-at-build-time=io.micronaut.openapi.annotation",
    "--initialize-at-build-time=io.micronaut.openapi.view",
    "--initialize-at-build-time=io.micronaut.openapi.visitor",
    "--initialize-at-build-time=io.micronaut.openapi.visitor.ConvertUtils\$1",
    "--initialize-at-build-time=com.vladsch.flexmark.html2md.converter",
    "--initialize-at-build-time=com.vladsch.flexmark.util.sequence",
    "--initialize-at-build-time=com.vladsch.flexmark.util.misc",
    "--initialize-at-run-time=com.vladsch.flexmark.util.sequence.Escaping",
    "--initialize-at-build-time=com.vladsch.flexmark.util.data",
    "--initialize-at-build-time=com.vladsch.flexmark.util.html",
    "--initialize-at-run-time=ch.qos.logback.classic.Logger",
    "--initialize-at-run-time=io.netty",
    "--initialize-at-run-time=ch.qos.logback",
    "--initialize-at-run-time=com.mysql",
    "--initialize-at-run-time=io.micronaut.testresources.client",
    "--initialize-at-run-time=io.micronaut.testresources.embedded",
    "--initialize-at-run-time=io.micronaut.core.beans.BeanIntrospector,io.micronaut.core.beans.DefaultBeanIntrospector",
    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
    "--initialize-at-run-time=io.micronaut.retry.intercept.CircuitBreakerRetry",
    "--initialize-at-run-time=io.micronaut.core.async.subscriber.CompletionAwareSubscriber",
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

    named("installDist") {
        dependsOn(writeNativeClasspathManifests)
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
