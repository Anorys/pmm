package com.anorys.pmm.git;

import com.anorys.pmm.git.GitClient.Operation;

import java.util.List;

/**
 * The parsed output of {@code git status --porcelain=v2 --branch}.
 *
 * @param branch          current branch, {@code null} when HEAD is detached
 * @param upstream        tracked remote branch, {@code null} when none
 * @param aheadOfUpstream local commits not pushed yet (0 without upstream)
 * @param behindUpstream  remote commits not pulled yet (0 without upstream)
 * @param changed         tracked files with staged or unstaged changes
 * @param untracked       untracked files
 * @param conflicted      files with unresolved conflicts
 */
public record WorkingTreeStatus(String branch, String upstream, int aheadOfUpstream, int behindUpstream,
                                int changed, int untracked, int conflicted, Operation operation) {

    public boolean detached() {
        return branch == null;
    }

    public boolean clean() {
        return changed == 0 && untracked == 0 && conflicted == 0;
    }

    static WorkingTreeStatus parse(List<String> lines, Operation operation) {
        String branch = null;
        String upstream = null;
        int ahead = 0;
        int behind = 0;
        int changed = 0;
        int untracked = 0;
        int conflicted = 0;
        for (String line : lines) {
            if (line.startsWith("# branch.head ")) {
                String head = line.substring("# branch.head ".length()).trim();
                branch = head.equals("(detached)") ? null : head;
            } else if (line.startsWith("# branch.upstream ")) {
                upstream = line.substring("# branch.upstream ".length()).trim();
            } else if (line.startsWith("# branch.ab ")) {
                String[] parts = line.substring("# branch.ab ".length()).trim().split("\\s+");
                ahead = Math.abs(Integer.parseInt(parts[0]));
                behind = Math.abs(Integer.parseInt(parts[1]));
            } else if (line.startsWith("1 ") || line.startsWith("2 ")) {
                changed++;
            } else if (line.startsWith("u ")) {
                conflicted++;
            } else if (line.startsWith("? ")) {
                untracked++;
            }
        }
        return new WorkingTreeStatus(branch, upstream, ahead, behind, changed, untracked, conflicted, operation);
    }
}
