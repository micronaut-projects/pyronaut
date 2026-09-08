import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.TreeSet

plugins {
    id("io.micronaut.build.internal.pyronaut-module")
    id("application")
    id("org.graalvm.buildtools.native")
}

val micronautPlatformVersion = providers.gradleProperty("pyronaut.micronaut.platform.version")
val controlPanelRuntime by configurations.creating
val nativeBundleOs = when {
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    else -> System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"), "-")
}
val nativeBundleArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "x86_64", "amd64" -> "amd64"
    else -> System.getProperty("os.arch").lowercase()
}
val nativeImageOutputDirectory = layout.buildDirectory.dir("native/nativeCompile")

dependencies {
    implementation(project(":micronaut-pyronaut-build-annotations"))
    api(platform("io.micronaut.platform:micronaut-platform:${micronautPlatformVersion.get()}"))
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnPicocli.picocli.codegen)

    // platform processors
    implementation(mn.micronaut.inject.python)
    implementation(project(":micronaut-pyronaut-direct-source"))
    implementation(project(":micronaut-pyronaut-processor"))
    implementation(mnSerde.micronaut.serde.processor)
    implementation(mnValidation.micronaut.validation.processor)
    implementation("io.micronaut.data:micronaut-data-processor") {
        // The processor only needs the generated parser runtime. Do not ship
        // the ANTLR tool and its legacy runtime/template dependencies.
        exclude(group = "org.antlr", module = "antlr4")
    }
    implementation("io.micronaut.security:micronaut-security-processor")
    implementation("io.micronaut.micrometer:micronaut-micrometer-annotation")
    implementation("io.micronaut.jaxrs:micronaut-jaxrs-processor")
    implementation("io.micronaut.sourcegen:micronaut-sourcegen-generator-java")
    implementation("io.micronaut.sourcegen:micronaut-sourcegen-model")
    implementation(mnOpenapi.micronaut.openapi)
    // The native processor image must contain the optional OpenAPI ADOC
    // converter and its pegdown classes; application-only processor jars
    // cannot be loaded later through a native URLClassLoader.
    implementation("io.micronaut.openapi:micronaut-openapi-adoc")

    // CLI modules
    implementation(project(":micronaut-pyronaut-install"))
    implementation(project(":micronaut-pyronaut-config-model"))
    // TODO: this drags in a huge graph of dependencies so exclude for now
    implementation(project(":micronaut-pyronaut-run-python"))
    implementation(project(":micronaut-pyronaut-test"))
    implementation(project(":micronaut-pyronaut-test-resources-server"))
    implementation(project(":micronaut-pyronaut-pytest"))
    implementation(project(":micronaut-pyronaut-native-build"))
    implementation(project(":micronaut-pyronaut-validate-config"))
    implementation(project(":micronaut-pyronaut-logback"))


    implementation(mnPicocli.picocli)

    // Bundled only for --control-panel direct development launches. These
    // artifacts are intentionally outside the normal launcher runtime graph.
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-core:${libs.versions.micronaut.control.panel.get()}") {
        exclude(group = "io.projectreactor", module = "reactor-core")
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
        exclude(group = "org.jspecify", module = "jspecify")
        exclude(group = "io.micronaut", module = "micronaut-inject")
        exclude(group = "io.micronaut", module = "micronaut-discovery-core")
        exclude(group = "io.micronaut", module = "micronaut-json-core")
        exclude(group = "io.micronaut", module = "micronaut-router")
        exclude(group = "io.micronaut", module = "micronaut-core")
        exclude(group = "io.micronaut", module = "micronaut-http-server")
        exclude(group = "io.micronaut", module = "micronaut-management")
        exclude(group = "io.micronaut.reactor", module = "micronaut-reactor")
    }
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-management:${libs.versions.micronaut.control.panel.get()}") {
        exclude(group = "io.projectreactor", module = "reactor-core")
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
        exclude(group = "org.jspecify", module = "jspecify")
        exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-core")
        exclude(group = "io.micronaut", module = "micronaut-inject")
        exclude(group = "io.micronaut", module = "micronaut-core")
        exclude(group = "io.micronaut", module = "micronaut-http-server")
        exclude(group = "io.micronaut", module = "micronaut-management")
        exclude(group = "io.micronaut.reactor", module = "micronaut-reactor")
    }
    "controlPanelRuntime"("io.micronaut.controlpanel:micronaut-control-panel-ui:${libs.versions.micronaut.control.panel.get()}") {
        exclude(group = "io.projectreactor", module = "reactor-core")
        exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-core")
        exclude(group = "org.jspecify", module = "jspecify")
        exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
        exclude(group = "io.micronaut", module = "micronaut-inject")
        exclude(group = "io.micronaut", module = "micronaut-core")
        exclude(group = "io.micronaut", module = "micronaut-http-server")
        exclude(group = "io.micronaut", module = "micronaut-management")
        exclude(group = "io.micronaut.reactor", module = "micronaut-reactor")
    }


    // runtime build in modules
    api(mnOpenapi.micronaut.openapi.annotations)
    api("io.micronaut.data:micronaut-data-model")
    api("io.micronaut.data:micronaut-data-runtime")
    api("io.micronaut.data:micronaut-data-connection")
    api("io.micronaut.sql:micronaut-jdbc")
    api("io.micronaut.cache:micronaut-cache-core")
    api("io.micronaut.sourcegen:micronaut-sourcegen-annotations")
    api("io.micronaut.views:micronaut-views-core")
    api("io.micronaut:micronaut-management")
    api(mnSerde.micronaut.serde.jackson)
    api(mn.micronaut.context.python.netty)
    api(mn.micronaut.context.python)
    api(mn.micronaut.runtime)
    api(mn.micronaut.retry)
    api(libs.micronaut.toml)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.messaging)
    api(mn.micronaut.websocket)

    api(mnValidation.micronaut.validation)

    // testing API
    api(mnTest.micronaut.test.junit5) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    api(mnTest.junit.jupiter.api)

    implementation(mnTest.junit.jupiter.engine)
    implementation(mnTest.junit.platform.launcher)

}

tasks.named<Test>("test") {
    // Direct-source tutorial tests launch a child JVM from the exploded test
    // classpath, so package implementation metadata is not available there.
    // Pass the Gradle project version explicitly for snapshot BOM resolution.
    systemProperty("pyronaut.version", project.version.toString())
    dependsOn(project(":micronaut-pyronaut").tasks.named("prepareSdkMavenLocalEnvironment"))
}

val bundleControlPanelJars by tasks.registering(Sync::class) {
    from(controlPanelRuntime)
    into(layout.buildDirectory.dir("generated/control-panel-libs"))
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-simple")
    // GraalPy only needs this optional support module for legacy private-key formats.
    exclude(group = "org.graalvm.python", module = "python-bouncycastle-support")
}

configurations.named("nativeImageClasspath") {
    exclude(group = "org.openrewrite", module = "rewrite-kotlin")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-client")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-server")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-control-panel")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-core")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-management")
    exclude(group = "io.micronaut.controlpanel", module = "micronaut-control-panel-ui")
}

configurations.named("runtimeClasspath") {
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-server")
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-control-panel")
    // Control Panel is an optional development distribution.  It must not be
    // part of pyronaut-dev's native image runtime graph; the jars are bundled
    // separately and added only for an explicit --control-panel launch.
    exclude(group = "io.micronaut.controlpanel")
}

// The native parent image already contains these modules.  Keep their Maven
// identities alongside the distribution so runtime launchers can remove a
// project dependency even when it resolved to a different version.
val nativeCompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.api.get())
}

val nativeProvidedSourcesDirectory = layout.buildDirectory.dir("generated/native-provided-sources")
val copyNativeProvidedSources by tasks.registering {
    val outputDirectory = nativeProvidedSourcesDirectory
    outputs.dir(outputDirectory)
    inputs.files(configurations.nativeImageClasspath)
    doLast {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        configurations.nativeImageClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val dependency = dependencies.create(
                "${artifact.moduleVersion.id.group}:${artifact.name}:${artifact.moduleVersion.id.version}:sources@jar"
            )
            val source = configurations.detachedConfiguration(dependency).apply {
                isTransitive = false
            }
            runCatching { source.singleFile }
                .getOrNull()
                ?.takeIf { it.isFile }
                ?.let { sourceFile -> sourceFile.copyTo(output.resolve(sourceFile.name), overwrite = true) }
        }
    }
}

val writeNativeClasspathManifests by tasks.registering {
    val outputDirectory = layout.buildDirectory.dir("generated/native-classpaths")
    outputs.dir(outputDirectory)
    inputs.files(nativeCompileClasspath)
    // Do not declare nativeImageClasspath as a task input: it contains this
    // project's JAR and would make processResources depend on jar.
    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        val nativeArtifacts = configurations.nativeImageClasspath.get().resolvedConfiguration.resolvedArtifacts
        directory.resolve("native-provided-classpath.txt").writeText(
            nativeArtifacts
                .map { "${it.moduleVersion.id.group}:${it.name}" }
                .distinct()
                .sorted()
                .joinToString("\n", postfix = "\n")
        )
        val compileArtifactsByFile = nativeCompileClasspath.resolvedConfiguration.resolvedArtifacts
            .associateBy { it.file.canonicalFile }
        val compileEntries = nativeCompileClasspath.files
            .map { file ->
                val artifact = checkNotNull(compileArtifactsByFile[file.canonicalFile]) {
                    "Missing Maven coordinates for native classpath entry: $file"
                }
                listOf(
                    "maven",
                    artifact.moduleVersion.id.group,
                    artifact.name,
                    artifact.moduleVersion.id.version,
                    artifact.extension,
                    artifact.classifier ?: "",
                    artifact.file.name
                ).joinToString("\t")
            }
            .distinct()
        compileEntries.forEach { entry ->
            val fields = entry.split("\t")
            check(fields.size == 7 && fields[0] == "maven") { "Invalid native classpath descriptor: $entry" }
            check(fields.subList(1, 5).all { it.isNotBlank() && '/' !in it && '\\' !in it }) {
                "Invalid native classpath coordinate: $entry"
            }
            check('/' !in fields[5] && '\\' !in fields[5]) { "Invalid native classpath classifier: $entry" }
            val classifier = fields[5].takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
            val expectedFileName = "${fields[2]}-${fields[3]}$classifier.${fields[4]}"
            check(fields[6] == expectedFileName && !File(fields[6]).isAbsolute && File(fields[6]).name == fields[6]) {
                "Native classpath descriptor contains a host path: $entry"
            }
        }
        directory.resolve("native-compile-classpath.txt").writeText(
            compileEntries.joinToString("\n", postfix = "\n")
        )
    }
}

val writeNativeAnnotationProcessorOptions by tasks.registering {
    val outputDirectory = layout.buildDirectory.dir("generated/native-annotation-processor-options")
    outputs.dir(outputDirectory)
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val options = TreeSet<String>()
        val urls = configurations.runtimeClasspath.get().files
            .map { it.toURI().toURL() }
            .toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val visitorType = Class.forName("io.micronaut.inject.visitor.TypeElementVisitor", true, loader)
            ServiceLoader.load(visitorType, loader).stream().forEach { provider ->
                try {
                    val visitorName = provider.type().name
                    if (!visitorName.startsWith("io.micronaut.data") &&
                        !visitorName.startsWith("io.micronaut.python.processing")) {
                        val visitor = provider.get()
                        val supported = visitorType.getMethod("getSupportedOptions").invoke(visitor)
                        if (supported is Collection<*>) {
                            supported.filterIsInstance<String>()
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .forEach(options::add)
                        }
                    }
                } catch (_: ReflectiveOperationException) {
                    // Visitors with optional dependencies do not contribute options.
                } catch (_: LinkageError) {
                    // Visitors with optional dependencies do not contribute options.
                } catch (_: RuntimeException) {
                    // Visitors with optional dependencies do not contribute options.
                }
            }
        }
        val resource = outputDirectory.get().file(
            "META-INF/pyronaut/native-annotation-processor-options.txt"
        ).asFile
        resource.parentFile.mkdirs()
        resource.writeText(options.joinToString("\n", postfix = "\n"))
    }
}

tasks.processResources {
    from(writeNativeAnnotationProcessorOptions)
}

distributions {
    named("main") {
        contents {
            into("lib/control-panel") {
                from(bundleControlPanelJars)
            }
            into("bin") {
                from(writeNativeClasspathManifests)
            }
        }
    }
}

application {
    mainClass = "io.micronaut.pyronaut.dev.PyronautDevMain"
    applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val runtimeMetadataExclusion = providers.provider {
    val excludedArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .filter { artifact ->
            val group = artifact.moduleVersion.id.group
            val name = artifact.name
            name == "micronaut-test-resources-server" ||
                name == "micronaut-test-resources-control-panel" ||
                group == "io.micronaut.controlpanel"
        }
        .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
    buildList {
        excludedArtifacts.forEach { jar ->
            add("--exclude-config")
            add("\\Q$jar\\E")
            add("^/META-INF/native-image/.*")
        }
    configurations.nativeImageClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { artifact ->
                artifact.moduleVersion.id.group == "io.micronaut" && artifact.name == "micronaut-core"
            }
            .map { artifact -> artifact.file.toPath().toAbsolutePath().normalize() }
            .forEach { jar ->
                add("--exclude-config")
                add("\\Q$jar\\E")
                add("^/META-INF/native-image/io\\.micronaut/micronaut-core/native-image\\.properties$")
            }
    }
}

val nativeImageCLibraryPathArgs = providers.provider {
    val javaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() } ?: return@provider emptyList<String>()
    val clibrariesDir = File(javaHome, "lib/svm/clibraries")
    if (!clibrariesDir.isDirectory) {
        return@provider emptyList<String>()
    }
    val osArch = System.getProperty("os.arch").lowercase()
    val platformDir = when {
        osArch.contains("aarch64") || osArch.contains("arm64") -> File(clibrariesDir, "darwin-aarch64")
        else -> null
    }
    buildList {
        platformDir?.takeIf { it.isDirectory }?.let { add(it.absolutePath) }
        add(clibrariesDir.absolutePath)
    }.takeIf { it.isNotEmpty() }
        ?.let { listOf("-H:CLibraryPath=${it.joinToString(",")}") }
        ?: emptyList()
}

val nativeImageRuntimeArgs = listOf(
    "-Os",
    "--enable-native-access=org.graalvm.truffle",
    "--add-modules=jdk.compiler,java.net.http,java.naming,java.rmi",
    "-H:+UnlockExperimentalVMOptions",
    "-H:+CopyLanguageResources",
    "-H:EnableURLProtocols=jar",
    "-H:+RuntimeClassLoading",
    "-H:+AllowJRTFileSystem",
    "-H:+SharedArenaSupport",
    "-H:-SupportCompileInIsolates",
    "--features=io.micronaut.core.io.service.PyronautDevServiceLoaderFeature",
    "--enable-http",
    "--enable-https",
    // Modules
    "-H:Preserve=module=java.base,module=java.sql",

    /* java.* */
    "-H:Preserve=package=java.applet.*",
    // "-H:Preserve=package=java.awt.*",
    "-H:Preserve=package=java.beans.*",
    "-H:Preserve=package=java.io.*",
    "-H:Preserve=package=java.lang.*",
    "-H:Preserve=package=java.math.*",
    "-H:Preserve=package=java.net.*",
    "-H:Preserve=package=java.nio.*",
    "-H:Preserve=package=java.rmi.*",
    "-H:Preserve=package=java.security.*",
    "-H:Preserve=package=java.sql.*",
    "-H:Preserve=package=java.text.*",
    "-H:Preserve=package=java.time.*",
    "-H:Preserve=package=java.util.*",

    /* sun.* */
    // "-H:Preserve=package=sun.awt.*",
    // "-H:Preserve=package=sun.datatransfer.*",
    // "-H:Preserve=package=sun.font.*",
    "-H:Preserve=package=sun.instrument.*",
    "-H:Preserve=package=sun.invoke.*",
    // "-H:Preserve=package=sun.java2d.*",
    // "-H:Preserve=package=sun.launcher.*",
    // "-H:Preserve=package=sun.lwawt.*",
    "-H:Preserve=package=sun.management.*",
    "-H:Preserve=package=sun.misc.*",
    "-H:Preserve=package=sun.net.*",
    "-H:Preserve=package=sun.nio.*",
    "-H:Preserve=package=sun.print.*",
    "-H:Preserve=package=sun.reflect.*",
    "-H:Preserve=package=sun.rmi.*",
    "-H:Preserve=package=sun.security.*",
    // "-H:Preserve=package=sun.swing.*",
    "-H:Preserve=package=sun.text.*",
    // "-H:Preserve=package=sun.tools.*",
    "-H:Preserve=package=sun.util.*",

    /* javax.* */
    "-H:Preserve=package=javax.management.*",
    "-H:Preserve=package=javax.lang.*",
    "-H:Preserve=package=javax.annotation.processing.*",
    "-H:Preserve=package=javax.sql",
    "-H:Preserve=package=javax.xml.parsers",
    "-H:Preserve=package=javax.xml.transform.dom",
    "-H:Preserve=package=javax.xml.transform.sax",
    "-H:Preserve=package=javax.xml.transform",
    "-H:Preserve=package=javax.xml.validation",
    "-H:Preserve=package=javax.xml.xpath",
    "-H:Preserve=package=javax.xml",

    /* jakarta.* */
    "-H:Preserve=package=jakarta.*",
    "-H:Preserve=package=jakarta.annotation.*",
    "-H:Preserve=package=jakarta.inject.*",
    "-H:Preserve=package=jakarta.validation.*",
    "-H:Preserve=package=jakarta.persistence.*",
    "-H:Preserve=package=jakarta.transaction.*",

    /* jdk.internal.* */
    "-H:Preserve=package=jdk.internal.misc.*",
    "-H:Preserve=package=jdk.internal.access.*",

    /* io.micronaut.* */
    "-H:Preserve=package=io.micronaut.python.*",
    "-H:Preserve=package=io.micronaut.annotation.processing.visitor.*",
    "-H:Preserve=package=io.micronaut.annotation.processing.*",
    "-H:Preserve=package=io.micronaut.cache.*",
    "-H:Preserve=package=io.micronaut.data.*",
    "-H:Preserve=package=io.micronaut.discovery.*",
    "-H:Preserve=package=io.micronaut.transaction.*",
    "-H:Preserve=package=io.micronaut.jdbc.*",
    "-H:Preserve=package=io.micronaut.management.*",
    "-H:Preserve=package=io.micronaut.messaging.*",
    "-H:Preserve=package=io.micronaut.reactor.*",
    "-H:Preserve=package=io.micronaut.core.annotation.*",
    "-H:Preserve=package=io.micronaut.core.beans.*",
    "-H:Preserve=package=io.micronaut.core.convert.*",
    "-H:Preserve=package=io.micronaut.core.async.*",
    "-H:Preserve=package=io.micronaut.core.exceptions.*",
    "-H:Preserve=package=io.micronaut.core.order.*",
    "-H:Preserve=package=io.micronaut.core.propagation.*",
    "-H:Preserve=package=io.micronaut.expressions.*",
    "-H:Preserve=package=io.micronaut.context.visitor.*",
    "-H:Preserve=package=io.micronaut.validation.*",
    "-H:Preserve=package=io.micronaut.core.naming.*",
    "-H:Preserve=package=io.micronaut.core.reflect.*",
    "-H:Preserve=package=io.micronaut.core.type.*",
    "-H:Preserve=package=io.micronaut.core.util.*",
    "-H:Preserve=package=io.micronaut.core.io.service.*",
    "-H:Preserve=package=io.micronaut.buffer.netty.*",
    "-H:Preserve=package=io.micronaut.inject.*",
    "-H:Preserve=package=io.micronaut.context.*",
    "-H:Preserve=package=io.micronaut.scheduling.*",
    "-H:Preserve=package=io.micronaut.security.annotation.*",
    "-H:Preserve=package=io.micronaut.runtime.*",
    "-H:Preserve=package=io.micronaut.http.*",
    "-H:Preserve=package=io.micronaut.websocket.*",
    "-H:Preserve=package=io.micronaut.http.netty.*",
    "-H:Preserve=package=io.micronaut.aop.*",
    "-H:Preserve=package=io.micronaut.jackson.*",
    "-H:Preserve=package=io.micronaut.json.*",
    "-H:Preserve=package=io.micronaut.serde.*",
    "-H:Preserve=package=io.micronaut.toml.*",
    "-H:Preserve=package=io.micronaut.views.*",
    "-H:Preserve=package=io.micronaut.web.router.*",


    /* netty.* */
    "-H:Preserve=package=io.netty.channel.nio",
    "-H:Preserve=package=io.netty.channel",
    "-H:Preserve=package=io.netty.handler.codec.http.*",
    "-H:Preserve=package=io.netty.handler.ssl",
    "-H:Preserve=package=io.netty.resolver.*",
    "-H:Preserve=package=io.netty.util.concurrent",
    "-H:Preserve=package=io.netty.util",
    "-H:Preserve=package=io.netty.util.internal.logging.*",

    /* reactor.* */
    "-H:Preserve=package=reactor.core.*",
    "-H:Preserve=package=reactor.util.*",

    /* other runtime APIs */
    "-H:Preserve=package=javax.xml.namespace.*",
    "-H:Preserve=package=org.reactivestreams.*",

    /* other */
    "-H:Preserve=package=com.fasterxml.jackson.annotation.*",
    "-H:Preserve=package=org.slf4j.*",
    "-H:Preserve=package=org.w3c.dom.bootstrap",
    "-H:Preserve=package=org.w3c.dom.events",
    "-H:Preserve=package=org.w3c.dom.ls",
    "-H:Preserve=package=org.w3c.dom",
    "-H:Preserve=package=org.xml.sax.ext",
    "-H:Preserve=package=org.xml.sax.helpers",
    "-H:Preserve=package=org.xml.sax",
    "-H:Preserve=package=tools.jackson.core.*",
    "-H:Preserve=package=tools.jackson.databind.*",
    "-H:Preserve=package=io.swagger.v3.oas.models.*",
    // OpenAPI ADOC loads pegdown AST/parser types reflectively.
    "-H:Preserve=package=org.pegdown.*",
    "-H:Preserve=package=org.parboiled.*",
    // Parboiled transforms the PegDown parser on the first use. Its ASM
    // transformer reads the original class files through ClassLoader
    // resources, which native-image does not retain unless they are included
    // explicitly.
    "-H:IncludeResources=org/pegdown/.*\\.class",
    "-H:IncludeResources=org/parboiled/.*\\.class",
    "-H:IncludeResources=template/.*",
    "-H:IncludeResources=templates/.*",
    "-H:Preserve=package=io.micronaut.test.*",
    "-H:Preserve=package=io.micronaut.testresources.*",
    "-H:Preserve=package=io.micronaut.test.pytest.*",
    "-H:Preserve=package=org.junit.platform.*",
    "-H:Preserve=package=org.junit.jupiter.*",
    "-H:Preserve=package=org.apache.maven.*",
    "-H:Preserve=package=org.eclipse.aether.*",
    "-H:Preserve=package=org.codehaus.plexus.*",
    "-H:Preserve=package=com.github.javaparser.*",
    "-H:Preserve=package=ch.qos.logback.*",
    "-H:Preserve=package=org.graalvm.polyglot",
    "-H:-PrintRestrictHeapAccessWarnings",
    "-H:IncludeResources=pyronaut-test-resources-logback\\.xml",

// Jakarta

// Processor
    "--initialize-at-build-time=jakarta.annotation",
    "--initialize-at-build-time=jakarta.inject",
    "--initialize-at-build-time=jakarta.validation",
    "--initialize-at-build-time=jakarta.persistence",
    "--initialize-at-build-time=jakarta.transaction",
    "--initialize-at-build-time=com.sun.tools.javac.api.JavacTool",
    "--initialize-at-build-time=io.micronaut.sourcegen.model,org.objectweb.asm",
    "--initialize-at-build-time=com.github.javaparser",
    "--initialize-at-build-time=io.micronaut.annotation.processing",
    "--initialize-at-build-time=io.micronaut.inject.annotation",
    "--initialize-at-build-time=io.micronaut.inject.beans",
    "--initialize-at-build-time=io.micronaut.inject.beans.visitor",
    "--initialize-at-build-time=io.micronaut.inject.processing",
    "--initialize-at-build-time=io.micronaut.inject.writer",
    "--initialize-at-build-time=io.micronaut.inject.provider",
    "--initialize-at-build-time=io.micronaut.inject.validation",
    "--initialize-at-build-time=io.micronaut.python.compiler",
    "--initialize-at-build-time=io.micronaut.python.processing",
    "--initialize-at-build-time=io.micronaut.python.processing.annotation",
    "--initialize-at-build-time=io.micronaut.python.processing.beans",
    "--initialize-at-build-time=io.micronaut.python.processing.util",
    "--initialize-at-build-time=io.micronaut.python.processing.visitor",
    "--initialize-at-build-time=io.micronaut.aop.mapper",
    "--initialize-at-build-time=io.micronaut.context.visitor",

// Core
    "--initialize-at-build-time=io.micronaut.core.io",
    "--initialize-at-build-time=io.micronaut.core.optim",
    "--initialize-at-build-time=io.micronaut.core.async.publisher.PublishersOptimizations",
    "--initialize-at-build-time=io.micronaut.core.util",
    "--initialize-at-build-time=io.micronaut.core.bind",
    "--initialize-at-build-time=io.micronaut.core.convert",
    "--initialize-at-build-time=io.micronaut.core.convert.ConversionContext",
    "--initialize-at-build-time=io.micronaut.core.convert.ImmutableArgumentConversionContext",
    "--initialize-at-build-time=io.micronaut.core.type",
    "--initialize-at-build-time=io.micronaut.core.annotation",
    "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValue",
    "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValueResolver",
    "--initialize-at-build-time=io.micronaut.core.reflect.ReflectionUtils",
    "--initialize-at-build-time=io.micronaut.core.reflect.ClassUtils\$Optimizations",
    "--initialize-at-build-time=io.micronaut.scheduling.LoomSupport",
    "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport",
    "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport\$PrivateLoomCondition",
    "--initialize-at-build-time=io.micronaut.http.MediaType",
    "--initialize-at-build-time=io.micronaut.http.annotation",
    "--initialize-at-build-time=io.micronaut.messaging",
    "--initialize-at-build-time=io.micronaut.messaging.annotation",
    "--initialize-at-build-time=io.micronaut.messaging.exceptions",
    "--initialize-at-build-time=io.micronaut.management.endpoint",
    "--initialize-at-build-time=io.micronaut.management.endpoint.annotation",
    "--initialize-at-build-time=io.micronaut.management.endpoint.health",
    "--initialize-at-build-time=io.micronaut.management.endpoint.indicator.annotation",
    "--initialize-at-build-time=io.micronaut.retry.annotation",
    "--initialize-at-build-time=io.micronaut.retry.event",
    "--initialize-at-build-time=io.micronaut.retry.exception",
//    Pyronaut
    "--initialize-at-build-time=io.micronaut.pyronaut.install",
    "--initialize-at-build-time=io.micronaut.pyronaut.processor",
    "--initialize-at-build-time=io.micronaut.pyronaut.testresources",
    "--initialize-at-build-time=io.micronaut.pyronaut.nativebuild",
    "--initialize-at-build-time=io.micronaut.pyronaut.config.classloader",
    "--initialize-at-build-time=io.micronaut.pyronaut.config.model",
//    SourceGen
    "--initialize-at-build-time=io.micronaut.sourcegen",
    "--initialize-at-build-time=io.micronaut.sourcegen.annotations",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode.expression",
    "--initialize-at-build-time=io.micronaut.sourcegen.bytecode.statement",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator.bytecode",
    "--initialize-at-build-time=io.micronaut.sourcegen.generator.visitors",
    "--initialize-at-build-time=io.micronaut.sourcegen.info",
    "--initialize-at-build-time=io.micronaut.sourcegen.javapoet",
    "--initialize-at-build-time=io.micronaut.sourcegen.model",

// Serde
    "--initialize-at-build-time=tools.jackson.core.io",
    "--initialize-at-build-time=tools.jackson.core.sym",
    "--initialize-at-build-time=tools.jackson.core.util",
    "--initialize-at-build-time=tools.jackson.core.json",
    "--initialize-at-build-time=tools.jackson.core.Version",
    "--initialize-at-build-time=tools.jackson.core.json.JsonFactory",
    "--initialize-at-build-time=tools.jackson.core",
    "--initialize-at-build-time=io.micronaut.json",
    "--initialize-at-build-time=io.micronaut.json.bind",
    "--initialize-at-build-time=io.micronaut.json.body",
    "--initialize-at-build-time=io.micronaut.json.convert",
    "--initialize-at-build-time=io.micronaut.json.codec",
    "--initialize-at-build-time=io.micronaut.json.tree",
    "--initialize-at-build-time=io.micronaut.serde.processor",
    "--initialize-at-build-time=io.micronaut.serde.annotation",
    "--initialize-at-build-time=io.micronaut.serde.configuration",
    "--initialize-at-build-time=io.micronaut.serde",
    "--initialize-at-build-time=io.micronaut.serde.exceptions",
    "--initialize-at-build-time=io.micronaut.serde.reference",
    "--initialize-at-build-time=io.micronaut.serde.util",
    "--initialize-at-run-time=io.micronaut.serde.support.util",

// OpenAPI
    "--initialize-at-build-time=io.swagger.v3.oas.models",
    "--initialize-at-build-time=io.micronaut.openapi.javadoc",
    "--initialize-at-build-time=io.micronaut.openapi.annotation",
    "--initialize-at-build-time=io.micronaut.openapi.view",
    "--initialize-at-build-time=io.micronaut.openapi.visitor",
    "--initialize-at-build-time=io.micronaut.openapi.OpenApiUtils",
    "--initialize-at-build-time=io.micronaut.openapi.swagger.core.util",
    "--initialize-at-build-time=io.micronaut.openapi.swagger.core.jackson",
    "--initialize-at-build-time=io.micronaut.openapi.swagger.core.jackson.mixin",
    "--initialize-at-build-time=com.fasterxml.jackson.annotation",
    "--initialize-at-build-time=tools.jackson.databind.json.JsonMapper",
    "--initialize-at-build-time=tools.jackson.databind.DeserializationConfig",
    "--initialize-at-build-time=tools.jackson.databind.PropertyName",
    "--initialize-at-build-time=tools.jackson.databind.cfg",
    "--initialize-at-build-time=tools.jackson.databind.introspect",
    "--initialize-at-build-time=tools.jackson.databind.introspect.JacksonAnnotationIntrospector",
    "--initialize-at-build-time=tools.jackson.databind.node",
    "--initialize-at-build-time=tools.jackson.databind.type",
    "--initialize-at-build-time=tools.jackson.databind.type.SimpleType",
    "--initialize-at-build-time=tools.jackson.databind.ObjectWriter",
    "--initialize-at-build-time=tools.jackson.databind",
    "--initialize-at-build-time=tools.jackson.databind.jsontype",
    "--initialize-at-build-time=tools.jackson.dataformat.yaml",
    "--initialize-at-build-time=org.snakeyaml.engine.v2.common",
    "--initialize-at-build-time=org.snakeyaml.engine.v2",
    "--initialize-at-build-time=org.snakeyaml.engine.external",
    "--initialize-at-build-time=io.micronaut.openapi.visitor.ConvertUtils\$1",
    "--initialize-at-build-time=com.vladsch.flexmark.html2md.converter",
    "--initialize-at-build-time=com.vladsch.flexmark.util.sequence",
    "--initialize-at-build-time=com.vladsch.flexmark.util.misc",
    "--initialize-at-build-time=com.vladsch.flexmark.util.data",
    "--initialize-at-build-time=com.vladsch.flexmark.util.html",
    "--initialize-at-run-time=com.vladsch.flexmark.util.sequence.Escaping",

// Validation
    "--initialize-at-build-time=io.micronaut.validation",
    "--initialize-at-build-time=io.micronaut.validation.validator",
    "--initialize-at-build-time=io.micronaut.validation.validator.DefaultAnnotatedElementValidator",
    "--initialize-at-build-time=io.micronaut.validation.annotation",
    "--initialize-at-build-time=io.micronaut.validation.exceptions",
    "--initialize-at-build-time=io.micronaut.validation.validator",
    "--initialize-at-build-time=io.micronaut.validation.validator.constraints",
    "--initialize-at-build-time=io.micronaut.validation.validator.extractors",
    "--initialize-at-build-time=io.micronaut.validation.validator.messages",
    "--initialize-at-build-time=io.micronaut.validation.validator.resolver",

// Data
    "--initialize-at-build-time=io.micronaut.data.annotation",
    "--initialize-at-build-time=io.micronaut.data.exceptions",
    "--initialize-at-build-time=io.micronaut.data.event",
    "--initialize-at-build-time=io.micronaut.data.intercept",
    "--initialize-at-build-time=io.micronaut.data.intercept.annotation",
    "--initialize-at-build-time=io.micronaut.data.intercept.async",
    "--initialize-at-build-time=io.micronaut.data.intercept.reactive",
    "--initialize-at-build-time=io.micronaut.data.procesor.visitors",
    "--initialize-at-build-time=io.micronaut.data.procesor.model",
    "--initialize-at-build-time=io.micronaut.data.procesor.mappers.jakarta.data",
    "--initialize-at-build-time=io.micronaut.data.runtime.intercept",
    "--initialize-at-build-time=io.micronaut.data.runtime.intercept.async",
    "--initialize-at-build-time=io.micronaut.data.runtime.intercept.reactive",
    "--initialize-at-build-time=io.micronaut.data.runtime.intercept.criteria",
    "--initialize-at-build-time=io.micronaut.data.runtime.convert",


// JSON Schema
    "--initialize-at-build-time=io.micronaut.jsonschema.configuration.validator",
    "--initialize-at-run-time=io.micronaut.jsonschema.configuration.validator.DefaultDependencyInjectionValidator",

//    Runtime Init
    "--initialize-at-run-time=io.micronaut",
    "--initialize-at-run-time=io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder",
    "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
    "--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm",
    "--initialize-at-run-time=ch.qos.logback.classic.Logger",
    "--initialize-at-run-time=io.netty",
    "--initialize-at-run-time=ch.qos.logback",
    "--initialize-at-run-time=io.micronaut.testresources.client",
    "--initialize-at-run-time=io.micronaut.testresources.embedded",
    "--initialize-at-run-time=io.micronaut.core.beans.BeanIntrospector,io.micronaut.core.beans.DefaultBeanIntrospector",
    "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
    "--initialize-at-run-time=io.micronaut.core.util.KotlinUtils",
    "--initialize-at-run-time=io.micronaut.core.type.RuntimeTypeInformation\$LazyTypeInfo",
    "--initialize-at-run-time=io.micronaut.retry.intercept.CircuitBreakerRetry",
    "--initialize-at-run-time=io.micronaut.core.async.subscriber.CompletionAwareSubscriber",
    "-H:-UnlockExperimentalVMOptions"
)

val nativeImagePgoArgs = providers.provider {
    val instrument = providers.gradleProperty("pyronautDevPgoInstrument")
        .map(String::toBoolean)
        .orElse(false)
        .get()
    val profile = providers.gradleProperty("pyronautDevPgoProfile")
        .orNull
        ?.takeIf(String::isNotBlank)

    when {
        instrument -> listOf("--pgo-instrument")
        profile != null -> listOf("--pgo=$profile")
        else -> emptyList()
    }
}

// Build reports are Enterprise-only; opt in with -PpyronautDevEmitBuildReport=true.
val nativeImageBuildReportArgs = providers.gradleProperty("pyronautDevEmitBuildReport")
    .map(String::toBoolean)
    .orElse(false)
    .map { enabled -> if (enabled) listOf("--emit", "build-report") else emptyList() }

tasks {
    startScripts {
        applicationName = "pyronaut-dev"
    }

    named("installDist") {
        dependsOn(writeNativeClasspathManifests)
        dependsOn(copyNativeProvidedSources)
    }

    val nativeCompileTask = named<BuildNativeImageTask>("nativeCompile")
    nativeCompileTask.configure {
        dependsOn(writeNativeClasspathManifests)
        doLast {
            val resourcesDirectory = nativeImageOutputDirectory.get().dir("resources").asFile
            check(resourcesDirectory.isDirectory) {
                "pyronaut-dev native image did not copy language resources beside the executable: $resourcesDirectory"
            }
        }
    }
    val nativeBundle = register<Tar>("nativeBundle") {
        group = "distribution"
        description = "Packages the versioned pyronaut-dev native image and runtime metadata"
        dependsOn(nativeCompileTask)
        dependsOn(writeNativeClasspathManifests)
        dependsOn(copyNativeProvidedSources)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set("pyronaut-dev-${nativeBundleOs}-${nativeBundleArch}-${project.version}.tar.gz")
        compression = Compression.GZIP
        from(nativeCompileTask.flatMap { it.outputFile }) {
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
        // GraalVM native-image may emit runtime libraries beside the
        // executable. Keep them at the bundle root with the launcher.
        from(nativeImageOutputDirectory) {
            include("*.so", "*.dylib", "*.dll", "resources/**")
        }
        from(layout.buildDirectory.dir("generated/native-classpaths")) {
            include("native-compile-classpath.txt", "native-provided-classpath.txt")
        }
    }
    named("assemble") {
        dependsOn(nativeBundle)
    }
    val testSourceSet = the<SourceSetContainer>()["test"]

    register<Test>("nativeSmokeTest") {
        group = "verification"
        description = "Runs smoke tests against pyronaut-dev native binary"
        dependsOn(nativeCompileTask)
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        doFirst {
            systemProperty("pyronaut.dev.native.binary", nativeCompileTask.get().outputFile.get().asFile.absolutePath)
        }
        include("**/PyronautDevNativeSmokeTest.class")
    }

    named("nativeTest") {
        dependsOn(named("nativeSmokeTest"))
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pyronaut-dev")
            sharedLibrary.set(false)
            buildArgs.addAll(nativeImageCLibraryPathArgs)
            buildArgs.addAll(nativeImageRuntimeArgs)
            buildArgs.addAll(nativeImageBuildReportArgs)
            buildArgs.addAll(nativeImagePgoArgs)
            buildArgs.addAll(runtimeMetadataExclusion)
            buildArgs.add("-H:-PreserveIncludesJNI")
        }
        all {
            resources.autodetect()
        }
    }
}
