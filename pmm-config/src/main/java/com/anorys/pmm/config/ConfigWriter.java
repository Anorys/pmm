package com.anorys.pmm.config;

import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders an effective configuration back to YAML, with every default made explicit. */
public final class ConfigWriter {

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build());

    private ConfigWriter() {
    }

    public static String toYaml(PmmConfig config) {
        ObjectNode root = YAML.createObjectNode();

        ObjectNode workspace = root.putObject("workspace");
        workspace.put("root", path(config.workspace().root()));
        workspace.put("baseBranch", config.workspace().baseBranch());
        workspace.put("remote", config.workspace().remote());

        ArrayNode repositories = root.putArray("repositories");
        for (RepositoryConfig repo : config.repositories()) {
            ObjectNode node = repositories.addObject();
            node.put("name", repo.name());
            node.put("path", path(repo.path()));
            putIfSet(node, "url", repo.url());
            putIfSet(node, "baseBranch", repo.baseBranch());
            strings(node.putArray("modules"), repo.modules());
            node.put("enabled", repo.enabled());
            putIfSet(node, "releaseTagPattern", repo.releaseTagPattern());
        }

        ObjectNode maven = root.putObject("maven");
        maven.put("executable", config.maven().executable());
        putIfSet(maven, "settings", config.maven().settings() == null ? null : path(config.maven().settings()));
        strings(maven.putArray("profiles"), config.maven().profiles());
        maven.put("offline", config.maven().offline());
        maven.put("threads", config.maven().threads());
        strings(maven.putArray("extraArgs"), config.maven().extraArgs());
        maven.put("compileGoal", config.maven().compileGoal());

        ObjectNode nexus = root.putObject("nexus");
        putIfSet(nexus, "repositoryId", config.nexus().repositoryId());
        putIfSet(nexus, "url", config.nexus().url());

        strings(root.putArray("internalGroups"), config.internalGroups());

        ObjectNode checks = root.putObject("checks");
        for (String id : KnownChecks.ids()) {
            CheckSetting setting = config.check(id);
            if (setting.options().isEmpty()) {
                checks.put(id, setting.severity().id());
            } else {
                ObjectNode check = checks.putObject(id);
                check.put("severity", setting.severity().id());
                setting.options().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> check.put(e.getKey(), e.getValue()));
            }
        }

        ObjectNode rebase = root.putObject("rebase");
        rebase.put("requireCleanTree", config.rebase().requireCleanTree());
        rebase.put("autostash", config.rebase().autostash());
        rebase.put("backupRefs", config.rebase().backupRefs());
        rebase.put("push", config.rebase().push().name().toLowerCase(Locale.ROOT));

        ObjectNode branches = root.putObject("branches");
        strings(branches.putArray("pushProtected"), config.branches().pushProtected());
        strings(branches.putArray("updateProtected"), config.branches().updateProtected());
        ObjectNode strategies = branches.putObject("strategies");
        config.branches().strategies().forEach((glob, strategy) -> strategies.put(glob, strategy.id()));
        branches.put("stashOnSwitch", config.branches().stashOnSwitch().name().toLowerCase(Locale.ROOT));

        ObjectNode build = root.putObject("build");
        build.put("parallelRepos", config.build().parallelRepos());
        build.put("memoryPerBuild", config.build().memoryPerBuild());
        build.put("timeoutMinutes", config.build().timeoutMinutes());
        build.put("cache", config.build().cache());

        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Forward slashes read the same on every platform and need no escaping in YAML. */
    static String path(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void putIfSet(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private static void strings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
