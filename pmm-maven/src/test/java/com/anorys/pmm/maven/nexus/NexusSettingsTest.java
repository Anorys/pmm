package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NexusSettingsTest {

    @TempDir
    Path ws;

    @Test
    void notConfigured() throws IOException {
        NexusSettings.Resolution resolution = NexusSettings.resolve(config(""), Map.of());

        assertThat(resolution.endpoint()).isEmpty();
        assertThat(resolution.notes()).singleElement().asString().contains("Nexus is not configured");
    }

    @Test
    void explicitUrlWithCredentialsFromTheEnvironment() throws IOException {
        NexusSettings.Resolution resolution = NexusSettings.resolve(
                config("nexus:\n  url: https://nexus.example.com/repository/releases\n"),
                Map.of("PMM_NEXUS_USERNAME", "jdoe", "PMM_NEXUS_PASSWORD", "pw"));

        NexusEndpoint endpoint = resolution.endpoint().orElseThrow();
        assertThat(endpoint.url()).hasToString("https://nexus.example.com/repository/releases/");
        assertThat(endpoint.username()).isEqualTo("jdoe");
        assertThat(endpoint.password()).isEqualTo("pw");
    }

    @Test
    void urlFromAMirrorAndCredentialsFromTheServer() throws IOException {
        settings("""
                <servers>
                  <server><id>nexus</id><username>jdoe</username><password>${env.NEXUS_PW}</password></server>
                </servers>
                <mirrors>
                  <mirror><id>nexus</id><mirrorOf>*</mirrorOf><url>https://nexus.example.com/repository/public</url></mirror>
                </mirrors>
                """);

        NexusSettings.Resolution resolution = NexusSettings.resolve(config(nexusById()), Map.of("NEXUS_PW", "from-env"));

        NexusEndpoint endpoint = resolution.endpoint().orElseThrow();
        assertThat(endpoint.url()).hasToString("https://nexus.example.com/repository/public/");
        assertThat(endpoint.username()).isEqualTo("jdoe");
        assertThat(endpoint.password()).isEqualTo("from-env");
        assertThat(resolution.notes()).isEmpty();
    }

    @Test
    void urlFromAProfileRepositoryAndEncryptedPasswordIgnored() throws IOException {
        settings("""
                <servers>
                  <server><id>nexus</id><username>jdoe</username><password>{COQLCE6DU6GtcS5P=}</password></server>
                </servers>
                <profiles><profile><id>corp</id><repositories>
                  <repository><id>nexus</id><url>https://nexus.example.com/repository/releases</url></repository>
                </repositories></profile></profiles>
                """);

        NexusSettings.Resolution resolution = NexusSettings.resolve(config(nexusById()), Map.of());

        NexusEndpoint endpoint = resolution.endpoint().orElseThrow();
        assertThat(endpoint.url()).hasToString("https://nexus.example.com/repository/releases/");
        assertThat(endpoint.authenticated()).isFalse();
        assertThat(resolution.notes()).singleElement().asString().contains("is encrypted", "PMM_NEXUS_PASSWORD");
    }

    @Test
    void unknownRepositoryId() throws IOException {
        settings("");

        NexusSettings.Resolution resolution = NexusSettings.resolve(config(nexusById()), Map.of());

        assertThat(resolution.endpoint()).isEmpty();
        assertThat(resolution.notes()).singleElement().asString().contains("no mirror or repository with id 'nexus'");
    }

    private String nexusById() {
        return "maven:\n  settings: settings.xml\nnexus:\n  repositoryId: nexus\n";
    }

    private void settings(String content) throws IOException {
        Files.writeString(ws.resolve("settings.xml"), "<settings>\n" + content + "</settings>\n");
    }

    private PmmConfig config(String extra) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n" + extra);
        return ConfigLoader.load(ws.resolve("pmm.yaml"));
    }
}
