package com.anorys.pmm.git;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The branch rules of {@code branches:}. Protection has two sides: a push-protected branch (develop,
 * main...) is never pushed by pmm, so its remote history is never rewritten, but its local copy can
 * still take the commits of the remote; an update-protected branch is not changed locally at all.
 */
public final class BranchProtection {

    private final PmmConfig config;

    public BranchProtection(PmmConfig config) {
        this.config = config;
    }

    public boolean pushProtected(String branch) {
        return matches(config.branches().pushProtected(), branch);
    }

    public boolean updateProtected(String branch) {
        return matches(config.branches().updateProtected(), branch);
    }

    /**
     * The strategy of the first glob of {@code branches.strategies} matching the branch; otherwise a
     * fast-forward for push-protected branches (they follow the remote) and a rebase for the others.
     */
    public UpdateStrategy defaultStrategy(String branch) {
        for (Map.Entry<String, UpdateStrategy> entry : config.branches().strategies().entrySet()) {
            if (glob(entry.getKey()).matcher(branch).matches()) {
                return entry.getValue();
            }
        }
        return pushProtected(branch) ? UpdateStrategy.FF : UpdateStrategy.REBASE;
    }

    private static boolean matches(List<String> globs, String branch) {
        return branch != null && globs.stream().anyMatch(p -> glob(p).matcher(branch).matches());
    }

    /** {@code *} matches anything but a slash, {@code **} anything. */
    static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                boolean twice = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                regex.append(twice ? ".*" : "[^/]*");
                i += twice ? 1 : 0;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
