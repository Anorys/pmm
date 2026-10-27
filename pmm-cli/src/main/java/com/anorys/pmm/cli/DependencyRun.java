package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.Table;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.git.UnreleasedChanges;
import com.anorys.pmm.maven.DependencyChecks;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.maven.DependencyChecks.ProducerRelease;
import com.anorys.pmm.maven.DependencyChecks.Report;
import com.anorys.pmm.maven.DependencyChecks.State;
import com.anorys.pmm.maven.nexus.MetadataSource;
import com.anorys.pmm.maven.nexus.NexusClient;
import com.anorys.pmm.maven.nexus.NexusSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The dependency checks shared by {@code pmm deps} and {@code pmm doctor}: Nexus, then git for unreleased changes. */
final class DependencyRun {

    /**
     * @param notes what was not checked and why, and which Nexus was queried
     */
    record Result(Report report, List<Finding> findings, List<String> notes) {
    }

    private DependencyRun() {
    }

    static Result run(PmmConfig config, Workspace workspace, boolean offline, ProcessRunner runner) {
        List<String> notes = new ArrayList<>();
        MetadataSource source = null;
        if (offline || config.maven().offline()) {
            notes.add("offline: Nexus not queried, release checks skipped");
        } else {
            NexusSettings.Resolution nexus = NexusSettings.resolve(config);
            notes.addAll(nexus.notes());
            if (nexus.endpoint().isPresent()) {
                notes.add("Nexus: " + nexus.endpoint().get());
                source = new NexusClient(nexus.endpoint().get());
            }
        }
        Report report = new DependencyChecks(config, workspace, source).evaluate();

        List<Finding> findings = new ArrayList<>(report.findings());
        UnreleasedChanges unreleased = new UnreleasedChanges(config, runner);
        for (ProducerRelease release : report.releases()) {
            config.repository(release.repository())
                    .flatMap(repository -> unreleased.evaluate(repository, release.versions(), release.consumers()))
                    .ifPresent(findings::add);
        }
        return new Result(report, findings, notes);
    }

    /** One row per version of an internal artifact that a repository uses from another one. */
    static String table(List<InternalUsage> usages) {
        if (usages.isEmpty()) {
            return "No repository uses an artifact of another monitored repository." + System.lineSeparator();
        }
        Table table = new Table("REPO", "USES", "FROM", "VERSION", "LATEST", "STATE");
        for (InternalUsage usage : usages) {
            String latest = usage.latest() != null ? usage.latest() : usage.state() == State.UNKNOWN ? "?" : "-";
            table.row(usage.consumer(), usage.artifact().artifactId(), Objects.toString(usage.producer(), "-"),
                    usage.version(), latest, usage.state().label());
        }
        return table.render();
    }
}
