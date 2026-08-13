import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import java.nio.file.Files

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))

    api(mn.micronaut.context)
    api(mnPicocli.picocli)
    api(mnOpenapi.micronaut.openapi.annotations)
    api("io.micronaut.data:micronaut-data-model")
    api("io.micronaut.data:micronaut-data-runtime")
    api("io.micronaut.data:micronaut-data-connection")
    api("io.micronaut.sql:micronaut-jdbc")
    api("io.micronaut.cache:micronaut-cache-core")
    api("io.micronaut.sourcegen:micronaut-sourcegen-annotations")
    api("io.micronaut.views:micronaut-views-core")
    api("io.micronaut:micronaut-management")
    api(mn.micronaut.http.server)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.messaging)
    api(mn.micronaut.websocket)
    api(mn.micronaut.runtime)
    api(mn.micronaut.retry)
    api(libs.micronaut.toml)
    api(mnValidation.micronaut.validation)
    api("io.micronaut:micronaut-discovery-core")
    api(mn.micronaut.json.core)
    api(mnSerde.micronaut.serde.jackson)
    api("io.micronaut.serde:micronaut-serde-api")
    api(project(":micronaut-pyronaut-logback"))
    implementation(project(":micronaut-pyronaut-config-model"))
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-processor"))
    testAnnotationProcessor(mn.micronaut.inject.java)
    testRuntimeOnly(mn.micronaut.http.server)
    testRuntimeOnly(mn.micronaut.http.server.netty)
    testRuntimeOnly("io.micronaut:micronaut-discovery-core")
    testRuntimeOnly(mn.micronaut.json.core)
    testRuntimeOnly(mn.micronaut.jackson.databind)
}

// Control Panel is an optional application feature; it must not be embedded
// in the production runner native image. Explicit project dependencies remain
// on the application's own runtime classpath.
configurations.named("runtimeClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

configurations.named("nativeImageClasspath") {
    exclude(group = "io.micronaut.controlpanel")
}

application {
    mainClass = "io.micronaut.pyronaut.run.PyronautRunMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val nativeBuildProject = project(":micronaut-pyronaut-native-build")
val pythonRunProject = project(":micronaut-pyronaut-run-python")
val cremaProjectDirectory = layout.buildDirectory.dir("crema-native-image")
val cremaOutput = layout.buildDirectory.file("native/nativeCompile/pyronaut-run")
val pythonCremaOutput = pythonRunProject.layout.buildDirectory.file("native/nativeCompile/pyronaut-run-python")
val nativeBuildExecutable = nativeBuildProject.layout.buildDirectory.file(
    "install/micronaut-pyronaut-native-build/bin/pyronaut-native-build"
)
val writeNativeClasspathManifest by tasks.registering {
    val outputDirectory = layout.buildDirectory.dir("generated/native-classpaths")
    outputs.dir(outputDirectory)
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("native-provided-classpath.txt").writeText(
            configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                .map { "${it.moduleVersion.id.group}:${it.name}" }
                .distinct()
                .sorted()
                .joinToString("\n", postfix = "\n")
        )
    }
}

tasks {
    startScripts {
        applicationName = "pyronaut-run"
    }

    val buildCremaNativeImage = register<Exec>("buildCremaNativeImage") {
        group = "build"
        description = "Builds the production Crema runtime using PyronautNativeImageBuilder"
        dependsOn(nativeBuildProject.tasks.named("installDist"))
        dependsOn(writeNativeClasspathManifest)
        inputs.files(configurations.runtimeClasspath)
        outputs.file(cremaOutput)
        doFirst {
            val projectDirectory = cremaProjectDirectory.get().asFile.toPath()
            Files.createDirectories(projectDirectory.resolve("__pyronaut__/classes"))
            Files.writeString(
                projectDirectory.resolve("__pyronaut__/resolved-runtime-dependencies"),
                configurations.runtimeClasspath.get().files.joinToString(System.lineSeparator()) { it.absolutePath } + System.lineSeparator()
            )
            commandLine(
                nativeBuildExecutable.get().asFile.absolutePath,
                "--project-dir", projectDirectory.toString(),
                "--output", cremaOutput.get().asFile.absolutePath,
                "--base-image"
            )
        }
    }
    val nativeCompileTask = named("nativeCompile") {
        dependsOn(buildCremaNativeImage)
        dependsOn(writeNativeClasspathManifest)
        onlyIf { false }
    }
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs the Python controller smoke test against pyronaut-run-python"
        dependsOn(pythonRunProject.tasks.named("buildCremaNativeImage"))
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.run.native.binary", pythonCremaOutput.get().asFile.absolutePath)
        }
        include("**/PyronautRunNativeSmokeTest.class")
    }

    register<Test>("javaNativeSmokeTest") {
        group = "verification"
        description = "Runs the Java controller smoke test against pyronaut-run"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.run.native.binary", cremaOutput.get().asFile.absolutePath)
        }
        include("**/PyronautRunJavaNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
        dependsOn(named("javaNativeSmokeTest"))
    }
}

distributions {
    named("main") {
        contents {
            into("bin") {
                from(writeNativeClasspathManifest)
            }
        }
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-run")
            sharedLibrary.set(false)
        }
        all {
            resources.autodetect()
        }
    }
}
