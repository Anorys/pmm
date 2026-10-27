package com.anorys.pmm.config;

import com.anorys.pmm.config.PmmConfig.BuildConfig;
import com.anorys.pmm.config.PmmConfig.MavenConfig;
import com.anorys.pmm.config.PmmConfig.NexusConfig;
import com.anorys.pmm.config.PmmConfig.PushPolicy;
import com.anorys.pmm.config.PmmConfig.RebaseConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.WorkspaceConfig;
import com.anorys.pmm.core.Platform;
import com.anorys.pmm.core.Severity;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns a merged YAML tree into a {@link PmmConfig}, applying defaults and collecting every problem. */
final class ConfigParser {

    static final String DEFAULT_BASE_BRANCH = "develop";
    static final String DEFAULT_REMOTE = "origin";
    static final List<String> DEFAULT_PROTECTED_BRANCHES = List.of("develop", "main", "master", "release/*");

    private static final Pattern REPO_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern MEMORY = Pattern.compile("\\d+[kKmMgG]?");
    private static final Set<String> COMPILE_GOALS = Set.of("compile", "test-compile");

    private final Path configFile;
    private final List<String> problems = new ArrayList<>();

    ConfigParser(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    PmmConfig parse(JsonNode tree) {
        Section root = new Section(tree, "", problems);
        WorkspaceConfig workspace = workspace(root.section("workspace"));
        List<RepositoryConfig> repositories = repositories(root, workspace.root());
        MavenConfig maven = maven(root.section("maven"));
        NexusConfig nexus = nexus(root.section("nexus"));
        List<String> internalGroups = root.stringList("internalGroups", List.of());
        Map<String, CheckSetting> checks = checks(root.section("checks"));
        RebaseConfig rebase = rebase(root.section("rebase"));
        BuildConfig build = build(root.section("build"));
        root.rejectUnknownKeys();

        if (!problems.isEmpty()) {
            throw new ConfigException(configFile, problems);
        }
        return new PmmConfig(configFile, workspace, repositories, maven, nexus, internalGroups, checks, rebase, build);
    }

    private WorkspaceConfig workspace(Section s) {
        Path baseDir = configFile.getParent();
        Path root = path(s, "root", baseDir, baseDir);
        String baseBranch = s.string("baseBranch", DEFAULT_BASE_BRANCH);
        String remote = s.string("remote", DEFAULT_REMOTE);
        s.rejectUnknownKeys();
        return new WorkspaceConfig(root, baseBranch, remote);
    }

    private List<RepositoryConfig> repositories(Section root, Path workspaceRoot) {
        if (!root.has("repositories")) {
            root.problem("repositories", "is required: list at least one repository");
        }
        List<RepositoryConfig> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Section s : root.sectionList("repositories")) {
            String name = s.requiredString("name");
            if (name != null && !REPO_NAME.matcher(name).matches()) {
                s.problem("name", "may only contain letters, digits, '.', '_' and '-'");
            }
            if (name != null && !names.add(name)) {
                s.problem("name", "'" + name + "' is declared more than once");
            }
            Path path = path(s, "path", workspaceRoot, name == null ? workspaceRoot : workspaceRoot.resolve(name));
            String url = s.string("url", null);
            String baseBranch = s.string("baseBranch", null);
            List<String> modules = s.stringList("modules", List.of("*"));
            if (modules.isEmpty()) {
                s.problem("modules", "must not be empty; use [\"*\"] for all modules");
            }
            boolean enabled = s.bool("enabled", true);
            String tagPattern = s.string("releaseTagPattern", null);
            if (tagPattern != null && !tagPattern.contains("{version}")) {
                s.problem("releaseTagPattern", "must contain the {version} placeholder, for example \"v{version}\"");
            }
            s.rejectUnknownKeys();
            result.add(new RepositoryConfig(name, path, url, baseBranch, modules, enabled, tagPattern));
        }
        if (root.has("repositories") && result.isEmpty()) {
            root.problem("repositories", "must list at least one repository");
        }
        return result;
    }

    private MavenConfig maven(Section s) {
        String executable = s.string("executable", "auto");
        Path settings = path(s, "settings", configFile.getParent(), null);
        List<String> profiles = s.stringList("profiles", List.of());
        boolean offline = s.bool("offline", false);
        String threads = s.string("threads", "1C");
        List<String> extraArgs = s.stringList("extraArgs", List.of());
        String compileGoal = s.string("compileGoal", "test-compile");
        if (!COMPILE_GOALS.contains(compileGoal)) {
            s.problem("compileGoal", "must be one of " + String.join(", ", COMPILE_GOALS.stream().sorted().toList()));
        }
        s.rejectUnknownKeys();
        return new MavenConfig(executable, settings, profiles, offline, threads, extraArgs, compileGoal);
    }

    private NexusConfig nexus(Section s) {
        String repositoryId = s.string("repositoryId", null);
        String url = s.string("url", null);
        if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
            s.problem("url", "must start with http:// or https://");
        }
        s.rejectUnknownKeys();
        return new NexusConfig(repositoryId, url);
    }

    private Map<String, CheckSetting> checks(Section s) {
        Map<String, CheckSetting> result = KnownChecks.defaults();
        for (Map.Entry<String, JsonNode> entry : s.entries()) {
            String id = entry.getKey();
            if (!KnownChecks.isKnown(id)) {
                s.problem(id, "is not a known check (known: " + String.join(", ", KnownChecks.ids()) + ")");
                continue;
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            CheckSetting defaults = result.get(id);
            if (value.isValueNode()) {
                Severity severity = severity(s, id, value);
                if (severity != null) {
                    result.put(id, new CheckSetting(severity, defaults.options()));
                }
            } else {
                result.put(id, check(s.section(id), id, defaults));
            }
        }
        return result;
    }

    private CheckSetting check(Section s, String id, CheckSetting defaults) {
        Severity severity = defaults.severity();
        Map<String, String> options = new LinkedHashMap<>(defaults.options());
        for (Map.Entry<String, JsonNode> option : s.entries()) {
            String key = option.getKey();
            if (key.equals("severity")) {
                Severity parsed = severity(s, key, option.getValue());
                if (parsed != null) {
                    severity = parsed;
                }
            } else if (KnownChecks.options(id).contains(key)) {
                options.put(key, option.getValue().asText());
            } else {
                s.problem(key, "is not an option of " + id);
            }
        }
        return new CheckSetting(severity, options);
    }

    private static Severity severity(Section s, String key, JsonNode value) {
        // YAML 1.1 reads an unquoted "off" as the boolean false.
        if (value.isBoolean() && !value.booleanValue()) {
            return Severity.OFF;
        }
        String text = value.asText();
        Severity severity = Severity.parse(text);
        if (severity == null) {
            s.problem(key, "must be one of off, info, warn, error (was '" + text + "')");
        }
        return severity;
    }

    private RebaseConfig rebase(Section s) {
        boolean requireCleanTree = s.bool("requireCleanTree", true);
        boolean autostash = s.bool("autostash", false);
        boolean backupRefs = s.bool("backupRefs", true);
        PushPolicy push = PushPolicy.ASK;
        String pushText = s.string("push", null);
        if (pushText != null) {
            try {
                push = PushPolicy.valueOf(pushText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                s.problem("push", "must be one of never, ask, always");
            }
        }
        List<String> protectedBranches = s.stringList("protectedBranches", DEFAULT_PROTECTED_BRANCHES);
        s.rejectUnknownKeys();
        return new RebaseConfig(requireCleanTree, autostash, backupRefs, push, protectedBranches);
    }

    private BuildConfig build(Section s) {
        int parallelRepos = s.integer("parallelRepos", 0, 0);
        String memory = s.string("memoryPerBuild", "1g");
        if (!MEMORY.matcher(memory).matches()) {
            s.problem("memoryPerBuild", "must look like 512m or 1g");
        }
        int timeout = s.integer("timeoutMinutes", 15, 1);
        boolean cache = s.bool("cache", true);
        s.rejectUnknownKeys();
        return new BuildConfig(parallelRepos, memory, timeout, cache);
    }

    /** Reads a path, expanding {@code ~} and resolving it against {@code base}. */
    private static Path path(Section s, String key, Path base, Path defaultValue) {
        String text = s.string(key, null);
        if (text == null) {
            return defaultValue;
        }
        try {
            return base.resolve(Platform.expandHome(text)).normalize();
        } catch (InvalidPathException e) {
            s.problem(key, "is not a valid path: " + e.getMessage());
            return defaultValue;
        }
    }
}
