plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
}

// Runtime classpath of a Flyway application packaged into a FAT JAR by the
// tests, so they can exercise Flyway's classpath scanning against H2.
val flywayApplication by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":micronaut-pyronaut-config-model"))
    implementation(mnPicocli.picocli)

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.engine)
    testImplementation(project(":micronaut-pyronaut-run"))

    flywayApplication(platform("io.micronaut.platform:micronaut-platform:${providers.gradleProperty("pyronaut.micronaut.platform.version").get()}"))
    flywayApplication("org.flywaydb:flyway-core")
    flywayApplication("com.h2database:h2")
}

application {
    mainClass = "io.micronaut.pyronaut.jarbuild.PyronautJarBuildMain"
}

tasks {
    startScripts {
        applicationName = "pyronaut-jar-build"
    }
    test {
        val flywayClasspath = flywayApplication
        inputs.files(flywayClasspath).withPropertyName("flywayApplicationClasspath")
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dpyronaut.test.flyway.classpath=" + flywayClasspath.files.joinToString(File.pathSeparator))
        })
    }
}
