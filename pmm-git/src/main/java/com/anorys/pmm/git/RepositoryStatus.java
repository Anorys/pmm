package com.anorys.pmm.git;

import com.anorys.pmm.git.GitClient.AheadBehind;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Git state of one repository compared with its base branch.
 *
 * @param baseRef     remote base branch, for example {@code origin/develop}
 * @param tree        working tree and branch; {@code null} when the repository could not be read
 * @param vsBase      commits ahead of and behind {@code baseRef}; {@code null} when the base is missing
 * @param behindSince date of the oldest base commit not in HEAD; {@code null} when not behind
 * @param fetchError  why fetching failed (the status then uses the last fetched state), or {@code null}
 * @param error       why the repository could not be read at all, or {@code null}
 */
public record RepositoryStatus(String name, Path path, String baseRef, WorkingTreeStatus tree, AheadBehind vsBase,
                               Instant behindSince, String fetchError, String error) {

    static RepositoryStatus failed(String name, Path path, String baseRef, String error) {
        return new RepositoryStatus(name, path, baseRef, null, null, null, null, error);
    }

    public boolean readable() {
        return error == null;
    }

    public boolean baseMissing() {
        return readable() && vsBase == null;
    }

    public int ahead() {
        return vsBase == null ? 0 : vsBase.ahead();
    }

    public int behind() {
        return vsBase == null ? 0 : vsBase.behind();
    }

    /** Behind the base with no local commit of its own: a fast-forward is enough. */
    public boolean canFastForward() {
        return behind() > 0 && ahead() == 0;
    }
}
