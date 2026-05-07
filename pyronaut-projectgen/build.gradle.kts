plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}
dependencies {
    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(platform(libs.micronaut.projectgen))
    implementation(libs.micronaut.projectgen.core)
    implementation(libs.micronaut.projectgen.micronaut)
    testImplementation(libs.micronaut.projectgen.test)
    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mnTest.micronaut.test.junit5)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
    testImplementation(mnTest.junit.jupiter.params)
}
micronautBuild {
    binaryCompatibility {
        enabled = false
    }
}

val projectgenMicronautVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")

val generateProjectgenDefaults = tasks.register("generateProjectgenDefaults") {
    val outputDir = layout.buildDirectory.dir("generated/projectgen-defaults")
    outputs.dir(outputDir)
    doLast {
        val defaults = outputDir.get()
            .file("io/micronaut/pyronaut/projectgen/defaults.properties")
            .asFile
        defaults.parentFile.mkdirs()
        defaults.writeText(
            "micronaut.version=${projectgenMicronautVersion.get()}${System.lineSeparator()}",
            Charsets.UTF_8
        )
    }
}

sourceSets {
    main {
        resources.srcDir(generateProjectgenDefaults)
    }
}
