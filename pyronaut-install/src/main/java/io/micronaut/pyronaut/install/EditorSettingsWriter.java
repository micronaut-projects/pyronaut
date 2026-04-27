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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes IDE-specific settings for generated Python stubs.
 */
interface EditorSettingsWriter {
    SettingsResult ensureConfigured(Path projectDir, String stubPath) throws IOException;

    enum SettingsStatus {
        UPDATED,
        UNCHANGED,
        SKIPPED_USER_CONFIG
    }

    record SettingsResult(SettingsStatus status, List<PythonIdeStubGenerator.WarningDetail> warnings) {
    }
}
