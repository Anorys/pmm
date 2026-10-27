package com.anorys.pmm.cli;

import com.anorys.pmm.cli.update.GitHubReleases;
import com.anorys.pmm.cli.update.SelfUpdater;
import com.anorys.pmm.cli.update.UpdateCache;
import com.anorys.pmm.cli.update.UpdateException;
import com.anorys.pmm.cli.update.UpdateNotifier;
import com.anorys.pmm.config.ConfigException;
import com.anorys.pmm.core.process.ProcessException;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SelfUpdater.cleanUp();
        CommandLine cli = commandLine();
        CommandLine.IExecutionStrategy run = cli.getExecutionStrategy();
        AtomicReference<UpdateNotifier> notifier = new AtomicReference<>();
        cli.setExecutionStrategy(parseResult -> {
            updateNotifier(parseResult).ifPresent(notifier::set);
            return run.execute(parseResult);
        });
        int exitCode = cli.execute(args);
        // After the command and its error message, if any.
        if (notifier.get() != null) {
            notifier.get().finish(cli.getErr(), cli.getColorScheme().ansi()::string);
        }
        System.exit(exitCode);
    }

    /** The new-release notice, for commands run by someone at a terminal (see {@link UpdateNotifier}). */
    private static Optional<UpdateNotifier> updateNotifier(ParseResult parseResult) {
        if (parseResult.isUsageHelpRequested() || parseResult.isVersionHelpRequested()
                || parseResult.commandSpec().commandLine().<PmmCommand>getCommand().noUpdateCheck) {
            return Optional.empty();
        }
        for (ParseResult sub = parseResult.subcommand(); sub != null; sub = sub.subcommand()) {
            Object command = sub.commandSpec().userObject();
            if (sub.isUsageHelpRequested() || command instanceof SelfUpdateCommand
                    || command instanceof CommandLine.HelpCommand) {
                return Optional.empty();
            }
        }
        Map<String, String> env = System.getenv();
        String version = VersionProvider.version();
        return UpdateNotifier.start(version, System.console() != null, env, UpdateCache.forUser(env),
                Clock.systemUTC(), current -> GitHubReleases.fromEnvironment(env, current, UpdateNotifier.LOOKUP_TIMEOUT));
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
            if (e instanceof ConfigException || e instanceof ProcessException || e instanceof UpdateException) {
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
