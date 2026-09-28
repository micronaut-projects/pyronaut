package io.micronaut.pyronaut.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project

import java.nio.file.Files

/**
 * Profile-guided optimization settings shared by the pyronaut-dev, pyronaut-run and
 * pyronaut-run-python native images.
 *
 * <p>A release build runs three Gradle invocations with the same inputs:
 * <ol>
 *     <li>{@code -Ppyronaut.pgo=instrument} builds an instrumented image in place of the normal one.</li>
 *     <li>{@code :micronaut-pgo-training:train} runs the training workload against it and
 *     writes one {@code .iprof} file per launcher process.</li>
 *     <li>{@code -Ppyronaut.pgo=optimize} rebuilds the image with every profile and code compression.</li>
 * </ol>
 * Without the property the images are built exactly as before.
 */
final class PyronautPgo {
    static final String MODE_PROPERTY = "pyronaut.pgo"
    static final String PROFILES_PROPERTY = "pyronaut.pgo.profiles"
    static final String CODE_COMPRESSION_PROPERTY = "pyronaut.codeCompression"
    // PGO training can dispatch interpreted code to collection AOT methods missed by reachability analysis.
    static final String GRAALVM_COLLECTIONS_PRESERVE_ARG = "-H:Preserve=package=org.graalvm.collections"
    // Instrumented images are compiled at -O2 with profiling code and can outgrow native-image's
    // default heap (47% of RAM, capped at 30 GiB), for example -Ppyronaut.pgo.builderMaxHeap=52g.
    static final String BUILDER_MAX_HEAP_PROPERTY = "pyronaut.pgo.builderMaxHeap"
    // --pgo-instrument also collects call-stack samples by default. Sampling crashes instrumented
    // Crema images when interpreted code calls instrumented AOT code (pyronaut#203), and the
    // instrumentation counters provide most of the benefit, so it is off unless requested.
    static final String SAMPLING_PROPERTY = "pyronaut.pgo.sampling"

    enum Mode {
        OFF, INSTRUMENT, OPTIMIZE
    }

    private PyronautPgo() {
    }

    static String graalvmCollectionsPreserveArg() {
        GRAALVM_COLLECTIONS_PRESERVE_ARG
    }

    static Mode mode(Project project) {
        String value = project.providers.gradleProperty(MODE_PROPERTY).getOrElse("off").trim().toLowerCase(Locale.ROOT)
        switch (value) {
            case "":
            case "off":
            case "false":
                return Mode.OFF
            case "instrument":
                return Mode.INSTRUMENT
            case "optimize":
            case "true":
                return Mode.OPTIMIZE
            default:
                throw new GradleException("Unsupported -P${MODE_PROPERTY}=${value}. Use instrument, optimize or off.")
        }
    }

    // Code compression needs PLT/GOT, which native-image rejects alongside Truffle runtime
    // compilation ("PLT and GOT is currently not supported with runtime compilation"). The images
    // that embed GraalPy compile Python at runtime, so only pyronaut-run can be compressed.
    static final Set<String> RUNTIME_COMPILATION_IMAGES = ["pyronaut-dev", "pyronaut-run-python"] as Set<String>

    static boolean codeCompression(Project project, String imageName) {
        if (mode(project) != Mode.OPTIMIZE) {
            return false
        }
        Boolean requested = project.providers.gradleProperty(CODE_COMPRESSION_PROPERTY).map { it.toBoolean() }.getOrNull()
        // native-image rejects -H:+EnableCodeCompression on macOS ("not supported on darwin platform").
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            if (requested) {
                throw new GradleException("-P${CODE_COMPRESSION_PROPERTY}=true is not supported on macOS: native-image does not support code compression on darwin.")
            }
            return false
        }
        if (imageName in RUNTIME_COMPILATION_IMAGES) {
            if (requested) {
                throw new GradleException("-P${CODE_COMPRESSION_PROPERTY}=true is not supported for $imageName: " +
                    "code compression needs PLT/GOT, which native-image does not support with Truffle runtime compilation.")
            }
            return false
        }
        requested == null || requested
    }

    /**
     * The per-image working directory under the root build directory. Training writes profiles
     * to {@code profiles/}, and each native build writes {@code build-output.json} and
     * {@code pgo-report.txt} beside them.
     */
    static File imageDirectory(Project project, String imageName) {
        project.rootProject.layout.buildDirectory.dir("pgo/${imageName}").get().asFile
    }

    static File profilesDirectory(Project project, String imageName) {
        String override = project.providers.gradleProperty(PROFILES_PROPERTY).getOrNull()
        if (override != null && !override.isBlank()) {
            return project.rootProject.file(override).toPath().resolve(imageName).toFile()
        }
        new File(imageDirectory(project, imageName), "profiles")
    }

    static File buildOutputJson(Project project, String imageName) {
        new File(imageDirectory(project, imageName), "build-output-${mode(project).name().toLowerCase(Locale.ROOT)}.json")
    }

    static List<File> profiles(Project project, String imageName) {
        File directory = profilesDirectory(project, imageName)
        File[] files = directory.listFiles({ File file -> file.isFile() && file.name.endsWith(".iprof") && file.length() > 0 } as FileFilter)
        (files ?: new File[0]).toList().sort { it.name }
    }

    /**
     * The native-image arguments for the current mode, for images built by the GraalVM Gradle plugin.
     */
    static List<String> nativeImageArgs(Project project, String imageName) {
        Mode mode = mode(project)
        if (mode == Mode.OFF) {
            return []
        }
        List<String> args = []
        if (mode == Mode.INSTRUMENT) {
            args << "--pgo-instrument"
        } else {
            args << "--pgo=" + requireProfiles(project, imageName).collect { it.absolutePath }.join(",")
        }
        args << "-H:+UnlockExperimentalVMOptions"
        args << GRAALVM_COLLECTIONS_PRESERVE_ARG
        if (codeCompression(project, imageName)) {
            args << "-H:+EnableCodeCompression"
        }
        args.addAll(samplingArgs(project))
        args << "-H:BuildOutputJSONFile=" + buildOutputJson(project, imageName).absolutePath
        args << "-H:-UnlockExperimentalVMOptions"
        args.addAll(builderHeapArgs(project))
        args
    }

    static boolean sampling(Project project) {
        project.providers.gradleProperty(SAMPLING_PROPERTY).map { it.toBoolean() }.getOrElse(false)
    }

    // Must be added between -H:+UnlockExperimentalVMOptions and -H:-UnlockExperimentalVMOptions.
    private static List<String> samplingArgs(Project project) {
        mode(project) == Mode.INSTRUMENT && !sampling(project) ? ["-H:-SamplingCollect"] : []
    }

    private static List<String> builderHeapArgs(Project project) {
        String heap = project.providers.gradleProperty(BUILDER_MAX_HEAP_PROPERTY).getOrNull()
        heap != null && !heap.isBlank() ? ["-J-Xmx" + heap.trim()] : []
    }

    /**
     * The pyronaut-native-build arguments for the current mode, for the Crema images.
     */
    static List<String> nativeBuildArgs(Project project, String imageName) {
        Mode mode = mode(project)
        if (mode == Mode.OFF) {
            return []
        }
        List<String> args = []
        if (mode == Mode.INSTRUMENT) {
            args << "--pgo-instrument"
        } else {
            args << "--pgo=" + requireProfiles(project, imageName).collect { it.absolutePath }.join(",")
        }
        if (codeCompression(project, imageName)) {
            args << "--code-compression"
        }
        args << "-H:+UnlockExperimentalVMOptions"
        args << GRAALVM_COLLECTIONS_PRESERVE_ARG
        args.addAll(samplingArgs(project))
        args << "-H:BuildOutputJSONFile=" + buildOutputJson(project, imageName).absolutePath
        args << "-H:-UnlockExperimentalVMOptions"
        args.addAll(builderHeapArgs(project))
        args
    }

    /**
     * Fails before an expensive build starts when the toolchain cannot produce a PGO image, and
     * removes build output from an earlier run so that it cannot be mistaken for this one.
     */
    static void prepareBuild(Project project, String imageName, File javaHome) {
        if (mode(project) == Mode.OFF) {
            return
        }
        if (System.getenv("GRAALVM_QUICK_BUILD") != null) {
            throw new GradleException("-P${MODE_PROPERTY} cannot be combined with GRAALVM_QUICK_BUILD, which builds with -Ob. Unset it and run ./gradlew --stop.")
        }
        File release = new File(javaHome, "release")
        String releaseText = release.isFile() ? release.text : ""
        // Community Edition has neither module: it cannot build with --pgo or code compression.
        if (!releaseText.contains("graal_enterprise") && !releaseText.contains("substratevm-enterprise")) {
            throw new GradleException("-P${MODE_PROPERTY} needs Oracle GraalVM, which provides --pgo and code compression. $javaHome is not Oracle GraalVM.")
        }
        File json = buildOutputJson(project, imageName)
        json.parentFile.mkdirs()
        json.delete()
    }

    /**
     * Checks the build output JSON and writes {@code pgo-report.txt}. An optimized image that did
     * not apply its profiles fails the build.
     */
    static void verifyAndReport(Project project, String imageName, File executable) {
        Mode mode = mode(project)
        if (mode == Mode.OFF) {
            return
        }
        File json = buildOutputJson(project, imageName)
        if (!json.isFile()) {
            throw new GradleException("native-image did not write its build output JSON to $json")
        }
        Map<String, Object> output = new JsonSlurper().parse(json) as Map<String, Object>
        Object pgo = ((output.general_info as Map)?.graal_compiler as Map)?.pgo
        String pgoValue = pgo instanceof Collection ? (pgo as Collection).join(",") : String.valueOf(pgo)
        boolean applied = pgo != null && pgo != false && !pgoValue.isBlank() && pgoValue != "off" && pgoValue != "null"
        if (mode == Mode.OPTIMIZE && (!applied || pgoValue.contains("instrument"))) {
            throw new GradleException("$imageName was built with -P${MODE_PROPERTY}=optimize but native-image reports PGO: $pgoValue ($json)")
        }
        if (mode == Mode.INSTRUMENT && !pgoValue.contains("instrument")) {
            throw new GradleException("$imageName was built with -P${MODE_PROPERTY}=instrument but native-image reports PGO: $pgoValue ($json)")
        }
        List<String> lines = [
            "image: $imageName",
            "mode: ${mode.name().toLowerCase(Locale.ROOT)}",
            "pgo: ${mode == Mode.OPTIMIZE ? 'applied' : 'instrumented'} ($pgoValue)",
            "code-compression: ${codeCompression(project, imageName) ? 'enabled' : 'disabled'}",
            "executable: ${executable.absolutePath}",
            "executable-bytes: ${executable.length()}",
        ]
        if (mode == Mode.OPTIMIZE) {
            lines << "profiles:"
            profiles(project, imageName).each { lines << "  ${it.name} ${it.length()}".toString() }
        }
        File report = new File(imageDirectory(project, imageName), "pgo-report${mode == Mode.INSTRUMENT ? '-instrument' : ''}.txt")
        report.parentFile.mkdirs()
        report.text = lines.join("\n") + "\n"
        project.logger.lifecycle("PGO {} for {}: {}", mode.name().toLowerCase(Locale.ROOT), imageName, report)
        if (mode == Mode.INSTRUMENT) {
            // Record which executable training must run, so that it never trains a stale image.
            Files.writeString(new File(imageDirectory(project, imageName), "instrumented-executable.txt").toPath(),
                executable.absolutePath + "\n" + executable.length() + "\n" + executable.lastModified() + "\n")
        }
    }

    /**
     * Stops a bundle from shipping an instrumented image.
     */
    static void rejectInstrumentedBundle(Project project, String imageName) {
        if (mode(project) == Mode.INSTRUMENT) {
            throw new GradleException("$imageName is instrumented for PGO training and must not be bundled. Build the bundle with -P${MODE_PROPERTY}=optimize.")
        }
    }

    private static List<File> requireProfiles(Project project, String imageName) {
        List<File> profiles = profiles(project, imageName)
        if (profiles.isEmpty()) {
            throw new GradleException("No PGO profiles for $imageName in ${profilesDirectory(project, imageName)}. " +
                "Build with -P${MODE_PROPERTY}=instrument and run :micronaut-pgo-training:train first.")
        }
        profiles
    }
}
