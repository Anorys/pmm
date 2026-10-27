package com.anorys.pmm.build;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.MavenConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.Platform;
import com.anorys.pmm.core.process.ExecutableResolver;
import com.anorys.pmm.core.process.ProcessException;
import com.anorys.pmm.core.process.ProcessSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Maven command line that checks one repository compiles:
 * {@code <maven> -B -ntp [-o] [-s settings] [-P profiles] [-T threads] [-pl :modules -am] <goal> <extraArgs>}.
 *
 * <p>With {@code maven.executable: auto}, {@code mvnd} is used when it is on the PATH (it keeps warm
 * JVMs between builds), else the repository's own Maven wrapper, else {@code mvn}.
 */
public final class MavenCommand {

    public enum Kind { MVND, WRAPPER, MVN, CUSTOM }

    public record Executable(Path path, Kind kind) {
    }

    private final PmmConfig config;
    private final ExecutableResolver resolver;

    public MavenCommand(PmmConfig config, ExecutableResolver resolver) {
        this.config = config;
        this.resolver = resolver;
    }

    /** @throws ProcessException when no Maven can be found */
    public Executable executable(RepositoryConfig repository) {
        String configured = config.maven().executable();
        switch (configured) {
            case "auto" -> {
                Optional<Path> mvnd = resolver.resolve("mvnd");
                if (mvnd.isPresent()) {
                    return new Executable(mvnd.get(), Kind.MVND);
                }
                Optional<Path> wrapper = wrapper(repository.path());
                if (wrapper.isPresent()) {
                    return new Executable(wrapper.get(), Kind.WRAPPER);
                }
                return new Executable(require("mvn"), Kind.MVN);
            }
            case "mvn" -> {
                return new Executable(require("mvn"), Kind.MVN);
            }
            case "mvnd" -> {
                return new Executable(require("mvnd"), Kind.MVND);
            }
            default -> {
                String path = Platform.expandHome(configured);
                Path resolved = resolver.resolve(path).orElseThrow(() -> new ProcessException(
                        "maven.executable '" + configured + "' was not found"));
                return new Executable(resolved, Kind.CUSTOM);
            }
        }
    }

    private Path require(String name) {
        return resolver.resolve(name).orElseThrow(() -> new ProcessException(
                name + " was not found on the PATH; install Maven or set maven.executable in pmm.yaml"));
    }

    private static Optional<Path> wrapper(Path repository) {
        Path script = repository.resolve(Platform.isWindows() ? "mvnw.cmd" : "mvnw");
        return Files.isRegularFile(script) && Files.exists(repository.resolve(".mvn/wrapper/maven-wrapper.properties"))
                ? Optional.of(script) : Optional.empty();
    }

    /** Arguments after the executable. */
    public List<String> arguments(RepositoryConfig repository, Kind kind, boolean offline) {
        MavenConfig maven = config.maven();
        List<String> args = new ArrayList<>(List.of("-B", "-ntp"));
        if (offline || maven.offline()) {
            args.add("-o");
        }
        if (maven.settings() != null) {
            args.add("-s");
            args.add(maven.settings().toString());
        }
        if (!maven.profiles().isEmpty()) {
            args.add("-P");
            args.add(String.join(",", maven.profiles()));
        }
        if (kind != Kind.MVND && maven.threads() != null && !maven.threads().isBlank()) {
            args.add("-T");
            args.add(maven.threads());
        }
        if (!repository.monitorsAllModules()) {
            args.add("-pl");
            args.add(String.join(",", repository.modules().stream().map(m -> ":" + m).toList()));
            args.add("-am");
        }
        args.add(maven.compileGoal());
        args.addAll(maven.extraArgs());
        return args;
    }

    public ProcessSpec spec(RepositoryConfig repository, Executable executable, boolean offline, Path logFile) {
        return ProcessSpec.of(executable.path().toString(), arguments(repository, executable.kind(), offline))
                .in(repository.path())
                .env("MAVEN_OPTS", mavenOpts())
                .timeout(Duration.ofMinutes(config.build().timeoutMinutes()))
                .logTo(logFile)
                .build();
    }

    /** The heap limit keeps parallel builds from exhausting memory; the user's MAVEN_OPTS are kept. */
    private String mavenOpts() {
        String existing = System.getenv("MAVEN_OPTS");
        String heap = "-Xmx" + config.build().memoryPerBuild();
        return existing == null || existing.isBlank() ? heap : existing + " " + heap;
    }
}
