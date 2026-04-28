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
    implementation(mnPicocli.picocli)
    implementation(libs.micronaut.control.panel.core)
    implementation(mn.micronaut.http.server)
    implementation(libs.micronaut.test.resources.build.tools)
    implementation(libs.micronaut.test.resources.core)
    implementation(libs.micronaut.test.resources.control.panel)
    implementation(libs.micronaut.test.resources.server)
    implementation(mnLogging.logback.classic)
    runtimeOnly(libs.slf4j.jul.to.slf4j)
    runtimeOnly(mn.micronaut.http.server.netty)

    testImplementation(libs.docker.java.api)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

configurations.named("nativeImageClasspath") {
    exclude(group = "io.netty")
    exclude(group = "io.projectreactor", module = "reactor-core")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-server")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-control-panel")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-core")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-ui")
    exclude(group = "io.micronaut", module = "micronaut-http-server")
    exclude(group = "io.micronaut", module = "micronaut-http-server-netty")
    exclude(group = "io.micronaut", module = "micronaut-http-netty")
    exclude(group = "io.micronaut", module = "micronaut-buffer-netty")
}

application {
    mainClass = "io.micronaut.pyronaut.testresources.PyronautTestResourcesServerMain"
}

val runtimeMetadataExclusion = providers.provider {
    val excludedArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .filter { artifact ->
            val group = artifact.moduleVersion.id.group
            val name = artifact.name
            group == "io.netty" ||
                name == "micronaut-test-resources-server" ||
                name == "micronaut-test-resources-control-panel" ||
                name == "micronaut-control-panel-core" ||
                name == "micronaut-control-panel-ui" ||
                name == "micronaut-http-server" ||
                name == "micronaut-http-server-netty" ||
                name == "micronaut-http-netty" ||
                name == "micronaut-buffer-netty" ||
                name == "reactor-core" ||
                name == "testcontainers"
        }
        .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
    buildList {
        excludedArtifacts.forEach { jar ->
            add("--exclude-config")
            add("\\Q$jar\\E")
            add("^/META-INF/native-image/.*")
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
    "-H:Preserve=package=io.micronaut.core.type.*",
    "-H:Preserve=package=io.micronaut.core.util.*",
    "-H:Preserve=package=io.micronaut.core.io.service.*",
    "-H:Preserve=package=io.micronaut.inject.*",
    "-H:Preserve=package=io.micronaut.context.*",
    "-H:Preserve=package=io.micronaut.testresources.*",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "--initialize-at-run-time=io.netty",
    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
    "-H:-UnlockExperimentalVMOptions"
)

tasks {
    startScripts {
        applicationName = "pyronaut-test-resources-server"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-test-resources-server native binary"
        dependsOn(nativeCompileTask)
        dependsOn(named("installDist"))
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty(
                "pyronaut.testresources.native.binary",
                nativeCompileTask.get().outputFile.get().asFile.absolutePath
            )
            systemProperty(
                "pyronaut.testresources.install.lib.dir",
                layout.buildDirectory.dir("install/micronaut-pyronaut-test-resources-server/lib").get().asFile.absolutePath
            )
        }
        include("**/PyronautTestResourcesServerNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-test-resources-server")
            sharedLibrary.set(false)
            buildArgs.addAll(nativeImageCLibraryPathArgs)
            buildArgs.addAll(nativeImageRuntimeClassLoadingArgs)
            buildArgs.addAll(runtimeMetadataExclusion)
        }
        all {
            resources.autodetect()
        }
    }
}
