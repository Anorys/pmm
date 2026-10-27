package com.anorys.pmm.cli.report;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Everything one {@code pmm doctor} run found, for the JSON, JUnit XML and HTML reports.
 *
 * @param builds   empty when the builds did not run
 * @param notes    what was not checked and why
 * @param warnings what the POM scan could not read or resolve
 */
public record DoctorReport(String version, Instant generatedAt, Path workspace, boolean fetched, int exitCode,
                           List<RepositoryStatus> repositories, List<InternalUsage> dependencies,
                           List<BuildResult> builds, List<Blocker> blockers, List<Finding> findings,
                           List<String> notes, List<String> warnings) {

    public DoctorReport {
        repositories = List.copyOf(repositories);
        dependencies = List.copyOf(dependencies);
        builds = List.copyOf(builds);
        blockers = List.copyOf(blockers);
        findings = List.copyOf(findings);
        notes = List.copyOf(notes);
        warnings = List.copyOf(warnings);
    }

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** "git" for "git.behind": the category a check belongs to. */
    public static String category(Finding finding) {
        int dot = finding.checkId().indexOf('.');
        return dot < 0 ? finding.checkId() : finding.checkId().substring(0, dot);
    }

    /** Workspace-wide findings have no repository; reports file them under this name. */
    public static final String WORKSPACE = "(workspace)";

    public static String repositoryOf(Finding finding) {
        return finding.repository() == null ? WORKSPACE : finding.repository();
    }
}
