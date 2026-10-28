package com.anorys.pmm.cli;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.PushPolicy;

import java.io.Console;
import java.io.PrintWriter;
import java.util.Locale;

/** Whether to push a rewritten branch: {@code --push}/{@code --no-push}, else {@code rebase.push}, asking on a console. */
final class PushChoice {

    private PushChoice() {
    }

    static boolean wanted(Boolean push, PmmConfig config, String branch, PrintWriter out) {
        if (push != null) {
            return push;
        }
        if (config.rebase().push() == PushPolicy.ALWAYS) {
            return true;
        }
        Console console = System.console();
        if (config.rebase().push() == PushPolicy.ASK && console != null) {
            out.flush();
            String answer = console.readLine("  push %s to %s with --force-with-lease? [y/N] ", branch, config.workspace().remote());
            return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
        }
        return false;
    }

    /** Asks a yes/no question on the console; false without a console. */
    static boolean confirm(PrintWriter out, String question) {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        out.flush();
        String answer = console.readLine("  %s [y/N] ", question);
        return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }
}
