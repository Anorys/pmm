package com.anorys.pmm.core;

import java.util.Locale;

/** Severity of a check finding. {@link #OFF} disables a check entirely. */
public enum Severity {
    OFF,
    INFO,
    WARN,
    ERROR;

    /** Parses a severity case-insensitively; returns {@code null} for unknown values. */
    public static Severity parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
