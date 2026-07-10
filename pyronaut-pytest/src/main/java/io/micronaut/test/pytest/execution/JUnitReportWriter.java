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
package io.micronaut.test.pytest.execution;

import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the common Pyronaut JUnit report artifacts. */
public final class JUnitReportWriter {
    private JUnitReportWriter() {
    }

    /**
     * Writes the XML and HTML summary reports.
     *
     * @param directory The report directory
     * @param summary The JUnit execution summary
     * @throws IOException If a report cannot be written
     */
    public static void write(Path directory, TestExecutionSummary summary) throws IOException {
        Files.createDirectories(directory);
        long tests = summary.getTestsFoundCount();
        long skipped = summary.getTestsSkippedCount();
        long failed = summary.getTestsFailedCount();
        long successful = summary.getTestsSucceededCount();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"Pyronaut direct tests\" tests=\"" + tests + "\" skipped=\"" + skipped
            + "\" failures=\"" + failed + "\" errors=\"0\"><properties/></testsuite>\n";
        Files.writeString(directory.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"><title>Pyronaut Test Report</title></head><body>"
            + "<h1>Pyronaut Test Report</h1><ul><li>Tests: " + tests + "</li><li>Successful: "
            + successful + "</li><li>Skipped: " + skipped + "</li><li>Failed: " + failed
            + "</li></ul></body></html>\n";
        Files.writeString(directory.resolve("index.html"), html, StandardCharsets.UTF_8);
    }
}
