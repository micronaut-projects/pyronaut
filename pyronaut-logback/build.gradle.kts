plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)

    api(mn.micronaut.core)
    api(mn.micronaut.context)
    api(mnLogging.logback.classic)

    // Bridge java.util.logging (JUL) to SLF4J/logback
    runtimeOnly("org.slf4j:jul-to-slf4j:2.0.18")

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation(mn.micronaut.context.python)
    testImplementation(mn.graalpy) {
        artifact {
            type = "pom"
        }
    }
    testImplementation(mn.graalpy.embedding)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(mn.micronaut.context.python)
    testImplementation(mn.graalpy) {
        artifact {
            type = "pom"
        }
    }
    testImplementation(mn.graalpy.embedding)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Both this module and micronaut-context-python contribute application VFS
    // resources. GraalPy must allow those resources to be registered together.
    systemProperty("org.graalvm.python.vfs.allow_multiple", "true")
    val pyEnv = providers.environmentVariable("PYENV_VERSION")
    val vEnv = providers.environmentVariable("VIRTUAL_ENV")
    if (pyEnv.isPresent() && vEnv.isPresent()) {
        println("==================================================================")
        println("= INFO: Python Virtual Env Found. Running GraalPy tests.         =")
        println("==================================================================")

        environment("PYENV_VERSION", pyEnv.get())
        environment("VIRTUAL_ENV", vEnv.get())
    } else {
        println("==================================================================")
        println("= WARNING: Disabling GraalPy tests because not running under virtual env =")
        println("==================================================================")
        enabled = false
    }
}
