package com.anorys.pmm.cli;

import com.anorys.pmm.config.ConfigException;
import com.anorys.pmm.core.process.ProcessException;
import picocli.CommandLine;

import java.io.PrintWriter;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Builds the command line with the exit codes and error handling shared by every command. */
    public static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new PmmCommand());
        cli.setParameterExceptionHandler((e, args) -> {
            CommandLine commandLine = e.getCommandLine();
            PrintWriter err = commandLine.getErr();
            err.println(commandLine.getColorScheme().errorText(e.getMessage()));
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(e, err)) {
                err.println("Run '" + commandLine.getCommandSpec().qualifiedName() + " --help' for usage.");
            }
            err.flush();
            return ExitCodes.TOOL_ERROR;
        });
        cli.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
            PrintWriter err = commandLine.getErr();
            if (e instanceof ConfigException || e instanceof ProcessException) {
                err.println(commandLine.getColorScheme().errorText(e.getMessage()));
            } else if (isVerbose(commandLine)) {
                e.printStackTrace(err);
            } else {
                err.println(commandLine.getColorScheme().errorText("Unexpected error: " + e));
                err.println("Run again with -v for the stack trace.");
            }
            err.flush();
            return ExitCodes.TOOL_ERROR;
        });
        return cli;
    }

    private static boolean isVerbose(CommandLine commandLine) {
        CommandLine root = commandLine;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root.<PmmCommand>getCommand().verbose;
    }
}
