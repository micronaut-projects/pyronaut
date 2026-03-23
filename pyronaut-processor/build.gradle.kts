import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native") version "0.11.1"
}

val nativeProcessorEnabled = providers
    .gradleProperty("pyronautProcessorNative")
    .map(String::toBoolean)
    .orElse(false)

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)
    implementation(mn.micronaut.context.python)
    implementation(mn.micronaut.inject.python)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
}

application {
    mainClass = "io.micronaut.pyronaut.processor.PyronautProcessorMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-processor"
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    val testSourceSet = the<SourceSetContainer>()["test"]

    nativeCompileTask.configure {
        onlyIf { nativeProcessorEnabled.get() }
    }

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

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-processor")
            sharedLibrary.set(false)
            buildArgs.add("-H:ConfigurationFileDirectories=${project.layout.projectDirectory.dir("src/main/resources/META-INF/native-image/io.micronaut/micronaut-pyronaut-processor").asFile.absolutePath}")
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
                    "-H:Preserve=package=javax.annotation.processing",
                    "-H:Preserve=package=javax.lang.model",
                    "-H:Preserve=package=javax.tools",
                    "-H:Preserve=package=java.lang",
                    "-H:Preserve=package=java.lang.invoke",
                    "-H:Preserve=package=jdk.internal.misc",
                    "-H:Preserve=package=jdk.internal.access",
                    "-H:Preserve=package=io.micronaut.inject",
                    "-H:Preserve=package=io.micronaut.inject.visitor",
                    "-H:Preserve=package=io.micronaut.inject.ast",
                    "-H:Preserve=package=io.micronaut.context",
                    "-H:-PrintRestrictHeapAccessWarnings",
                    "-H:-UnlockExperimentalVMOptions",
                    "--initialize-at-build-time=com.sun.tools.javac.api.JavacTool",
                    "--initialize-at-build-time=io.micronaut.sourcegen.model,org.objectweb.asm",
                    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
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
        }
    }
}
