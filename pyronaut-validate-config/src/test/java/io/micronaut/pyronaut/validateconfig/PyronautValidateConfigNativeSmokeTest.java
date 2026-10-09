package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@EnabledIfSystemProperty(named = "pyronaut.validateconfig.native.binary", matches = ".+")
class PyronautValidateConfigNativeSmokeTest extends AbstractPyronautValidateConfigSmokeTest {

    @Test
    void nativeBinaryValidatesConfigurationErrors() throws Exception {
        assertValidationFindsConfigurationErrors();
    }

    @Test
    void nativeBinaryReportsInvalidValuesInConfigApplicationToml() throws Exception {
        assertValidationReportsInvalidApplicationToml();
    }

    @Test
    void nativeBinaryAcceptsReadableByteSizes() throws Exception {
        assertValidationAcceptsReadableByteSizes();
    }

    @Test
    void nativeBinaryRejectsInvalidByteSizes() throws Exception {
        assertValidationRejectsInvalidByteSizes();
    }

    @Override
    protected RunResult runConfigurationValidation(Path project, String scenario) throws Exception {
        return runNative(project, "--scenario", scenario, "--no-cache");
    }

    @Override
    protected RunResult runValidation(Path project) throws Exception {
        return runNative(project, "--scenario", "run", "--validate-dependency-injection", "--no-cache");
    }

    private static RunResult runNative(Path project, String... args) throws Exception {
        String binaryPath = System.getProperty("pyronaut.validateconfig.native.binary");
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(binaryPath).toString());
        command.add("--project-dir");
        command.add(project.toString());
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectErrorStream(true);
        builder.environment().put("PYRONAUT_VALIDATE_CONFIG_TRACE", "true");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }
}
