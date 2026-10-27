package com.anorys.pmm.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogTailTest {

    @Test
    void findsTheModuleTheReactorIsBuilding() {
        LogTail.Progress progress = LogTail.parse("""
                [INFO] Building core-api 3.1.5-SNAPSHOT                             [1/3]
                [INFO] --- compiler:3.13.0:compile (default-compile) @ core-api ---
                [INFO] Building core-service 3.1.5-SNAPSHOT                         [2/3]
                [INFO] --- resources:3.3.1:resources (default-resources) @ core-service ---
                """);
        assertThat(progress.module()).isEqualTo("core-service");
        assertThat(progress.index()).isEqualTo(2);
        assertThat(progress.count()).isEqualTo(3);
        assertThat(progress.lines()).hasSize(4).last().asString().contains("resources");
    }

    @Test
    void singleModuleBuildsHaveNoPosition() {
        LogTail.Progress progress = LogTail.parse("[INFO] Building billing 1.0.0\r\n[INFO] BUILD SUCCESS\r\n");
        assertThat(progress.module()).isEqualTo("billing");
        assertThat(progress.count()).isZero();
        assertThat(progress.lines()).containsExactly("[INFO] Building billing 1.0.0", "[INFO] BUILD SUCCESS");
    }

    @Test
    void readsOnlyTheEndOfALongLog(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("big.log");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            text.append("[INFO] line ").append(i).append('\n');
        }
        Files.writeString(log, text);
        LogTail.Progress progress = LogTail.read(log);
        assertThat(progress.lines()).hasSize(LogTail.MAX_LINES).last().isEqualTo("[INFO] line 4999");
        assertThat(progress.lines().get(0)).startsWith("[INFO] line ");
    }

    @Test
    void aMissingLogIsNotReadYet(@TempDir Path dir) {
        assertThat(LogTail.read(dir.resolve("none.log"))).isNull();
    }
}
