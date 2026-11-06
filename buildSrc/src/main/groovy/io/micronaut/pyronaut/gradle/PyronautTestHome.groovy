package io.micronaut.pyronaut.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/**
 * The home directory tests and the Pyronaut tools they start run against.
 *
 * <p>Pyronaut resolves into {@code $HOME/.m2/repository} and reads
 * {@code $HOME/.pyronaut/settings.toml}, and a test process hands {@code HOME}
 * down to every child process while Gradle system properties such as
 * {@code -Dmaven.repo.local} never reach them. Replacing {@code HOME} is
 * therefore what keeps a test run out of the developer's own Maven repository,
 * whose jars the installed {@code pyronaut} runs.
 *
 * <p>{@code user.home} stays as it is: it comes from the passwd entry, and
 * Testcontainers reads its configuration through it.
 */
final class PyronautTestHome {
    /** Gradle property that selects another test home, for example to test against a prepared repository. */
    static final String PROPERTY = "pyronaut.test.home"

    // These take precedence over HOME in the Pyronaut CLI and tools, which treat a blank value as unset.
    private static final List<String> OVERRIDES_OF_HOME = [
        "PYRONAUT_HOME",
        "PYRONAUT_CONFIG_DIR",
        "PYRONAUT_CACHE_DIR",
        "PYRONAUT_DATA_DIR",
        "PYRONAUT_XDG",
        "PYRONAUT_LOCAL_REPOSITORY",
        "XDG_CONFIG_HOME",
        "XDG_CACHE_HOME",
        "XDG_DATA_HOME"
    ]

    private PyronautTestHome() {
    }

    /**
     * @return the test home: the {@value #PROPERTY} property, or a directory of this checkout that survives {@code clean}
     */
    static File directory(Project project) {
        String configured = project.providers.gradleProperty(PROPERTY).orNull
        configured ? project.rootProject.file(configured) : new File(project.rootDir, ".gradle/test-home")
    }

    /**
     * @return the Maven repository inside the test home
     */
    static File mavenRepository(Project project) {
        new File(directory(project), ".m2/repository")
    }

    /**
     * @return the environment of a process that runs tests. The tools a test uses must still find what is set up
     * in the real home directory, so their locations are passed explicitly.
     */
    static Map<String, String> environment(Project project) {
        File realHome = new File(System.getProperty("user.home"))
        OVERRIDES_OF_HOME.collectEntries { [it, ""] } + [
            HOME         : directory(project).absolutePath,
            PYENV_ROOT   : PyenvVersions.root().absolutePath,
            DOCKER_CONFIG: System.getenv("DOCKER_CONFIG") ?: new File(realHome, ".docker").absolutePath
        ]
    }

    /**
     * Passes the repository as a JVM argument rather than a system property, which would put an absolute path
     * of this checkout into the task's build cache key.
     */
    static final class MavenRepositoryArgument implements CommandLineArgumentProvider {
        private final File repository

        MavenRepositoryArgument(File repository) {
            this.repository = repository
        }

        @Internal
        File getRepository() {
            repository
        }

        @Override
        Iterable<String> asArguments() {
            ["-Dmaven.repo.local=${repository.absolutePath}".toString()]
        }
    }
}
