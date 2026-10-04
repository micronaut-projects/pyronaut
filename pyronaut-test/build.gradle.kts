
import java.io.File

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(project(":micronaut-pyronaut-pytest"))
    implementation(project(":micronaut-pyronaut-logback"))
    implementation(mn.micronaut.context.python)
    // the asyncio event loop of `pyronaut test`, as in the dev and run launchers: without it coroutines run
    // on a fallback loop, where awaited client calls and run_in_executor do not work like they do in the app
    implementation(mn.micronaut.context.python.netty) {
        exclude(group = "org.graalvm.python", module = "python-bouncycastle-support")
    }
    implementation(mnPicocli.picocli)
    implementation(mnTest.junit.platform.launcher)
    runtimeOnly(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))
    runtimeOnly(mn.micronaut.http)
    runtimeOnly("io.micrometer:context-propagation")
    runtimeOnly(libs.micronaut.toml)
    runtimeOnly(libs.micronaut.test.resources.client)
    runtimeOnly("io.projectreactor:reactor-core")
    runtimeOnly(mnTest.junit.jupiter.engine)
    runtimeOnly(mnTest.micronaut.test.junit5) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-processor"))
    testRuntimeOnly(project(":micronaut-pyronaut-pytest"))
    testRuntimeOnly(project(":micronaut-pyronaut-logback"))
    testRuntimeOnly(libs.micronaut.toml)
    testRuntimeOnly(libs.micronaut.test.resources.client)
    testRuntimeOnly("io.projectreactor:reactor-core")
    testRuntimeOnly(mnTest.micronaut.test.junit5) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

application {
    mainClass = "io.micronaut.pyronaut.test.PyronautTestMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

tasks {
    startScripts {
        applicationName = "pyronaut-test"
    }

    withType<Test>().configureEach {
        useJUnitPlatform()
        val pyEnv = providers.environmentVariable("PYENV_VERSION")
        val vEnv = providers.environmentVariable("VIRTUAL_ENV")
        val virtualEnv = vEnv.orNull?.let(::File)
        val hasPythonExecutable = virtualEnv != null && (
            File(virtualEnv, "bin/python").isFile ||
                File(virtualEnv, "Scripts/python.exe").isFile
            )
        if (pyEnv.isPresent && vEnv.isPresent && hasPythonExecutable) {
            environment("PYENV_VERSION", pyEnv.get())
            environment("VIRTUAL_ENV", vEnv.get())
        } else {
            println("==================================================================")
            println("= WARNING: Disabling Pyronaut test smoke tests without GraalPy =")
            println("= virtual environment (set PYENV_VERSION and VIRTUAL_ENV).    =")
            println("==================================================================")
            enabled = false
        }
    }
}
