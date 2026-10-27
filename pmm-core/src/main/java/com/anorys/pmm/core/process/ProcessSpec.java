package com.anorys.pmm.core.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What to run: the command (executable name first), where, with which extra environment,
 * how long it may take, and an optional file receiving a copy of the output.
 */
public record ProcessSpec(List<String> command, Path workingDir, Map<String, String> environment,
                          Duration timeout, Path logFile) {

    public ProcessSpec {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment == null ? Map.of() : environment);
    }

    public static Builder of(String executable, String... args) {
        return new Builder(executable, List.of(args));
    }

    public static Builder of(String executable, List<String> args) {
        return new Builder(executable, args);
    }

    public String executable() {
        return command.get(0);
    }

    public List<String> arguments() {
        return command.subList(1, command.size());
    }

    public static final class Builder {
        private final List<String> command = new ArrayList<>();
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Path workingDir;
        private Duration timeout;
        private Path logFile;

        private Builder(String executable, List<String> args) {
            command.add(executable);
            command.addAll(args);
        }

        public Builder in(Path dir) {
            this.workingDir = dir;
            return this;
        }

        public Builder env(String name, String value) {
            environment.put(name, value);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder logTo(Path file) {
            this.logFile = file;
            return this;
        }

        public ProcessSpec build() {
            return new ProcessSpec(command, workingDir, environment, timeout, logFile);
        }
    }
}
