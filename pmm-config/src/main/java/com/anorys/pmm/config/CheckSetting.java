package com.anorys.pmm.config;

import com.anorys.pmm.core.Severity;

import java.util.Map;
import java.util.Optional;

/**
 * Severity of a check plus its check-specific options, written in YAML either as
 * {@code git.dirty: info} or as {@code git.behind: { severity: warn, maxCommits: 20 }}.
 */
public record CheckSetting(Severity severity, Map<String, String> options) {

    public CheckSetting {
        options = Map.copyOf(options);
    }

    public static CheckSetting of(Severity severity) {
        return new CheckSetting(severity, Map.of());
    }

    public boolean enabled() {
        return severity != Severity.OFF;
    }

    public Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name));
    }

    public int intOption(String name, int defaultValue) {
        return option(name).map(Integer::parseInt).orElse(defaultValue);
    }
}
