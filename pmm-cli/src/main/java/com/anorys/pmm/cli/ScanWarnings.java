package com.anorys.pmm.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.List;

/** Prints what the POM scan could not read or resolve, on standard error. */
final class ScanWarnings {

    private ScanWarnings() {
    }

    static void print(CommandLine commandLine, List<String> warnings) {
        PrintWriter err = commandLine.getErr();
        for (String warning : warnings) {
            err.println(commandLine.getColorScheme().text("@|yellow warning:|@ ") + warning);
        }
        err.flush();
    }

    /** Scan warnings count as warnings for {@code --strict}, like findings. */
    static int exitCode(int findingsExitCode, List<String> warnings, boolean strict) {
        if (findingsExitCode == ExitCodes.OK && strict && !warnings.isEmpty()) {
            return ExitCodes.WARNINGS;
        }
        return findingsExitCode;
    }
}
