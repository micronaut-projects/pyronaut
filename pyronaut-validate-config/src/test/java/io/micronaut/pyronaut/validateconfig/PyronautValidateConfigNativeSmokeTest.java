package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@EnabledIfSystemProperty(named = "pyronaut.validateconfig.native.binary", matches = ".+")
class PyronautValidateConfigNativeSmokeTest extends AbstractPyronautValidateConfigSmokeTest {

    @Test
    void nativeBinaryValidatesConfigurationAndDependencyInjection() throws Exception {
        assertValidationFindsConfigurationAndDependencyInjectionErrors();
    }

    @Override
    protected RunResult runValidation(Path project) throws Exception {
        String binaryPath = System.getProperty("pyronaut.validateconfig.native.binary");
        Path binary = Path.of(binaryPath);
        ProcessBuilder builder = new ProcessBuilder(
            binary.toString(),
            "--project-dir", project.toString(),
            "--scenario", "run",
            "--validate-dependency-injection",
            "--dependency-injection-validation-strategy", "all-beans",
            "--no-cache"
        )
            .redirectErrorStream(true);
        builder.environment().put("PYRONAUT_VALIDATE_CONFIG_TRACE", "true");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }
}
