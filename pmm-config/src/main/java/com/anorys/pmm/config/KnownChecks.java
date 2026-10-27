package com.anorys.pmm.config;

import com.anorys.pmm.core.Severity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every check the tool knows, with its default setting and the options it accepts.
 * A check id that is not listed here is rejected, so typos in pmm.yaml are caught.
 */
public final class KnownChecks {

    public static final String DEPS_RELEASE_MISSING = "deps.release-missing";
    public static final String DEPS_INTERNAL_OUTDATED = "deps.internal-outdated";
    public static final String DEPS_UNRELEASED_UPSTREAM = "deps.unreleased-upstream";
    public static final String DEPS_UNRESOLVABLE = "deps.unresolvable";
    public static final String DEPS_CONVERGENCE = "deps.convergence";
    public static final String DEPS_INTERNAL_SNAPSHOT = "deps.internal-snapshot";
    public static final String DEPS_UNDECLARED_INTERNAL = "deps.undeclared-internal";
    public static final String DEPS_NEXUS_UNAVAILABLE = "deps.nexus-unavailable";
    public static final String GIT_BEHIND = "git.behind";
    public static final String GIT_DIRTY = "git.dirty";
    public static final String GIT_NOT_CLONED = "git.not-cloned";
    public static final String GIT_FETCH_FAILED = "git.fetch-failed";
    public static final String GIT_BASE_MISSING = "git.base-missing";
    public static final String GRAPH_CYCLE = "graph.cycle";
    public static final String BUILD_FAILED = "build.failed";
    public static final String BUILD_BLOCKED = "build.blocked";

    private static final Map<String, CheckSetting> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> OPTIONS = new LinkedHashMap<>();

    static {
        define(DEPS_RELEASE_MISSING, Severity.ERROR, Map.of());
        define(DEPS_INTERNAL_OUTDATED, Severity.WARN, Map.of("allowedLag", "patch"));
        define(DEPS_UNRELEASED_UPSTREAM, Severity.INFO, Map.of());
        define(DEPS_UNRESOLVABLE, Severity.ERROR, Map.of());
        define(DEPS_CONVERGENCE, Severity.WARN, Map.of());
        define(DEPS_INTERNAL_SNAPSHOT, Severity.ERROR, Map.of());
        define(DEPS_UNDECLARED_INTERNAL, Severity.WARN, Map.of());
        define(DEPS_NEXUS_UNAVAILABLE, Severity.WARN, Map.of());
        define(GIT_BEHIND, Severity.WARN, Map.of("maxCommits", "20", "maxDays", "7"));
        define(GIT_DIRTY, Severity.INFO, Map.of());
        define(GIT_NOT_CLONED, Severity.WARN, Map.of());
        define(GIT_FETCH_FAILED, Severity.WARN, Map.of());
        define(GIT_BASE_MISSING, Severity.ERROR, Map.of());
        define(GRAPH_CYCLE, Severity.ERROR, Map.of());
        define(BUILD_FAILED, Severity.ERROR, Map.of());
        define(BUILD_BLOCKED, Severity.ERROR, Map.of());
    }

    private KnownChecks() {
    }

    private static void define(String id, Severity severity, Map<String, String> options) {
        DEFAULTS.put(id, new CheckSetting(severity, options));
        OPTIONS.put(id, options.keySet());
    }

    /** Default settings, in a stable order. */
    public static Map<String, CheckSetting> defaults() {
        return new LinkedHashMap<>(DEFAULTS);
    }

    public static boolean isKnown(String id) {
        return DEFAULTS.containsKey(id);
    }

    public static Set<String> options(String id) {
        return OPTIONS.getOrDefault(id, Set.of());
    }

    /** A problem with the value of a check option, or {@code null} when it is valid. */
    static String validateOption(String option, String value) {
        return switch (option) {
            case "allowedLag" -> Set.of("none", "patch", "minor", "major").contains(value)
                    ? null : "must be one of none, patch, minor, major (was '" + value + "')";
            case "maxCommits", "maxDays" -> value.matches("\\d{1,6}")
                    ? null : "must be a whole number (was '" + value + "')";
            default -> null;
        };
    }

    public static Set<String> ids() {
        return DEFAULTS.keySet();
    }
}
