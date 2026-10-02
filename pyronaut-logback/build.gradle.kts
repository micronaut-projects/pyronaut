plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)

    api(mn.micronaut.core)
    api(mn.micronaut.context)
    api(mnLogging.logback.classic)
    constraints {
        // micronaut-logging 2.1.0 manages logback 1.5.37, which is affected by
        // CVE-2026-19880. The fix only exists in the 1.6.x line.
        api(libs.logback.classic)
        api(libs.logback.core)
    }

    // Bridge java.util.logging (JUL) to SLF4J/logback
    runtimeOnly(mnLogging.slf4j.jul.to.slf4j)
    runtimeOnly(mnLogging.slf4j.jcl.over.slf4j)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation("org.awaitility:awaitility:4.3.0")
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
