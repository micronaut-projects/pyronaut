package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

@EnabledIfSystemProperty(named = "pyronaut.run.native.binary", matches = ".+")
class PyronautRunNativeSmokeTest extends AbstractPyronautRunSmokeTest {

    @Test
    void nativeLauncherServesHelloWorldApplication() throws Exception {
        assertHelloWorldApplicationServesHttpResponse();
    }

    @Test
    void nativeLauncherInvokesWritableMethodFromPython() throws Exception {
        assertPythonCanInvokeWritableMethod();
    }

    @Override
    protected RunResult runPyronautRun(Path project) throws Exception {
        return runNative(Path.of(System.getProperty("pyronaut.run.native.binary")), project);
    }
}
