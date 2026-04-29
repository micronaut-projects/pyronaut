package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class PyronautRunJvmSmokeTest extends AbstractPyronautRunSmokeTest {

    @Test
    void jvmLauncherServesHelloWorldApplication() throws Exception {
        assertHelloWorldApplicationServesHttpResponse();
    }

    @Override
    protected RunResult runPyronautRun(Path project) throws Exception {
        return runJvm(project);
    }
}
