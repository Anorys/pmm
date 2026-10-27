package com.anorys.pmm.config;

import com.anorys.pmm.config.ConfigTemplate.DiscoveredRepository;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTemplateTest {

    @TempDir
    Path tmp;

    @Test
    void discoversGitRepositoriesWithPom() throws IOException {
        mavenRepo("platform-core");
        mavenRepo("Billing Service");
        Files.createDirectories(tmp.resolve("docs/.git"));        // git but no pom
        Files.createDirectories(tmp.resolve("scratch"));
        Files.writeString(tmp.resolve("scratch/pom.xml"), "");     // pom but no git

        List<DiscoveredRepository> found = ConfigTemplate.discover(tmp);

        assertThat(found).containsExactly(
                new DiscoveredRepository("Billing-Service", "Billing Service"),
                new DiscoveredRepository("platform-core", "platform-core"));
    }

    @Test
    void renderedTemplateIsAValidConfig() throws IOException {
        mavenRepo("platform-core");
        mavenRepo("Billing Service");

        String yaml = ConfigTemplate.render(ConfigTemplate.discover(tmp), "main");
        PmmConfig config = ConfigLoader.parse(yaml, tmp.resolve("pmm.yaml"));

        assertThat(config.workspace().baseBranch()).isEqualTo("main");
        assertThat(config.repositories()).extracting(RepositoryConfig::path)
                .containsExactly(tmp.resolve("Billing Service").toAbsolutePath(), tmp.resolve("platform-core").toAbsolutePath());
        assertThat(WorkspaceWarnings.of(config)).isEmpty();
    }

    @Test
    void emptyWorkspaceStillRendersAValidConfig() {
        String yaml = ConfigTemplate.render(List.of(), "develop");

        PmmConfig config = ConfigLoader.parse(yaml, tmp.resolve("pmm.yaml"));

        assertThat(config.repositories()).extracting(RepositoryConfig::name).containsExactly("my-repository");
    }

    @Test
    void sanitizesNames() {
        assertThat(ConfigTemplate.toName("my repo (old)")).isEqualTo("my-repo-old");
        assertThat(ConfigTemplate.toName("###")).isEqualTo("repository");
    }

    private void mavenRepo(String name) throws IOException {
        Path repo = Files.createDirectories(tmp.resolve(name).resolve(".git")).getParent();
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
    }
}
