package com.anorys.pmm.cli;

import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;

import java.util.Collection;

/** Process exit codes, the contract with CI pipelines. */
public final class ExitCodes {

    /** Everything checked is healthy. */
    public static final int OK = 0;
    /** Only warnings were found, and {@code --strict} was given. */
    public static final int WARNINGS = 1;
    /** At least one check reported an error. */
    public static final int ERRORS = 2;
    /** The tool itself could not run: bad configuration, bad arguments, missing executable. */
    public static final int TOOL_ERROR = 3;

    private ExitCodes() {
    }

    /** {@link #ERRORS} for any error; {@link #WARNINGS} for warnings when strict; otherwise {@link #OK}. */
    public static int fromFindings(Collection<Finding> findings, boolean strict) {
        Severity highest = Finding.highest(findings);
        if (highest == Severity.ERROR) {
            return ERRORS;
        }
        return strict && highest == Severity.WARN ? WARNINGS : OK;
    }
}
