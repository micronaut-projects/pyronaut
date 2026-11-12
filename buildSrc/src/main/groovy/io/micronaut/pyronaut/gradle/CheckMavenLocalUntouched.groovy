package io.micronaut.pyronaut.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Reports the files that changed in the developer's Maven repository since the build started.
 *
 * <p>This compares the status change time: Maven Resolver keeps the server's modification time on the files it
 * downloads, so the modification time misses a replaced jar. Other programs on a developer machine write to the
 * repository too, so only a build told to fail on it does; CI is such a build.
 */
abstract class CheckMavenLocalUntouched extends DefaultTask {
    private static final int LISTED_FILES = 50

    @Internal
    abstract SetProperty<File> getMavenRepositories()

    @Internal
    abstract Property<Long> getBuildStartMillis()

    @Internal
    abstract Property<Boolean> getFailOnChange()

    @TaskAction
    void check() {
        List<Path> changed = mavenRepositories.get()
            .collectMany { changedFiles(it.toPath(), FileTime.fromMillis(buildStartMillis.get())) }
        if (changed.isEmpty()) {
            return
        }
        String message = "Tests wrote ${changed.size()} files to the Maven repository outside their test home " +
            "(${PyronautTestHome.PROPERTY}):\n" + changed.take(LISTED_FILES).collect { "  $it" }.join("\n") +
            (changed.size() > LISTED_FILES ? "\n  ..." : "")
        if (failOnChange.get()) {
            throw new GradleException(message)
        }
        logger.warn(message)
    }

    static List<Path> changedFiles(Path repository, FileTime since) {
        List<Path> changed = []
        if (!Files.isDirectory(repository)) {
            return changed
        }
        Files.walkFileTree(repository, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (changeTime(file, attributes) >= since) {
                    changed.add(file)
                }
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFileFailed(Path file, IOException e) {
                // A file another program is replacing
                FileVisitResult.CONTINUE
            }
        })
        changed.sort()
    }

    static FileTime changeTime(Path file, BasicFileAttributes attributes) {
        try {
            (FileTime) Files.getAttribute(file, "unix:ctime", LinkOption.NOFOLLOW_LINKS)
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
            // No such attribute on Windows
            attributes.lastModifiedTime()
        }
    }
}
