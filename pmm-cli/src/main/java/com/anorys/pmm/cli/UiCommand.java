package com.anorys.pmm.cli;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.tui.PmmDashboard;
import com.anorys.pmm.tui.demo.DemoWorkspace;
import com.anorys.pmm.tui.service.DemoService;
import com.anorys.pmm.tui.service.LiveWorkspace;
import com.anorys.pmm.tui.theme.Theme;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(name = "ui", mixinStandardHelpOptions = true,
        description = {
                "Opens the interactive dashboard: every repository with its branch, its lag behind the base branch,",
                "its build and its dependencies, live builds with their logs, guided rebases and the dependency graph.",
                "Also opens when pmm runs without a command in a terminal. Press ? in the dashboard for the keys.",
                "PMM_ASCII=1 replaces the Unicode symbols for old consoles; NO_COLOR turns colors off."})
public class UiCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--demo", description = "Open on a made-up workspace, with simulated git, Nexus and builds.")
    boolean demo;

    @Override
    public Integer call() throws Exception {
        if (System.console() == null) {
            spec.commandLine().getErr().println(spec.commandLine().getColorScheme().errorText(
                    "pmm ui needs an interactive terminal; use 'pmm doctor' in scripts and CI."));
            return ExitCodes.TOOL_ERROR;
        }
        Theme theme = Theme.fromEnvironment();
        if (demo) {
            new PmmDashboard(new DemoService(), DemoWorkspace.root(), theme).run();
        } else {
            PmmConfig config = pmm.loadConfig();
            new PmmDashboard(new LiveWorkspace(config), config.workspace().root(), theme).run();
        }
        return ExitCodes.OK;
    }
}
