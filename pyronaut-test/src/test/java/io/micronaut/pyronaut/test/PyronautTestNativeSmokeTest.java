package io.micronaut.pyronaut.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@EnabledIfSystemProperty(named = "pyronaut.test.native.binary", matches = ".+")
class PyronautTestNativeSmokeTest extends AbstractPyronautTestSmokeTest {

    @Test
    void nativeLauncherExecutesGeneratedClassFromProjectClasspath() throws Exception {
        assertSelectedClassRunsUsingProjectClasspath();
    }

    @Test
    void nativeLauncherLoadsLogbackPythonModuleFromProjectClasspath() throws Exception {
        assertPythonLogbackModuleLoadsUsingProjectClasspath();
    }

    @Test
    void nativeLauncherLooksUpProcessedPythonBeanFromTestClasses() throws Exception {
        assertProcessedPythonBeanLookupWorks();
    }

    @Override
    protected RunResult runPyronautTest(Path project) throws Exception {
        return runNativeTest(project, "smoke.GeneratedPassingTest");
    }

    @Override
    protected RunResult runDefaultPytest(Path project) throws Exception {
        return runNativeTest(project, null);
    }

    private RunResult runNativeTest(Path project, String className) throws Exception {
        Path binary = Path.of(System.getProperty("pyronaut.test.native.binary"));
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(binary.toString());
        command.add("-Djava.class.path=" + String.join(java.io.File.pathSeparator, nativeTestClasspathEntries(project)));
        command.add("--project-dir");
        command.add(project.toString());
        if (className != null) {
            command.add("--select-class");
            command.add(className);
        }
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }
}
