plugins {
    id("io.micronaut.build.internal.pyronaut-module")
}


dependencies {
    annotationProcessor(mn.micronaut.inject.java)

    api(mn.micronaut.core)
    api(mn.micronaut.context)
    api(mn.micronaut.context.python)
    api(mnLogging.slf4j.api)        
}
