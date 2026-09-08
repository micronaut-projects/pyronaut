package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyConfigurationLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void environmentProxyTakesPrecedence() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(pyronautSettings, "[proxy]\nhost=\"pyronaut-proxy\"\nport=8080\n", StandardCharsets.UTF_8);
        Files.writeString(m2Settings, m2SettingsXml("m2-proxy", "9090"), StandardCharsets.UTF_8);

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://env-user:env-pass@env-proxy:3128", "NO_PROXY", "localhost,127.0.0.1"),
            pyronautSettings,
            m2Settings
        );

        ProxyConfigurationLoader.ProxyConfiguration proxy = loader.load().orElseThrow();
        assertEquals("environment", proxy.source());
        assertEquals("env-proxy", proxy.host());
        assertEquals(3128, proxy.port());
        assertEquals("env-user", proxy.username());
        assertEquals("env-pass", proxy.password());
        assertEquals("localhost|127.0.0.1", proxy.nonProxyHosts());
    }

    @Test
    void pyronautSettingsUsedWhenEnvironmentMissing() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(
            pyronautSettings,
            "[proxy]\nurl=\"https://pyronaut-user:pyronaut-pass@pyronaut-proxy:8443\"\nnonProxyHosts=\"localhost,*.internal\"\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(m2Settings, m2SettingsXml("m2-proxy", "9090"), StandardCharsets.UTF_8);

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(Map.of(), pyronautSettings, m2Settings);

        ProxyConfigurationLoader.ProxyConfiguration proxy = loader.load().orElseThrow();
        assertEquals("~/.pyronaut/settings.toml", proxy.source());
        assertEquals("https", proxy.protocol());
        assertEquals("pyronaut-proxy", proxy.host());
        assertEquals(8443, proxy.port());
        assertEquals("pyronaut-user", proxy.username());
        assertEquals("pyronaut-pass", proxy.password());
        assertEquals("localhost|*.internal", proxy.nonProxyHosts());
    }

    @Test
    void fallsBackToMavenSettings() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(
            m2Settings,
            """
                <settings>
                  <proxies>
                    <proxy>
                      <active>false</active>
                      <protocol>http</protocol>
                      <host>inactive-proxy</host>
                      <port>7000</port>
                    </proxy>
                    <proxy>
                      <active>true</active>
                      <protocol>https</protocol>
                      <host>active-proxy</host>
                      <port>7443</port>
                      <username>m2-user</username>
                      <password>m2-pass</password>
                      <nonProxyHosts>localhost|127.*</nonProxyHosts>
                    </proxy>
                  </proxies>
                </settings>
                """,
            StandardCharsets.UTF_8
        );

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(Map.of(), pyronautSettings, m2Settings);

        ProxyConfigurationLoader.ProxyConfiguration proxy = loader.load().orElseThrow();
        assertEquals("~/.m2/settings.xml", proxy.source());
        assertEquals("https", proxy.protocol());
        assertEquals("active-proxy", proxy.host());
        assertEquals(7443, proxy.port());
        assertEquals("m2-user", proxy.username());
        assertEquals("m2-pass", proxy.password());
        assertEquals("localhost|127.*", proxy.nonProxyHosts());
    }

    @Test
    void invalidPyronautSettingsFailsWithActionableMessage() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(pyronautSettings, "[proxy\n", StandardCharsets.UTF_8);

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(Map.of(), pyronautSettings, m2Settings);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, loader::load);
        assertTrue(exception.getMessage().contains("~/.pyronaut/settings.toml"));
    }

    @Test
    void ignoresMavenProxiesExplicitlyMarkedInactive() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(
            m2Settings,
            """
                <settings>
                  <proxies>
                    <proxy>
                      <active>false</active>
                      <protocol>http</protocol>
                      <host>inactive-proxy</host>
                      <port>7000</port>
                    </proxy>
                  </proxies>
                </settings>
                """,
            StandardCharsets.UTF_8
        );

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(Map.of(), pyronautSettings, m2Settings);

        assertTrue(loader.load().isEmpty());
    }

    @Test
    void mavenProxyWithoutActiveElementIsUsed() throws Exception {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");
        Files.writeString(
            m2Settings,
            """
                <settings>
                  <proxies>
                    <proxy>
                      <active>false</active>
                      <host>inactive-proxy</host>
                      <port>7000</port>
                    </proxy>
                    <proxy>
                      <host>implicit-proxy</host>
                      <port>7001</port>
                    </proxy>
                  </proxies>
                </settings>
                """,
            StandardCharsets.UTF_8
        );

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(Map.of(), pyronautSettings, m2Settings);

        ProxyConfigurationLoader.ProxyConfiguration proxy = loader.load().orElseThrow();
        assertEquals("implicit-proxy", proxy.host());
        assertEquals(7001, proxy.port());
    }

    @Test
    void normalizesNoProxySuffixAndPortEntries() {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://env-proxy:3128", "NO_PROXY", ".internal, localhost:8080 ,127.0.0.1,*.corp.example"),
            pyronautSettings,
            m2Settings
        );

        ProxyConfigurationLoader.ProxyConfiguration proxy = loader.load().orElseThrow();
        assertEquals("*.internal|localhost|127.0.0.1|*.corp.example", proxy.nonProxyHosts());
    }

    @Test
    void malformedEnvironmentProxyUrlFailsWithSourceAndValue() {
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Path m2Settings = tempDir.resolve("settings.xml");

        ProxyConfigurationLoader loader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://bad proxy:3128"),
            pyronautSettings,
            m2Settings
        );

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, loader::load);
        assertTrue(exception.getMessage().contains("environment"), exception.getMessage());
        assertTrue(exception.getMessage().contains("http://bad proxy:3128"), exception.getMessage());
    }

    private static String m2SettingsXml(String host, String port) {
        return """
            <settings>
              <proxies>
                <proxy>
                  <active>true</active>
                  <protocol>http</protocol>
                  <host>%s</host>
                  <port>%s</port>
                </proxy>
              </proxies>
            </settings>
            """.formatted(host, port);
    }
}
