import com.oracle.graal.python.bindings.j2pyi.PyiFromDependencySources
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    // Resolved via pluginManagement { repositories { mavenLocal() ... } } in settings.gradle
    id("com.oracle.graal.python.bindings.j2pyi") version "1.3-SNAPSHOT"
    java
}

repositories {
    // Resolve Micronaut artifacts and the local j2pyi doclet/plugin during development
    mavenCentral()
    mavenLocal()
}

// Use Micronaut versions from the central versions catalog (gradle/libs.versions.toml)
val micronautVersionFromCatalog = libs.versions.micronaut.platform.get()

val micronautApi by configurations.registering {
    isCanBeResolved = true
    isCanBeConsumed = false
    // Ensure variant selection matches normal JVM runtime artifacts
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }
}

dependencies {
    // Align all Micronaut artifacts to a single, consistent version via the platform BOM.
    // Correct coordinate is 'micronaut-platform' (not '-bom').
    micronautApi(platform("io.micronaut.platform:micronaut-platform:$micronautVersionFromCatalog"))

    // Core/public API surface (representative top-level modules; add/remove as needed)
    // NOTE: We do not specify versions; they come from the BOM above.
    micronautApi("io.micronaut:micronaut-core")
    micronautApi("io.micronaut:micronaut-inject")
    micronautApi("io.micronaut:micronaut-http")
    micronautApi("io.micronaut:micronaut-http-client")
    micronautApi("io.micronaut:micronaut-http-server")
    // Correct group for validation in Micronaut 4
    micronautApi("io.micronaut.validation:micronaut-validation")
    micronautApi("io.micronaut:micronaut-runtime")
    micronautApi("io.micronaut:micronaut-context")

    // Feature modules that add genuine functionality (from modules.yml):
    // - Data, Caching, Scheduling, Retry, Security, Views, Problem JSON
    // Avoids adapters/integrations that are purely about other frameworks (e.g., Spring, RxJava).
    micronautApi("io.micronaut.data:micronaut-data-runtime")
    micronautApi("io.micronaut.cache:micronaut-cache-core")
    // Retry support (scheduling is part of core; no standalone 'micronaut-scheduling' artifact on Maven Central)
    micronautApi("io.micronaut:micronaut-retry")
    // Security: focus on core/annotations (skip OAuth/OIDC adapters here)
    micronautApi("io.micronaut.security:micronaut-security-annotations")
    micronautApi("io.micronaut.security:micronaut-security")
    // Views core (template-engine specific modules intentionally omitted)
    micronautApi("io.micronaut.views:micronaut-views-core")
    // Problem Details for HTTP APIs (RFC 7807)
    micronautApi("io.micronaut.problem:micronaut-problem-json")
    // Sessions and multitenancy support
    micronautApi("io.micronaut.session:micronaut-session")
    micronautApi("io.micronaut.multitenancy:micronaut-multitenancy")
    // Micrometer metrics integration (core API)
    micronautApi("io.micronaut.micrometer:micronaut-micrometer-core")
    // Email core API (skip provider-specific transport integrations)
    micronautApi("io.micronaut.email:micronaut-email")
    // Object storage common API (skip cloud-provider-specific modules)
    micronautApi("io.micronaut.objectstorage:micronaut-object-storage-core")
    // RSS support
    micronautApi("io.micronaut.rss:micronaut-rss")
    // JMX support
    micronautApi("io.micronaut.jmx:micronaut-jmx")

    // Common "API" category integrations from modules.yml (these often publish sources and API)
    micronautApi("io.micronaut.graphql:micronaut-graphql")
    micronautApi("io.micronaut.grpc:micronaut-grpc-runtime")
    // Correct group for XML support
    micronautApi("io.micronaut.xml:micronaut-jackson-xml")
    micronautApi("io.micronaut.jaxrs:micronaut-jaxrs-server")
    // Micronaut JSON Schema: base artifact not published as a single module on Maven Central (as of 1.1.x).
    // Omit for now; can be reintroduced with the correct coordinates if desired.
    micronautApi("io.micronaut.openapi:micronaut-openapi-annotations")
    micronautApi("io.micronaut.serde:micronaut-serde-api")

    // Additional libs used by Micronaut sources and annotations (no versions: inherited from BOMs where possible)
    micronautApi("org.jetbrains.kotlin:kotlin-stdlib")
    micronautApi("org.jetbrains:annotations:24.1.0")
    micronautApi("io.micrometer:micrometer-core")
    micronautApi("org.yaml:snakeyaml:2.2")

    // Extra APIs referenced by some Micronaut modules' sources.
    //
    // If the build fails due to missing packages or classes, examine the upstream build system to locate the
    // compileOnly dependencies and copy them here.
    //
    // TODO: Ideally, there is a way to get this metadata from the artifacts or Maven repositories. However because
    //       these coordinates aren't normally needed by consumers, they aren't published. For now we just duplicate
    //       them here and upgrading Micronaut may require tweaks or extensions. A better approach would be to modify
    //       the Micronaut build system to publish compileOnly dependency coordinates in the JARs themselves or as
    //       sidecar artifacts.
    micronautApi("org.graalvm.sdk:graal-sdk:25.0.1")
    micronautApi("javax.cache:cache-api:1.1.1") // for micronaut-cache JCache support
    micronautApi("io.micronaut:micronaut-management") // for security SensitiveEndpointRule
    micronautApi("io.micronaut:micronaut-inject-java") // for io.micronaut.inject.visitor.VisitorContext
    micronautApi("de.malkusch.whois-server-list:public-suffix-list:2.2.0") // for multitenancy PublicSuffixList
    micronautApi("io.reactivex.rxjava2:rxjava:2.2.21") // for RxJavaCrudRepository signatures
    micronautApi("jakarta.persistence:jakarta.persistence-api:3.2.0") // for JPA Criteria API types
    // Some Micronaut modules still reference javax.persistence in annotations; provide legacy API too
    micronautApi("javax.persistence:javax.persistence-api:2.2")
    micronautApi("org.apache.groovy:groovy")
    micronautApi("javax.inject:javax.inject:1")
    micronautApi("jakarta.data:jakarta.data-api:1.0.1")
    micronautApi("io.netty:netty-pkitesting")
    micronautApi("io.micronaut.security:micronaut-security-csrf")
    micronautApi("io.micronaut.security:micronaut-security-session")

    // We exclude the following as not relevant to Python devs.
    // micronautApi("io.micronaut.spring:micronaut-spring")
    // micronautApi("io.micronaut.servlet:micronaut-servlet-core")
    // micronautApi("io.micronaut.guice:micronaut-guice")
}

// Generate a single Python module from the merged Micronaut API sources
val pyi by tasks.registering(PyiFromDependencySources::class) {
    group = "build"
    description = "Generate Python stubs for Micronaut public API modules"
    configuration.set(micronautApi.get())
    destinationDir.set(layout.buildDirectory.dir("pymodule"))
    // Map canonical Java package to a concise Python package name
    packageMap.set("io.micronaut=micronaut")
    // Exclude optional integrations and impl details
    excludePrefixes.set(
        listOf(
            "io.micronaut.grpc",
            "io.micronaut.http.client.netty",
            "io.micronaut.http.netty",
            "io.micronaut.http.server.netty",
            // exclude mgmt endpoints (not core API surface for Python consumers)
            "io.micronaut.logging.impl",
            "io.micronaut.xml.jackson.server",
            "io.micronaut.management",
            // exclude adapters/integrations with other large Java frameworks
            "io.micronaut.spring",
            "io.micronaut.servlet",
            "io.micronaut.reactor",
            "io.micronaut.rxjava2",
            "io.micronaut.rxjava3",
            // Micronaut Security expressions sources refer to a non-published artifact; skip them.
            "io.micronaut.security.annotation.expressions",
            // Exclude Micrometer metrics binders and adapters that pull optional deps (r2dbc, netty, logback, jdbc metadata)
            "io.micronaut.configuration.metrics.binder",
            "io.micronaut.configuration.metrics.binder.netty",
            "io.micronaut.configuration.metrics.binder.datasource",
            "io.micronaut.configuration.metrics.binder.logging",
            "io.micronaut.configuration.metrics.binder.r2dbc",
            // Exclude Netty access-log session helpers
            "io.micronaut.session.http",
            // Exclude test-only transaction listener referencing spring/test artifacts
            "io.micronaut.transaction.test"
        ).joinToString(",")
    )
    // New module name in this repository context
    moduleName.set("pyronaut-api")
    moduleVersion.set(micronautVersionFromCatalog)
}

tasks.build {
    dependsOn(pyi)
}
