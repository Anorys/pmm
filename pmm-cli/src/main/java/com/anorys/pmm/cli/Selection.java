package com.anorys.pmm.cli;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

import java.util.ArrayList;
import java.util.List;

/** Repositories named on the command line, or every monitored one when none is named. */
final class Selection {

    private Selection() {
    }

    static List<RepositoryConfig> repositories(CommandSpec spec, PmmConfig config, List<String> names) {
        if (names.isEmpty()) {
            return config.monitoredRepositories();
        }
        List<RepositoryConfig> selected = new ArrayList<>();
        for (String name : names) {
            selected.add(config.repository(name).orElseThrow(() -> new ParameterException(spec.commandLine(),
                    "Unknown repository '" + name + "'; known: "
                            + String.join(", ", config.repositories().stream().map(RepositoryConfig::name).toList()))));
        }
        return selected;
    }
}
