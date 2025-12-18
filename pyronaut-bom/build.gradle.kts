plugins {
    id("io.micronaut.build.internal.pyronaut-base")
    id("io.micronaut.build.internal.bom")
}
micronautBuild {
    binaryCompatibility {
        enabled = false
    }
}