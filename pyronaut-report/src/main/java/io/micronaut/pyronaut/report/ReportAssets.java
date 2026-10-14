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
package io.micronaut.pyronaut.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The assets every Pyronaut HTML report inlines so that it works offline: the stylesheet, the
 * script driving its filters and search, and the Pyronaut artwork of its hero. The test report and
 * the static compilation report share them, so the reports look the same.
 */
public final class ReportAssets {

    /**
     * The favicon of every report, an inline SVG.
     */
    public static final String FAVICON = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E"
        + "%3Ctext y='.9em' font-size='90'%3E%F0%9F%94%A5%3C/text%3E%3C/svg%3E";

    private static final String RESOURCES = "META-INF/pyronaut/report/";

    private ReportAssets() {
    }

    /**
     * @return The stylesheet, empty when the resource is missing
     */
    public static String stylesheet() {
        return text("report.css");
    }

    /**
     * @return The script, empty when the resource is missing
     */
    public static String script() {
        return text("report.js");
    }

    /**
     * @return The Pyronaut wordmark as a data URI, or {@code null} when the resource is missing
     */
    public static String wordmark() {
        return dataUri("wordmark.png");
    }

    /**
     * @return The Pyronaut mascot as a data URI, or {@code null} when the resource is missing
     */
    public static String mascot() {
        return dataUri("mascot.png");
    }

    /**
     * @param text Text to place in an HTML document
     * @return The text with its markup characters escaped
     */
    public static String escapeHtml(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String text(String name) {
        byte[] bytes = bytes(name);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String dataUri(String name) {
        byte[] bytes = bytes(name);
        return bytes == null ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] bytes(String name) {
        try (InputStream in = ReportAssets.class.getClassLoader().getResourceAsStream(RESOURCES + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
