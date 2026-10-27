package com.anorys.pmm.core.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * A scanned repository.
 *
 * @param version version of the root pom.xml, the one a release of this repository carries
 * @param modules every module of the reactor, the root first
 */
public record RepositoryInfo(String name, Path path, String version, List<ModuleInfo> modules) {

    public RepositoryInfo {
        modules = List.copyOf(modules);
    }

    public List<ModuleInfo> monitoredModules() {
        return modules.stream().filter(ModuleInfo::monitored).toList();
    }
}
