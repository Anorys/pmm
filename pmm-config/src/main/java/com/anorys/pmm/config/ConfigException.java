package com.anorys.pmm.config;

import java.nio.file.Path;
import java.util.List;

/** The configuration cannot be read or is invalid. Carries every problem found, not just the first. */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final transient List<String> problems;

    public ConfigException(Path file, List<String> problems) {
        super(format(file, problems));
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    public ConfigException(Path file, String problem, Throwable cause) {
        super(format(file, List.of(problem)), cause);
        this.file = file;
        this.problems = List.of(problem);
    }

    public Path file() {
        return file;
    }

    public List<String> problems() {
        return problems;
    }

    private static String format(Path file, List<String> problems) {
        StringBuilder sb = new StringBuilder("Invalid configuration");
        if (file != null) {
            sb.append(" in ").append(file);
        }
        sb.append(':');
        for (String problem : problems) {
            sb.append(System.lineSeparator()).append("  - ").append(problem);
        }
        return sb.toString();
    }
}
