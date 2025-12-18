plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("nu.studer.rocker") version "3.2"

}
dependencies {
    implementation(platform(libs.micronaut.projectgen))
    implementation(libs.micronaut.projectgen.core)
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
rocker {
    configurations {
        create("main") {
            optimize.set(true)
            templateDir.set(file("src/rocker"))
            outputDir.set(file("src/generated/rocker"))
        }
    }
}

spotless {
    java {
        targetExclude("src/**/*.rocker.raw")
    }
}
