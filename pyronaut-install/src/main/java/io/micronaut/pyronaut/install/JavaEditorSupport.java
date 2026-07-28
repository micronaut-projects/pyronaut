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

import java.nio.file.Path;
import java.util.List;

/**
 * Generates Eclipse/VS Code and IntelliJ metadata for direct Java sources.
 */
final class JavaEditorSupport {
    private final EclipseClasspathWriter eclipseWriter = new EclipseClasspathWriter();
    private final IntellijDirectSourceWriter intellijWriter = new IntellijDirectSourceWriter();
    private final IntellijRunConfigurationWriter intellijRunWriter = new IntellijRunConfigurationWriter();
    private final VsCodeJavaSettingsWriter vsCodeWriter = new VsCodeJavaSettingsWriter();
    private final VsCodeLaunchWriter vsCodeLaunchWriter = new VsCodeLaunchWriter();

    void ensureWritten(Path projectDir,
                       List<Path> sourceFiles,
                       List<Path> sourceRoots,
                       List<Library> libraries) throws Exception {
        eclipseWriter.ensureWritten(projectDir, sourceRoots, libraries);
        intellijWriter.ensureWritten(projectDir, sourceRoots, libraries);
        intellijRunWriter.ensureWritten(projectDir, sourceFiles);
        vsCodeWriter.ensureWritten(projectDir, sourceRoots, libraries);
        vsCodeLaunchWriter.ensureWritten(projectDir, sourceFiles);
    }

    record Library(Path binary, Path sources) {
    }
}
