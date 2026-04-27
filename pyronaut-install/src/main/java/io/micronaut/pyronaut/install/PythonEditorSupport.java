/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes editor support files for Python code completion.
 */
final class PythonEditorSupport {
    private final PythonIdeStubGenerator stubGenerator;
    private final EditorSettingsWriter vsCodeSettingsWriter;
    private final EditorSettingsWriter pyCharmSettingsWriter;
    private final EditorArtifactManifest editorArtifactManifest;

    PythonEditorSupport() {
        this(
            new PythonIdeStubGenerator(),
            new VsCodeSettingsWriter(),
            new PyCharmSettingsWriter(),
            new EditorArtifactManifest()
        );
    }

    PythonEditorSupport(PythonIdeStubGenerator stubGenerator,
                        EditorSettingsWriter vsCodeSettingsWriter,
                        EditorSettingsWriter pyCharmSettingsWriter,
                        EditorArtifactManifest editorArtifactManifest) {
        this.stubGenerator = stubGenerator;
        this.vsCodeSettingsWriter = vsCodeSettingsWriter;
        this.pyCharmSettingsWriter = pyCharmSettingsWriter;
        this.editorArtifactManifest = editorArtifactManifest;
    }

    EditorSupportResult ensureWritten(Path projectDir,
                                      Path cacheDir,
                                      PyprojectModel.IdeStubs ideStubs,
                                      List<String> runtimeClasspath,
                                      List<String> testClasspath) throws IOException {
        if (!Boolean.TRUE.equals(ideStubs.enabled())) {
            return new EditorSupportResult(PythonIdeStubGenerator.Status.NONE, 0, 0, 0, null, EditorSettingsWriter.SettingsStatus.UNCHANGED);
        }
        List<EditorArtifactManifest.Entry> artifacts = new ArrayList<>();
        artifacts.addAll(editorArtifactManifest.entriesForClasspath(InstallScope.RUNTIME, runtimeClasspath));
        artifacts.addAll(editorArtifactManifest.entriesForClasspath(InstallScope.TEST, testClasspath));
        editorArtifactManifest.write(cacheDir, artifacts);
        PythonIdeStubGenerator.WriteResult stubs = stubGenerator.write(projectDir, ideStubs, artifacts);
        return applySettings(projectDir, cacheDir, ideStubs, stubs);
    }

    EditorSupportResult ensureWrittenFromResolvedArtifacts(Path projectDir,
                                                           Path cacheDir,
                                                           PyprojectModel.IdeStubs ideStubs,
                                                           List<MavenClasspathResolver.ResolvedEditorArtifact> runtimeArtifacts,
                                                           List<MavenClasspathResolver.ResolvedEditorArtifact> testArtifacts) throws IOException {
        if (!Boolean.TRUE.equals(ideStubs.enabled())) {
            return new EditorSupportResult(PythonIdeStubGenerator.Status.NONE, 0, 0, 0, null, EditorSettingsWriter.SettingsStatus.UNCHANGED);
        }
        List<EditorArtifactManifest.Entry> artifacts = new ArrayList<>();
        artifacts.addAll(editorArtifactManifest.entriesForResolvedArtifacts(InstallScope.RUNTIME, runtimeArtifacts));
        artifacts.addAll(editorArtifactManifest.entriesForResolvedArtifacts(InstallScope.TEST, testArtifacts));
        editorArtifactManifest.write(cacheDir, artifacts);
        PythonIdeStubGenerator.WriteResult stubs = stubGenerator.write(projectDir, ideStubs, artifacts);
        return applySettings(projectDir, cacheDir, ideStubs, stubs);
    }

    EditorSupportResult ensureWrittenFromManifests(Path projectDir,
                                                   Path cacheDir,
                                                   PyprojectModel.IdeStubs ideStubs) throws IOException {
        if (!Boolean.TRUE.equals(ideStubs.enabled())) {
            return new EditorSupportResult(PythonIdeStubGenerator.Status.NONE, 0, 0, 0, null, EditorSettingsWriter.SettingsStatus.UNCHANGED);
        }
        List<EditorArtifactManifest.Entry> artifacts = editorArtifactManifest.read(cacheDir);
        if (artifacts.isEmpty()) {
            List<String> runtimeClasspath = readManifestIfPresent(cacheDir.resolve(InstallScope.RUNTIME.manifestFile()));
            List<String> testClasspath = readManifestIfPresent(cacheDir.resolve(InstallScope.TEST.manifestFile()));
            artifacts = new ArrayList<>();
            artifacts.addAll(editorArtifactManifest.entriesForClasspath(InstallScope.RUNTIME, runtimeClasspath));
            artifacts.addAll(editorArtifactManifest.entriesForClasspath(InstallScope.TEST, testClasspath));
        }
        PythonIdeStubGenerator.WriteResult stubs = stubGenerator.write(projectDir, ideStubs, artifacts);
        return applySettings(projectDir, cacheDir, ideStubs, stubs);
    }

    private EditorSupportResult applySettings(Path projectDir,
                                              Path cacheDir,
                                              PyprojectModel.IdeStubs ideStubs,
                                              PythonIdeStubGenerator.WriteResult stubs) throws IOException {
        List<PythonIdeStubGenerator.WarningDetail> warnings = new ArrayList<>(stubs.warnings());
        if (stubs.status() != PythonIdeStubGenerator.Status.NONE) {
            EditorSettingsWriter.SettingsResult settings = settingsWriter(ideStubs.ide()).ensureConfigured(projectDir, ideStubs.destinationDir());
            warnings.addAll(settings.warnings());
            Path warningReport = writeWarningReport(cacheDir, warnings);
            return new EditorSupportResult(
                stubs.status(),
                stubs.packageCount(),
                stubs.symbolCount(),
                warnings.size(),
                warningReport,
                settings.status()
            );
        }
        Path warningReport = writeWarningReport(cacheDir, warnings);
        return new EditorSupportResult(
            stubs.status(),
            stubs.packageCount(),
            stubs.symbolCount(),
            warnings.size(),
            warningReport,
            EditorSettingsWriter.SettingsStatus.UNCHANGED
        );
    }

    private EditorSettingsWriter settingsWriter(String ide) {
        if ("pycharm".equalsIgnoreCase(ide)) {
            return pyCharmSettingsWriter;
        }
        return vsCodeSettingsWriter;
    }

    private static List<String> readManifestIfPresent(Path manifestFile) throws IOException {
        if (!Files.exists(manifestFile)) {
            return List.of();
        }
        return Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
    }

    private static Path writeWarningReport(Path cacheDir,
                                           List<PythonIdeStubGenerator.WarningDetail> warnings) throws IOException {
        Path reportDir = cacheDir.resolve("reports").resolve("editor-stubs");
        Path reportFile = reportDir.resolve("stub-generation-report.txt");
        if (warnings.isEmpty()) {
            Files.deleteIfExists(reportFile);
            return null;
        }
        Files.createDirectories(reportDir);
        StringBuilder builder = new StringBuilder();
        builder.append("Python IDE stub generation warnings\n\n");
        for (PythonIdeStubGenerator.WarningDetail warning : warnings) {
            builder.append("- ").append(warning.summary()).append("\n");
            if (warning.detail() != null && !warning.detail().isBlank()) {
                builder.append(warning.detail().strip()).append("\n");
            }
            builder.append("\n");
        }
        Files.writeString(reportFile, builder.toString(), StandardCharsets.UTF_8);
        return reportFile;
    }

    record EditorSupportResult(PythonIdeStubGenerator.Status status,
                               int packageCount,
                               int symbolCount,
                               int warningCount,
                               Path warningReport,
                               EditorSettingsWriter.SettingsStatus settingsStatus) {
    }
}
