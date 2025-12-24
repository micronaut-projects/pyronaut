plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("io.micronaut.build.internal.python")
}

dependencies {
    implementation(mn.micronaut.core)
    implementation(mn.micronaut.context.python)
    implementation(mnTest.micronaut.test.core)
    implementation(mnTest.junit.platform.engine)
    implementation(mnTest.junit.platform.launcher)
    implementation(mn.graalpy) {
        artifact {
            type = "pom"
        }
    }
    implementation(mn.graalpy.embedding)

    testImplementation(mnTest.junit.platform.engine)
    testImplementation(mnTest.junit.platform.testkit)
    testImplementation(mn.micronaut.inject.python)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.params)
    testImplementation(mnTest.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val pyEnv = providers.environmentVariable("PYENV_VERSION")
    val vEnv = providers.environmentVariable("VIRTUAL_ENV")
    if (pyEnv.isPresent() && vEnv.isPresent()) {
        println("==================================================================")
        println("= INFO: Python Virtual Env Found. Running tests.                 =")
        println("==================================================================")

//        systemProperty("pytest.src.dir", "src/test/python")
        environment("PYENV_VERSION", pyEnv.get())
        environment("VIRTUAL_ENV", vEnv.get())
    } else {
        println("==================================================================")
        println("= WARNING: Disabling tests because not running under virtual env =")
        println("==================================================================")
        enabled = false
    }
}
