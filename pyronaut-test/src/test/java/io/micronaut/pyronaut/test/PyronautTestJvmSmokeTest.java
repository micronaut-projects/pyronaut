package io.micronaut.pyronaut.test;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class PyronautTestJvmSmokeTest extends AbstractPyronautTestSmokeTest {

    @Test
    void jvmLauncherExecutesGeneratedClassFromProjectClasspath() throws Exception {
        assertSelectedClassRunsUsingProjectClasspath();
    }

    @Test
    void jvmLauncherLoadsLogbackPythonModuleFromProjectClasspath() throws Exception {
        assertPythonLogbackModuleLoadsUsingProjectClasspath();
    }

    @Test
    void jvmLauncherLooksUpProcessedPythonBeanFromTestClasses() throws Exception {
        assertProcessedPythonBeanLookupWorks();
    }

    @Test
    void jvmLauncherRunsPytestAgainstProcessedRuntimeSources() throws Exception {
        assertPytestUsesProcessedRuntimeSourcesForKeywordAliases();
    }

    @Test
    void jvmLauncherLoadsProjectTypeConverterRegistrarsForPytestMicronautContext() throws Exception {
        assertPytestLoadsProjectTypeConverterRegistrars();
    }

    @Override
    protected RunResult runPyronautTest(Path project) throws Exception {
        return runJvmTest(project);
    }

    @Override
    protected RunResult runDefaultPytest(Path project) throws Exception {
        return runJvmTest(project, null);
    }
}
