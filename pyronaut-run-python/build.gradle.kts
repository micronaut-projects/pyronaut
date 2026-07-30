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
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))
    api(project(":micronaut-pyronaut-run"))
    api(mn.micronaut.context.python)
    api(mn.micronaut.context.python.netty)
    api(project(":micronaut-pyronaut-logback"))

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.run.PyronautRunMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val nativeBuildProject = project(":micronaut-pyronaut-native-build")
val cremaProjectDirectory = layout.buildDirectory.dir("crema-native-image")
val cremaOutput = layout.buildDirectory.file("native/nativeCompile/pyronaut-run-python")
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
        applicationName = "pyronaut-run-python"
    }

    val buildCremaNativeImage = register<Exec>("buildCremaNativeImage") {
        group = "build"
        description = "Builds the Python production Crema runtime using PyronautNativeImageBuilder"
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
                "--base-image",
                "--include-python"
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
        description = "Runs smoke tests against pyronaut-run-python native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
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
            imageName.set("pyronaut-run-python")
            sharedLibrary.set(false)
        }
        all {
            resources.autodetect()
        }
    }
}
