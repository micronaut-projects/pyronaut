package io.micronaut.build;

import org.gradle.api.GradleException;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.jvm.toolchain.JavaToolchainDownload;
import org.gradle.jvm.toolchain.JavaToolchainRequest;
import org.gradle.jvm.toolchain.JavaToolchainResolver;

import javax.inject.Inject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class GraalVmDevBuildToolchainResolver implements JavaToolchainResolver {
    private static final String DEV_BUILDS_API = "https://api.github.com/repos/graalvm/graalvm-ce-dev-builds/releases/tags/";
    private static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");

    @Inject
    protected abstract ProviderFactory getProviders();

    @Override
    public Optional<JavaToolchainDownload> resolve(JavaToolchainRequest request) {
        String configuredTag = getProviders().gradleProperty("pyronautGraalVmDevTag").getOrNull();
        if (configuredTag == null || configuredTag.isBlank()) {
            return Optional.empty();
        }
        String tag = normalizeDevBuildTag(configuredTag);

        Integer requestedLanguageVersion = requestedLanguageVersion(request);
        if (requestedLanguageVersion != null && !tag.contains(String.valueOf(requestedLanguageVersion))) {
            return Optional.empty();
        }

        String downloadUrl = findDownloadUrl(tag, assetSuffixForCurrentMachine());
        return Optional.of(JavaToolchainDownload.fromUri(URI.create(downloadUrl)));
    }

    private static Integer requestedLanguageVersion(JavaToolchainRequest request) {
        if (!request.getJavaToolchainSpec().getLanguageVersion().isPresent()) {
            return null;
        }
        return Integer.valueOf(request.getJavaToolchainSpec().getLanguageVersion().get().toString());
    }

    private static String assetSuffixForCurrentMachine() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String os;
        if (osName.contains("mac")) {
            os = "darwin";
        } else if (osName.contains("linux")) {
            os = "linux";
        } else if (osName.contains("win")) {
            os = "windows";
        } else {
            throw new GradleException("Unsupported operating system for GraalVM CE dev toolchain provisioning: " + osName);
        }

        String arch;
        if ("aarch64".equals(archName) || "arm64".equals(archName)) {
            arch = "aarch64";
        } else if ("x86_64".equals(archName) || "amd64".equals(archName)) {
            arch = "amd64";
        } else {
            throw new GradleException("Unsupported architecture for GraalVM CE dev toolchain provisioning: " + archName);
        }

        String extension = "windows".equals(os) ? "zip" : "tar.gz";
        return os + "-" + arch + "." + extension;
    }

    private static String normalizeDevBuildTag(String configuredTag) {
        if (configuredTag.startsWith("jdk-")) {
            return configuredTag.substring(4);
        }
        return configuredTag;
    }

    private static String findDownloadUrl(String tag, String assetSuffix) {
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(DEV_BUILDS_API + encodedTag).toURL().openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "pyronaut-gradle-toolchain-resolver");

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, responseCode);
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new GradleException(
                    "Unable to resolve GraalVM CE dev build tag '" + tag + "' from GitHub releases (HTTP " + responseCode + "). " + responseBody.trim()
                );
            }

            Matcher matcher = DOWNLOAD_URL_PATTERN.matcher(responseBody);
            while (matcher.find()) {
                String candidate = matcher.group(1).replace("\\/", "/");
                if (candidate.contains("/download/" + tag + "/")
                    && candidate.contains("/graalvm-community-dev-")
                    && candidate.endsWith(assetSuffix)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            throw new GradleException("Unable to query GitHub releases for GraalVM CE dev build tag '" + tag + "'", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        throw new GradleException("No GraalVM CE dev build asset ending with '" + assetSuffix + "' was found for tag '" + tag + "'");
    }

    private static String readResponseBody(HttpURLConnection connection, int responseCode) throws IOException {
        var stream = responseCode == HttpURLConnection.HTTP_OK ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (stream; var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder response = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                response.append(buffer, 0, read);
            }
            return response.toString();
        }
    }
}
