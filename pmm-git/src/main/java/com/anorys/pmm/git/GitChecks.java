package com.anorys.pmm.git;

import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.GitClient.Operation;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns repository statuses into findings, following the severities and thresholds of the configuration. */
public final class GitChecks {

    private final PmmConfig config;
    private final Clock clock;

    public GitChecks(PmmConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public List<Finding> evaluate(List<RepositoryStatus> statuses) {
        List<Finding> findings = new ArrayList<>();
        for (RepositoryStatus status : statuses) {
            evaluate(status, findings);
        }
        return findings;
    }

    private void evaluate(RepositoryStatus status, List<Finding> findings) {
        if (!status.readable()) {
            add(findings, KnownChecks.GIT_NOT_CLONED, status, status.error(),
                    "clone it, or set 'enabled: false' for it in pmm.local.yaml");
            return;
        }
        if (status.fetchError() != null) {
            add(findings, KnownChecks.GIT_FETCH_FAILED, status, status.fetchError()
                    + "; using the last fetched state", "check the network, VPN or credentials, then fetch again");
        }
        if (status.baseMissing()) {
            add(findings, KnownChecks.GIT_BASE_MISSING, status, "base branch " + status.baseRef() + " does not exist",
                    "check 'baseBranch' for this repository in pmm.yaml");
        } else if (status.behind() > 0) {
            evaluateBehind(status, findings);
        }
        WorkingTreeStatus tree = status.tree();
        if (tree.operation() != Operation.NONE) {
            add(findings, KnownChecks.GIT_DIRTY, status,
                    "a " + tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-') + " is in progress",
                    "finish it or abort it before rebasing");
        } else if (!tree.clean()) {
            add(findings, KnownChecks.GIT_DIRTY, status, describeChanges(tree), null);
        }
    }

    private void evaluateBehind(RepositoryStatus status, List<Finding> findings) {
        CheckSetting setting = config.check(KnownChecks.GIT_BEHIND);
        if (!setting.enabled()) {
            return;
        }
        int maxCommits = setting.intOption("maxCommits", 20);
        int maxDays = setting.intOption("maxDays", 7);
        long days = status.behindSince() == null ? 0 : Duration.between(status.behindSince(), clock.instant()).toDays();
        boolean tooFar = status.behind() >= maxCommits || days >= maxDays;

        String message = status.behind() + " commit" + (status.behind() == 1 ? "" : "s") + " behind " + status.baseRef()
                + (days > 0 ? ", oldest missing commit " + days + " day" + (days == 1 ? "" : "s") + " old" : "");
        String suggestion = status.canFastForward()
                ? "fast-forward: git merge --ff-only " + status.baseRef()
                : "rebase " + status.tree().branch() + " onto " + status.baseRef();
        Severity severity = tooFar ? setting.severity() : Severity.INFO;
        findings.add(new Finding(KnownChecks.GIT_BEHIND, severity, status.name(), message, suggestion));
    }

    private void add(List<Finding> findings, String checkId, RepositoryStatus status, String message, String suggestion) {
        CheckSetting setting = config.check(checkId);
        if (setting.enabled()) {
            findings.add(new Finding(checkId, setting.severity(), status.name(), message, suggestion));
        }
    }

    static String describeChanges(WorkingTreeStatus tree) {
        List<String> parts = new ArrayList<>();
        if (tree.conflicted() > 0) {
            parts.add(tree.conflicted() + " conflicted");
        }
        if (tree.changed() > 0) {
            parts.add(tree.changed() + " modified");
        }
        if (tree.untracked() > 0) {
            parts.add(tree.untracked() + " untracked");
        }
        return "uncommitted changes: " + String.join(", ", parts);
    }
}
