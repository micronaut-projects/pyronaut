plugins {
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("graalvmDevToolchain") {
            id = "io.micronaut.build.graalvm-dev-toolchain"
            implementationClass = "io.micronaut.build.GraalVmDevBuildToolchainPlugin"
        }
    }
}
