package com.anorys.pmm.build;

import com.anorys.pmm.build.MavenCommand.Kind;
import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Platform;
import com.anorys.pmm.core.process.ExecutableResolver;
import com.anorys.pmm.core.process.ProcessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenCommandTest {

    @TempDir
    Path ws;

    @Test
    void autoPrefersMvndThenTheWrapperThenMvn() throws IOException {
        PmmConfig config = config("repositories:\n  - name: core\n");
        Path bin = Files.createDirectories(ws.resolve("bin"));
        Path core = Files.createDirectories(ws.resolve("core"));

        assertThatThrownBy(() -> command(config, bin).executable(config.repositories().get(0)))
                .isInstanceOf(ProcessException.class).hasMessageContaining("mvn was not found on the PATH");

        executable(bin.resolve(name("mvn")));
        assertThat(command(config, bin).executable(config.repositories().get(0)).kind()).isEqualTo(Kind.MVN);

        executable(core.resolve(Platform.isWindows() ? "mvnw.cmd" : "mvnw"));
        Files.createDirectories(core.resolve(".mvn/wrapper"));
        Files.writeString(core.resolve(".mvn/wrapper/maven-wrapper.properties"), "");
        assertThat(command(config, bin).executable(config.repositories().get(0)).kind()).isEqualTo(Kind.WRAPPER);

        executable(bin.resolve(name("mvnd")));
        assertThat(command(config, bin).executable(config.repositories().get(0)).kind()).isEqualTo(Kind.MVND);
    }

    @Test
    void argumentsFollowTheConfiguration() throws IOException {
        PmmConfig config = config("""
                repositories:
                  - name: core
                maven:
                  settings: settings.xml
                  offline: true
                  threads: "2"
                  compileGoal: compile
                """);
        MavenCommand command = command(config, ws);

        assertThat(command.arguments(config.repositories().get(0), Kind.MVN, false)).containsExactly(
                "-B", "-ntp", "-o", "-s", ws.resolve("settings.xml").toString(), "-T", "2", "compile");
        assertThat(command.arguments(config.repositories().get(0), Kind.MVND, false)).doesNotContain("-T");
    }

    private MavenCommand command(PmmConfig config, Path bin) {
        return new MavenCommand(config, new ExecutableResolver(Platform.isWindows(), List.of(bin),
                List.of(".exe", ".cmd", ".bat")));
    }

    private static String name(String base) {
        return Platform.isWindows() ? base + ".cmd" : base;
    }

    private static void executable(Path file) throws IOException {
        Files.writeString(file, "");
        file.toFile().setExecutable(true);
    }

    private PmmConfig config(String yaml) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), yaml);
        return ConfigLoader.load(ws.resolve("pmm.yaml"));
    }
}
