package com.anorys.pmm.build;

import com.anorys.pmm.build.BuildResult.Status;
import com.anorys.pmm.build.Failure.MissingArtifact;
import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.check.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns build results into findings, and says which repository blocks which. */
public final class BuildChecks {

    /** {@code producer} must release {@code artifacts} before {@code blocked} can build. */
    public record Blocker(String producer, Set<String> blocked, Set<String> artifacts) {
    }

    private final PmmConfig config;

    public BuildChecks(PmmConfig config) {
        this.config = config;
    }

    public List<Finding> evaluate(List<BuildResult> results) {
        List<Finding> findings = new ArrayList<>();
        for (BuildResult result : results) {
            switch (result.status()) {
                case BLOCKED -> add(findings, KnownChecks.BUILD_BLOCKED, result, blockedMessage(result), blockedSuggestion(result));
                case FAILED -> add(findings, KnownChecks.BUILD_FAILED, result, failedMessage(result), "see " + result.log());
                default -> {
                }
            }
        }
        return findings;
    }

    /** Monitored repositories that block others, from the artifacts the blocked builds could not download. */
    public static List<Blocker> blockers(List<BuildResult> results) {
        Map<String, Set<String>> blocked = new LinkedHashMap<>();
        Map<String, Set<String>> artifacts = new LinkedHashMap<>();
        for (BuildResult result : results) {
            if (result.status() != Status.BLOCKED) {
                continue;
            }
            for (MissingArtifact missing : result.failure().missing()) {
                if (missing.producer() != null) {
                    blocked.computeIfAbsent(missing.producer(), k -> new LinkedHashSet<>()).add(result.repository());
                    artifacts.computeIfAbsent(missing.producer(), k -> new LinkedHashSet<>()).add(missing.toString());
                }
            }
        }
        return blocked.keySet().stream()
                .map(p -> new Blocker(p, Set.copyOf(blocked.get(p)), Set.copyOf(artifacts.get(p))))
                .toList();
    }

    private static String blockedMessage(BuildResult result) {
        return "cannot build: " + result.failure().missing().stream()
                .map(m -> m + (m.producer() == null ? " (" + m.artifact().groupId() + ")" : " from " + m.producer())
                        + " could not be downloaded")
                .collect(Collectors.joining("; "));
    }

    private static String blockedSuggestion(BuildResult result) {
        Map<String, List<String>> byProducer = new LinkedHashMap<>();
        List<String> external = new ArrayList<>();
        for (MissingArtifact missing : result.failure().missing()) {
            if (missing.producer() != null) {
                byProducer.computeIfAbsent(missing.producer(), k -> new ArrayList<>()).add(missing.version());
            } else {
                external.add(missing.artifact() + ":" + missing.version());
            }
        }
        List<String> actions = new ArrayList<>();
        byProducer.forEach((producer, versions) -> actions.add(
                "release " + producer + " " + String.join(", ", new LinkedHashSet<>(versions)) + " if it is not in Nexus yet"));
        if (!external.isEmpty()) {
            actions.add("check the version and the Nexus access (or drop --offline) for " + String.join(", ", external));
        }
        return String.join("; ", actions);
    }

    private static String failedMessage(BuildResult result) {
        Failure failure = result.failure();
        String where = failure.module() == null ? "" : failure.module() + " ";
        return switch (failure.kind()) {
            case COMPILATION -> where + "does not compile"
                    + (failure.detail() == null ? "" : ": " + failure.detail());
            case TIMEOUT -> "build timed out after " + format(result) + " (build.timeoutMinutes)";
            default -> "build failed" + (failure.module() == null ? "" : " in " + failure.module())
                    + (failure.detail() == null ? "" : ": " + failure.detail());
        };
    }

    private static String format(BuildResult result) {
        long minutes = result.duration().toMinutes();
        return minutes > 0 ? minutes + " min" : result.duration().toSeconds() + " s";
    }

    private void add(List<Finding> findings, String checkId, BuildResult result, String message, String suggestion) {
        CheckSetting setting = config.check(checkId);
        if (setting.enabled()) {
            findings.add(new Finding(checkId, setting.severity(), result.repository(), message, suggestion));
        }
    }
}
