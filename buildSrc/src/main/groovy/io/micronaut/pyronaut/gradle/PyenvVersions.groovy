package io.micronaut.pyronaut.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Resolves the pyenv-selected Python version the same way pyenv itself does:
 * the {@code PYENV_VERSION} environment variable, then the nearest
 * {@code .python-version} file in the project directory or its parents, then
 * the global {@code $(PYENV_ROOT or ~/.pyenv)/version} file.
 */
final class PyenvVersions {
    static final String PYENV_VERSION = "PYENV_VERSION"

    private PyenvVersions() {
    }

    /**
     * @return a provider of the selected pyenv version, absent when none is selected
     */
    static Provider<String> provider(Project project) {
        File startDir = project.rootProject.projectDir
        project.providers.provider {
            resolve(System.getenv(), startDir)
        }
    }

    /**
     * @return the selected pyenv version, or {@code null} when none is selected
     */
    static String resolve(Map<String, String> env, File startDir) {
        String fromEnv = env.get(PYENV_VERSION)?.trim()
        if (fromEnv) {
            return fromEnv
        }
        for (File dir = startDir?.absoluteFile; dir != null; dir = dir.parentFile) {
            String local = readVersionFile(new File(dir, ".python-version"))
            if (local) {
                return local
            }
        }
        readVersionFile(new File(root(env), "version"))
    }

    /**
     * @return the pyenv root directory: {@code PYENV_ROOT} or {@code ~/.pyenv}
     */
    static File root(Map<String, String> env = System.getenv()) {
        String configured = env.get("PYENV_ROOT")?.trim()
        configured ? new File(configured) : new File(System.getProperty("user.home"), ".pyenv")
    }

    private static String readVersionFile(File file) {
        if (!file.isFile()) {
            return null
        }
        // pyenv version files list whitespace-separated versions and may contain '#' comments;
        // the first entry is the primary version.
        file.readLines("UTF-8")
            .collect { it.trim() }
            .findAll { it && !it.startsWith("#") }
            .collectMany { it.split(/\s+/).toList() }
            .find()
    }
}
