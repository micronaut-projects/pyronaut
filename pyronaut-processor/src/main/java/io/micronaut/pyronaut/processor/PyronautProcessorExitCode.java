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
package io.micronaut.pyronaut.processor;

/**
 * Exit codes for {@code pyronaut-processor}.
 */
public enum PyronautProcessorExitCode {
    SUCCESS(0),
    USAGE_ERROR(2),
    CONFIG_ERROR(3),
    PROCESSING_ERROR(5),
    PRECONDITION_FAILED(8),
    INTERNAL_ERROR(10);

    private final int code;

    PyronautProcessorExitCode(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }
}
