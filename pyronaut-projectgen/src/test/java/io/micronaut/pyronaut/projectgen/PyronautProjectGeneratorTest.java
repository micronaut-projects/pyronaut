package io.micronaut.pyronaut.projectgen;

import io.micronaut.core.util.StringUtils;
import io.micronaut.projectgen.core.io.PreviewGenerator;
import io.micronaut.projectgen.core.options.GenericOptionsBuilder;
import io.micronaut.projectgen.core.options.Language;
import io.micronaut.projectgen.core.options.Options;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(startApplication = false)
public class PyronautProjectGeneratorTest {
    @Test
    void testGeneratePyronautProject(PreviewGenerator generator) throws Exception {
        Options options = GenericOptionsBuilder.builder()
            .name("pyronaut-demo")
            .version("1.0.0")
            .language(Language.PYTHON)
            .build();
        Map<String, String> project = generator.generate(options);

        Set<String> keys = project.keySet();
        // Verify a file in path launcher/__init.py__ exists and is empty
        assertTrue(keys.contains("launcher/__init.py__"));
        String initPy = project.get("launcher/__init.py__");
        assertTrue(StringUtils.isEmpty(initPy));

        // Verify a file in path src/hello_world_controller.py exists and contains a controller code
        assertTrue(keys.contains("src/hello_world_controller.py"));
        String controllerPy = project.get("src/hello_world_controller.py");
        assertEquals("""
            from micronaut.http.annotation import Controller, Get

            @Controller
            class MyController:
                @Get(value="/", produces="text/plain")
                def index(self) -> str:
                    return "Hello World"
            """, controllerPy);

        // Verify a file in path tests/test_hello_world_controller.py
        assertTrue(keys.contains("tests/test_hello_world_controller.py"));

        assertTrue(keys.contains("setup.py"));

        // Verify a file in path config/micronaut-banner.txt
        assertTrue(keys.contains("config/micronaut-banner.txt"));
        String banner = project.get("config/micronaut-banner.txt");
        assertEquals("""
             (
             )\\ )                                       )
            (()/( (     (                   )    (   ( /(
             /(_)))\\ )  )(    (    (     ( /(   ))\\  )\\())
            (_)) (()/( (()\\   )\\   )\\ )  )(_)) /((_)(_))/
            | _ \\ )(_)) ((_) ((_) _(_/( ((_)_ (_))( | |_
            |  _/| || || '_|/ _ \\| ' \\))/ _` || || ||  _|
            |_|   \\_, ||_|  \\___/|_||_| \\__,_| \\_,_| \\__|
                  |__/
            """, banner);

        assertTrue(keys.contains("pyproject.toml"));
        String config = project.get("pyproject.toml");
        assertEquals("""

[project]
name = 'pyronaut-demo'
version = '1.0.0'
dynamic = ['scripts']

[build-system]
requires = ['setuptools', 'wheel', 'tomli']
build-backend = 'setuptools.build_meta'

[tool.pyronaut]
version = '5.0.0-SNAPSHOT'
repositories = ['mavenCentral', 'https://repo.gradle.org/gradle/libs-releases']

[tool.pyronaut.dependencies]
compile = ['io.micronaut:micronaut-inject-python', 'io.micronaut:micronaut-context-python', 'io.micronaut:micronaut-http-server-netty', 'io.micronaut:micronaut-json-core', 'io.micronaut:micronaut-jackson-databind', 'ch.qos.logback:logback-classic', 'org.bouncycastle:bcprov-jdk18on', 'org.apache.commons:commons-lang3:3.20.0']
annotationProcessor = ['io.micronaut:micronaut-inject-python', 'io.micronaut:micronaut-context-python']
            """, config);
    }
}
