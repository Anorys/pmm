package com.anorys.pmm.git;

import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.ProcessRunner;

import java.util.List;
import java.util.Optional;

/**
 * {@code deps.unreleased-upstream}: a repository that others depend on has commits on its base branch
 * since its latest release, so its consumers do not get them yet.
 *
 * <p>The release commit is found, in order, from {@code releaseTagPattern}, from a tag ending with the
 * version ({@code v3.1.4}, {@code core-3.1.4}), or from the oldest commit whose root pom.xml gained
 * {@code <version>3.1.4</version>} (the commit the release process made). Commits written by the
 * maven-release-plugin are not counted. The last fetched state of the base branch is used.
 */
public final class UnreleasedChanges {

    static final String RELEASE_PLUGIN_MESSAGE = "[maven-release-plugin]";

    private final PmmConfig config;
    private final ProcessRunner runner;

    public UnreleasedChanges(PmmConfig config, ProcessRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    /**
     * @param versions  the producer's possible latest releases, newest first; the first one whose
     *                  release commit is found is used
     * @param consumers the repositories that use it
     * @return a finding when the base branch has commits since that release; empty when it has none,
     *         or when no release commit can be found
     */
    public Optional<Finding> evaluate(RepositoryConfig repository, List<String> versions, List<String> consumers) {
        CheckSetting setting = config.check(KnownChecks.DEPS_UNRELEASED_UPSTREAM);
        if (!setting.enabled()) {
            return Optional.empty();
        }
        GitClient git = new GitClient(runner, repository.path());
        String base = config.workspace().remote() + "/" + config.baseBranch(repository);
        try {
            if (!git.isRepository() || !git.exists(base)) {
                return Optional.empty();
            }
            String version = null;
            Optional<String> release = Optional.empty();
            for (String candidate : versions) {
                release = releaseCommit(git, repository, candidate, base);
                if (release.isPresent()) {
                    version = candidate;
                    break;
                }
            }
            if (release.isEmpty() || !git.isAncestor(release.get(), base)) {
                return Optional.empty();
            }
            int commits = git.countCommits(release.get() + ".." + base, RELEASE_PLUGIN_MESSAGE);
            if (commits == 0) {
                return Optional.empty();
            }
            return Optional.of(new Finding(KnownChecks.DEPS_UNRELEASED_UPSTREAM, setting.severity(), repository.name(),
                    commits + " commit" + (commits == 1 ? "" : "s") + " on " + base + " since release " + version
                            + ", used by " + String.join(", ", consumers),
                    "release " + repository.name() + " if " + (consumers.size() == 1 ? "it needs" : "they need")
                            + " these changes"));
        } catch (GitException e) {
            return Optional.empty();
        }
    }

    static Optional<String> releaseCommit(GitClient git, RepositoryConfig repository, String version, String base) {
        if (repository.releaseTagPattern() != null) {
            return git.commitOf("refs/tags/" + repository.releaseTagPattern().replace("{version}", version));
        }
        Optional<String> tag = releaseTag(git.tags(), version);
        if (tag.isPresent()) {
            return git.commitOf("refs/tags/" + tag.get());
        }
        return git.oldestCommitChanging(base, "pom.xml", "<version>" + version.replace(".", "\\.") + "</version>");
    }

    /** A tag that ends with the version, not preceded by a digit or a dot ({@code 13.1.4} is not {@code 3.1.4}). */
    static Optional<String> releaseTag(List<String> tags, String version) {
        if (tags.contains(version)) {
            return Optional.of(version);
        }
        return tags.stream()
                .filter(t -> t.endsWith(version))
                .filter(t -> {
                    char before = t.charAt(t.length() - version.length() - 1);
                    return !Character.isDigit(before) && before != '.';
                })
                .min((a, b) -> Integer.compare(a.length(), b.length()));
    }
}
