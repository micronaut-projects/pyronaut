package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompilerDaemonTest {

    @Test
    void launchesStandaloneJvmProcessorWithOriginalRuntimeArguments() {
        assertEquals(
            List.of(
                "/jdk/bin/java",
                "-Xmx512m",
                "-cp",
                "processor.jar",
                PyronautProcessorMain.class.getName()
            ),
            CompilerDaemon.daemonCommand(
                "/jdk/bin/java",
                new String[]{
                    "-Xmx512m",
                    "-cp",
                    "processor.jar",
                    PyronautProcessorMain.class.getName(),
                    "--project-dir",
                    "/project"
                },
                false,
                null
            )
        );
    }

    @Test
    void launchesNativeDevelopmentToolThroughProcessCommand() {
        assertEquals(
            List.of("/opt/pyronaut-dev", "process"),
            CompilerDaemon.daemonCommand(
                "/opt/pyronaut-dev",
                new String[]{"process", "--project-dir", "/project"},
                true,
                "process"
            )
        );
    }

    @Test
    void launchesJvmDevelopmentToolThroughProcessCommand() {
        assertEquals(
            List.of(
                "/jdk/bin/java",
                "-cp",
                "pyronaut-dev.jar",
                "io.micronaut.pyronaut.dev.PyronautDevMain",
                "process"
            ),
            CompilerDaemon.daemonCommand(
                "/jdk/bin/java",
                new String[]{
                    "-cp",
                    "pyronaut-dev.jar",
                    "io.micronaut.pyronaut.dev.PyronautDevMain",
                    "process",
                    "--project-dir",
                    "/project"
                },
                false,
                "process"
            )
        );
    }
}
