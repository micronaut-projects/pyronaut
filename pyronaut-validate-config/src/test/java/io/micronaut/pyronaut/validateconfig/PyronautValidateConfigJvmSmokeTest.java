package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;

class PyronautValidateConfigJvmSmokeTest extends AbstractPyronautValidateConfigSmokeTest {

    @Test
    void jvmValidationFindsConfigurationAndDependencyInjectionErrors() throws Exception {
        assertValidationFindsConfigurationAndDependencyInjectionErrors();
    }

    @Test
    void jvmValidationReportsInvalidValuesInConfigApplicationToml() throws Exception {
        assertValidationReportsInvalidApplicationToml();
    }

    @Test
    void jvmValidationAcceptsReadableByteSizes() throws Exception {
        assertValidationAcceptsReadableByteSizes();
    }

    @Test
    void jvmValidationRejectsInvalidByteSizes() throws Exception {
        assertValidationRejectsInvalidByteSizes();
    }

    @Override
    protected RunResult runValidation(java.nio.file.Path project) throws Exception {
        return runJvmValidation(project);
    }
}
