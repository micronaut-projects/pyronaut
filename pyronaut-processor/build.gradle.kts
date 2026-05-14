import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

val nativeProcessorEnabled = providers
    .gradleProperty("pyronautProcessorNative")
    .map(String::toBoolean)
    .orElse(false)

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

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(mn.micronaut.context.python)
    implementation(mn.micronaut.inject.python)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(mn.micronaut.http)
    testImplementation(mn.micronaut.router)
    testRuntimeOnly(libs.micronaut.data.jdbc)
    testRuntimeOnly(libs.micronaut.data.processor)
}

application {
    mainClass = "io.micronaut.pyronaut.processor.PyronautProcessorMain"
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

tasks {
    startScripts {
        applicationName = "pyronaut-processor"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-processor native binary (experimental; enable with -PpyronautProcessorNative=true)"
        dependsOn(nativeCompileTask)
        onlyIf { nativeProcessorEnabled.get() }
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty(
                "pyronaut.processor.native.binary",
                nativeCompileTask.get().outputFile.get().asFile.absolutePath
            )
        }
        include("**/PyronautProcessorNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
        onlyIf { nativeProcessorEnabled.get() }
    }
}

configurations.named("testRuntimeClasspath") {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-processor")
            sharedLibrary.set(false)
            buildArgs.add("-H:ConfigurationFileDirectories=${project.layout.projectDirectory.dir("src/main/resources/META-INF/native-image/io.micronaut/micronaut-pyronaut-processor").asFile.absolutePath}")
            buildArgs.addAll(nativeImageCLibraryPathArgs)
        }
        all {
            resources.autodetect()
            buildArgs.addAll(
                listOf(
                    "--add-modules=java.compiler",
                    "--enable-native-access=org.graalvm.truffle",
                    "-H:+UnlockExperimentalVMOptions",
                    "-H:EnableURLProtocols=jar",
                    "-H:+RuntimeClassLoading",
                    "-H:+AllowJRTFileSystem",
                    "-H:Preserve=package=javax.annotation.processing.*",
                    "-H:Preserve=package=javax.lang.model.*",
                    "-H:Preserve=package=javax.tools.*",
                    "-H:Preserve=package=java.lang.*",
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
                    "-H:Preserve=package=io.micronaut.annotation.processing.*",
                    "-H:Preserve=package=io.micronaut.inject.*",
                    "-H:Preserve=package=io.micronaut.context.*",
                    "-H:-PrintRestrictHeapAccessWarnings",
                    "-H:-UnlockExperimentalVMOptions",
                    "--initialize-at-build-time=com.sun.tools.javac.api.JavacTool",
                    "--initialize-at-build-time=io.micronaut.sourcegen.model,org.objectweb.asm",
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
                    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
                    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
                    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
                    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
                    "--initialize-at-run-time=io.micronaut.annotation.processing.TypeElementVisitorProcessor",
                    "--initialize-at-run-time=io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor",
                    "--initialize-at-run-time=io.micronaut.annotation.processing.PackageElementVisitorProcessor",
                    "--initialize-at-run-time=io.micronaut.inject.visitor.TypeElementVisitor",
                    "--initialize-at-run-time=io.micronaut.inject.visitor.TypeElementVisitor\$VisitorKind",
                    "--initialize-at-run-time=io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder",
                    "--initialize-at-run-time=io.micronaut.inject.processing,io.micronaut.inject.writer,io.micronaut.inject.beans.visitor",
                    "--initialize-at-run-time=com.sun.tools.javac",
                    "--initialize-at-run-time=com.sun.tools.javac.file",
                    "--initialize-at-run-time=com.sun.source",
                    "--initialize-at-run-time=jdk.javadoc.internal",
                    "--initialize-at-run-time=com.sun.tools.doclint",
                    "--initialize-at-run-time=jdk.internal.jshell.tool",
                    "--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm"
                )
            )
            buildArgs.addAll(micronautCoreNativeImageExclusion)
        }
    }
}
