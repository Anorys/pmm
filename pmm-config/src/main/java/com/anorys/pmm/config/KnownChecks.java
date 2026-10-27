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
    public static final String GIT_BEHIND = "git.behind";
    public static final String GIT_DIRTY = "git.dirty";
    public static final String GRAPH_CYCLE = "graph.cycle";

    private static final Map<String, CheckSetting> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> OPTIONS = new LinkedHashMap<>();

    static {
        define(DEPS_RELEASE_MISSING, Severity.ERROR, Map.of());
        define(DEPS_INTERNAL_OUTDATED, Severity.WARN, Map.of("allowedLag", "minor"));
        define(DEPS_UNRELEASED_UPSTREAM, Severity.INFO, Map.of());
        define(DEPS_UNRESOLVABLE, Severity.ERROR, Map.of());
        define(DEPS_CONVERGENCE, Severity.WARN, Map.of());
        define(DEPS_INTERNAL_SNAPSHOT, Severity.ERROR, Map.of());
        define(DEPS_UNDECLARED_INTERNAL, Severity.WARN, Map.of());
        define(GIT_BEHIND, Severity.WARN, Map.of("maxCommits", "20", "maxDays", "7"));
        define(GIT_DIRTY, Severity.INFO, Map.of());
        define(GRAPH_CYCLE, Severity.ERROR, Map.of());
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

    public static Set<String> ids() {
        return DEFAULTS.keySet();
    }
}
