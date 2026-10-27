package com.anorys.pmm.cli;

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
}
