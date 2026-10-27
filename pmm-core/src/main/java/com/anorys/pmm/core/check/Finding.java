package com.anorys.pmm.core.check;

import com.anorys.pmm.core.Severity;

import java.util.Collection;
import java.util.Objects;

/**
 * One result of a check, about one repository (or the whole workspace when {@code repository}
 * is {@code null}).
 *
 * @param suggestion what to do about it, or {@code null}
 */
public record Finding(String checkId, Severity severity, String repository, String message, String suggestion) {

    public Finding {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    /** The highest severity among {@code findings}, {@link Severity#OFF} when there are none. */
    public static Severity highest(Collection<Finding> findings) {
        return findings.stream().map(Finding::severity).max(Enum::compareTo).orElse(Severity.OFF);
    }
}
