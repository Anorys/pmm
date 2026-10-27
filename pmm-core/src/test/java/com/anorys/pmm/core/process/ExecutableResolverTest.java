package com.anorys.pmm.core.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutableResolverTest {

    private static final List<String> WINDOWS_EXT = List.of(".com", ".exe", ".bat", ".cmd");

    @TempDir
    Path tmp;

    @Test
    void windowsResolvesCmdThroughPathext() throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("maven/bin"));
        Files.writeString(bin.resolve("mvn"), "#!/bin/sh");   // the Unix script is ignored on Windows
        Files.writeString(bin.resolve("mvn.cmd"), "@echo off");

        ExecutableResolver resolver = new ExecutableResolver(true, List.of(bin), WINDOWS_EXT);

        assertThat(resolver.resolve("mvn")).contains(bin.resolve("mvn.cmd"));
    }

    @Test
    void windowsPrefersEarlierPathEntries() throws IOException {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path second = Files.createDirectories(tmp.resolve("second"));
        Files.writeString(first.resolve("git.exe"), "");
        Files.writeString(second.resolve("git.exe"), "");

        ExecutableResolver resolver = new ExecutableResolver(true, List.of(first, second), WINDOWS_EXT);

        assertThat(resolver.resolve("git")).contains(first.resolve("git.exe"));
    }

    @Test
    void windowsKeepsAnExplicitExtension() throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("mvnd.cmd"), "");

        ExecutableResolver resolver = new ExecutableResolver(true, List.of(bin), WINDOWS_EXT);

        assertThat(resolver.resolve("mvnd.cmd")).contains(bin.resolve("mvnd.cmd"));
    }

    @Test
    void returnsEmptyWhenMissing() {
        ExecutableResolver resolver = new ExecutableResolver(true, List.of(tmp), WINDOWS_EXT);

        assertThat(resolver.resolve("mvn")).isEmpty();
    }

    @Test
    void pathsWithSeparatorAreCheckedDirectly() throws IOException {
        Path script = Files.writeString(tmp.resolve("mvnw.cmd"), "");

        ExecutableResolver resolver = new ExecutableResolver(true, List.of(), WINDOWS_EXT);

        assertThat(resolver.resolve(script.toString())).contains(script);
    }

    @Test
    void detectsBatchFiles() {
        assertThat(ExecutableResolver.isBatchFile(Path.of("mvn.CMD"))).isTrue();
        assertThat(ExecutableResolver.isBatchFile(Path.of("build.bat"))).isTrue();
        assertThat(ExecutableResolver.isBatchFile(Path.of("git.exe"))).isFalse();
    }
}
