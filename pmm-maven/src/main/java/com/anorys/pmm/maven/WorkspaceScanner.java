package com.anorys.pmm.maven;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.DependencyRef.Kind;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the pom.xml files of every enabled repository and builds the {@link Workspace} model,
 * without running Maven. Parents are looked up in the whole workspace by groupId:artifactId,
 * and properties and dependencyManagement are inherited along that parent chain.
 *
 * <p>What lives outside the workspace (a corporate parent or BOM from Nexus) is not read, so a
 * version that only such a parent defines stays unknown; those cases are reported as warnings.
 */
public final class WorkspaceScanner {

    /** The workspace model plus everything that could not be read or resolved. */
    public record ScanResult(Workspace workspace, List<String> warnings) {

        public ScanResult {
            warnings = List.copyOf(warnings);
        }
    }

    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    /** A pom.xml as written, before inheritance. */
    private record RawPom(String repository, Path pom, Model model, ArtifactKey key, String version) {
    }

    private final List<String> warnings = new ArrayList<>();
    private final Map<ArtifactKey, RawPom> index = new HashMap<>();

    private WorkspaceScanner() {
    }

    public static ScanResult scan(PmmConfig config) {
        return new WorkspaceScanner().run(config);
    }

    private ScanResult run(PmmConfig config) {
        Map<RepositoryConfig, List<RawPom>> rawByRepository = new LinkedHashMap<>();
        for (RepositoryConfig repository : config.monitoredRepositories()) {
            List<RawPom> poms = readRepository(repository);
            rawByRepository.put(repository, poms);
            for (RawPom pom : poms) {
                RawPom previous = index.putIfAbsent(pom.key(), pom);
                if (previous != null) {
                    warnings.add(pom.key() + " is produced by both " + previous.repository() + " ("
                            + previous.pom() + ") and " + pom.repository() + " (" + pom.pom() + ")");
                }
            }
        }

        List<RepositoryInfo> repositories = new ArrayList<>();
        rawByRepository.forEach((repository, poms) -> {
            if (poms.isEmpty()) {
                return;
            }
            List<ModuleInfo> modules = new ArrayList<>();
            for (RawPom pom : poms) {
                boolean monitored = repository.monitorsAllModules() || repository.modules().contains(pom.key().artifactId());
                modules.add(resolve(pom, monitored));
            }
            if (!repository.monitorsAllModules()) {
                Set<String> found = new HashSet<>();
                poms.forEach(p -> found.add(p.key().artifactId()));
                repository.modules().stream()
                        .filter(m -> !found.contains(m))
                        .forEach(m -> warnings.add(repository.name() + ": module '" + m
                                + "' listed in the configuration was not found in the reactor"));
            }
            repositories.add(new RepositoryInfo(repository.name(), repository.path(), modules.get(0).version(), modules));
        });

        Workspace workspace = new Workspace(repositories);
        warnUnresolvedInternalVersions(workspace);
        return new ScanResult(workspace, warnings);
    }

    /** Reads the root pom.xml and every module it declares, recursively, including modules of profiles. */
    private List<RawPom> readRepository(RepositoryConfig repository) {
        Path rootPom = repository.path().resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            warnings.add(repository.name() + ": no pom.xml in " + repository.path() + "; repository skipped");
            return List.of();
        }
        List<RawPom> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Deque<Path> todo = new ArrayDeque<>(List.of(rootPom));
        while (!todo.isEmpty()) {
            Path pom = todo.poll().toAbsolutePath().normalize();
            if (!seen.add(pom)) {
                continue;
            }
            Model model = read(repository.name(), pom);
            if (model == null) {
                continue;
            }
            String groupId = model.getGroupId() != null ? model.getGroupId()
                    : model.getParent() != null ? model.getParent().getGroupId() : null;
            String version = model.getVersion() != null ? model.getVersion()
                    : model.getParent() != null ? model.getParent().getVersion() : null;
            if (groupId == null || model.getArtifactId() == null) {
                warnings.add(repository.name() + ": " + pom + " has no groupId or artifactId; skipped");
                continue;
            }
            result.add(new RawPom(repository.name(), pom, model, new ArtifactKey(groupId, model.getArtifactId()), version));

            List<String> moduleNames = new ArrayList<>(model.getModules());
            for (Profile profile : model.getProfiles()) {
                moduleNames.addAll(profile.getModules());
            }
            for (String moduleName : moduleNames) {
                Path module = pom.getParent().resolve(moduleName.trim());
                Path modulePom = Files.isDirectory(module) ? module.resolve("pom.xml") : module;
                if (Files.isRegularFile(modulePom)) {
                    todo.add(modulePom);
                } else {
                    warnings.add(repository.name() + ": module '" + moduleName + "' declared in " + pom + " was not found");
                }
            }
        }
        return result;
    }

    private Model read(String repository, Path pom) {
        try (Reader reader = Files.newBufferedReader(pom)) {
            return new MavenXpp3Reader().read(reader, false);
        } catch (IOException | XmlPullParserException e) {
            warnings.add(repository + ": cannot read " + pom + ": " + e.getMessage());
            return null;
        }
    }

    /** Applies the parent chain (properties, dependencyManagement) and interpolates every reference. */
    private ModuleInfo resolve(RawPom pom, boolean monitored) {
        List<RawPom> chain = parentChain(pom);   // root ancestor first, pom last
        Map<String, String> properties = properties(chain, pom);
        Interpolator interpolator = new Interpolator(properties);

        Map<ArtifactKey, String> managedDependencies = new HashMap<>();
        Map<ArtifactKey, String> managedPlugins = new HashMap<>();
        List<DependencyRef> references = new ArrayList<>();
        for (RawPom ancestor : chain) {
            Model model = ancestor.model();
            if (model.getDependencyManagement() != null) {
                for (Dependency d : model.getDependencyManagement().getDependencies()) {
                    ArtifactKey key = new ArtifactKey(interpolator.apply(d.getGroupId()), interpolator.apply(d.getArtifactId()));
                    String version = interpolator.apply(d.getVersion());
                    if ("import".equals(d.getScope())) {
                        // An import inherited from a parent is the parent's reference, not this module's.
                        if (ancestor == pom) {
                            references.add(new DependencyRef(key, version, Kind.IMPORT, null));
                        }
                    } else {
                        managedDependencies.put(key, version);
                    }
                }
            }
            if (model.getBuild() != null && model.getBuild().getPluginManagement() != null) {
                for (Plugin p : model.getBuild().getPluginManagement().getPlugins()) {
                    managedPlugins.put(pluginKey(p, interpolator), interpolator.apply(p.getVersion()));
                }
            }
        }

        Model model = pom.model();
        Parent parent = model.getParent();
        if (parent != null) {
            references.add(new DependencyRef(new ArtifactKey(parent.getGroupId(), parent.getArtifactId()),
                    interpolator.apply(parent.getVersion()), Kind.PARENT, null));
        }
        for (Dependency d : model.getDependencies()) {
            ArtifactKey key = new ArtifactKey(interpolator.apply(d.getGroupId()), interpolator.apply(d.getArtifactId()));
            String version = d.getVersion() != null ? interpolator.apply(d.getVersion()) : managedDependencies.get(key);
            references.add(new DependencyRef(key, version, Kind.DEPENDENCY, d.getScope() == null ? "compile" : d.getScope()));
        }
        if (model.getBuild() != null) {
            for (Plugin p : model.getBuild().getPlugins()) {
                ArtifactKey key = pluginKey(p, interpolator);
                String version = p.getVersion() != null ? interpolator.apply(p.getVersion()) : managedPlugins.get(key);
                references.add(new DependencyRef(key, version, Kind.PLUGIN, null));
            }
        }

        String packaging = model.getPackaging() == null ? "jar" : model.getPackaging();
        return new ModuleInfo(pom.repository(), pom.key(), interpolator.apply(pom.version()), packaging, pom.pom(),
                references, monitored);
    }

    /** Ancestors found in the workspace, outermost first, ending with {@code pom} itself. */
    private List<RawPom> parentChain(RawPom pom) {
        List<RawPom> chain = new ArrayList<>();
        Set<ArtifactKey> seen = new HashSet<>();
        for (RawPom current = pom; current != null && seen.add(current.key()); ) {
            chain.add(current);
            Parent parent = current.model().getParent();
            current = parent == null ? null : index.get(new ArtifactKey(parent.getGroupId(), parent.getArtifactId()));
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Map<String, String> properties(List<RawPom> chain, RawPom pom) {
        Map<String, String> properties = new HashMap<>();
        for (RawPom ancestor : chain) {
            ancestor.model().getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
        }
        Model model = pom.model();
        put(properties, pom.version(), "project.version", "pom.version", "version");
        put(properties, pom.key().groupId(), "project.groupId", "pom.groupId", "groupId");
        put(properties, pom.key().artifactId(), "project.artifactId", "pom.artifactId", "artifactId");
        if (model.getParent() != null) {
            put(properties, model.getParent().getVersion(), "project.parent.version", "parent.version");
            put(properties, model.getParent().getGroupId(), "project.parent.groupId", "parent.groupId");
        }
        return properties;
    }

    private static void put(Map<String, String> properties, String value, String... names) {
        if (value != null) {
            for (String name : names) {
                properties.put(name, value);
            }
        }
    }

    private static ArtifactKey pluginKey(Plugin plugin, Interpolator interpolator) {
        String groupId = plugin.getGroupId() == null ? DEFAULT_PLUGIN_GROUP : interpolator.apply(plugin.getGroupId());
        return new ArtifactKey(groupId, interpolator.apply(plugin.getArtifactId()));
    }

    /** An internal artifact referenced without a usable version is worth knowing about. */
    private void warnUnresolvedInternalVersions(Workspace workspace) {
        for (ModuleInfo module : workspace.modules()) {
            for (DependencyRef reference : module.references()) {
                if (reference.kind() == Kind.PARENT || workspace.producer(reference.artifact()).isEmpty()) {
                    continue;
                }
                if (reference.version() == null) {
                    warnings.add(module.repository() + ": " + module.artifactId() + " uses " + reference.artifact()
                            + " without a version known in the workspace (managed by an external parent or BOM?)");
                } else if (reference.hasUnresolvedVersion()) {
                    warnings.add(module.repository() + ": " + module.artifactId() + " uses " + reference.artifact()
                            + " with unresolved version " + reference.version());
                }
            }
        }
    }
}
