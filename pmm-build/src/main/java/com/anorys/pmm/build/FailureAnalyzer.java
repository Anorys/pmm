package com.anorys.pmm.build;

import com.anorys.pmm.build.Failure.Kind;
import com.anorys.pmm.build.Failure.MissingArtifact;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Maven's output to say why a build failed. Maven's English messages are stable across 3.x:
 * <ul>
 *   <li>{@code Could not find artifact g:a:jar:1.0 in nexus (...)};</li>
 *   <li>{@code The following artifacts could not be resolved: g:a:jar:1.0 (absent)} (Maven 3.9);</li>
 *   <li>{@code dependency: g:a:jar:1.0 (compile)}, then the reason on the next line (Maven 3.9.10+);</li>
 *   <li>{@code g:a:jar:1.0 was not found in ... during a previous attempt} (failure cached locally);</li>
 *   <li>{@code the artifact g:a:jar:1.0 has not been downloaded from it before} (offline);</li>
 *   <li>{@code Plugin g:a:1.0 or one of its dependencies could not be resolved};</li>
 *   <li>{@code [ERROR] /path/Foo.java:[12,5] cannot find symbol} for compilation errors;</li>
 *   <li>{@code Failed to execute goal ... on project billing-api:} for the failing module.</li>
 * </ul>
 */
public final class FailureAnalyzer {

    private static final Pattern NOT_FOUND = Pattern.compile("Could not find artifact (\\S+)");
    private static final Pattern ABSENT = Pattern.compile("([\\w.\\-]+:[\\w.\\-]+(?::[\\w.\\-]+)*) \\(absent\\)");
    private static final Pattern DEPENDENCY = Pattern.compile("^\\[ERROR] dependency: (\\S+) \\(");
    private static final Pattern CACHED = Pattern.compile("(\\S+:\\S+) was not found in");
    private static final Pattern OFFLINE = Pattern.compile("the artifact (\\S+) has not been downloaded");
    private static final Pattern PLUGIN = Pattern.compile("Plugin (\\S+) or one of its dependencies could not be resolved");
    private static final Pattern PROJECT = Pattern.compile("Failed to execute goal (?:.* )?on project ([\\w.\\-]+)");
    private static final Pattern COMPILE_ERROR = Pattern.compile("^\\[ERROR] (?:.*[/\\\\])?([^/\\\\]+\\.(?:java|kt|groovy|scala)):?(\\[\\d+,\\d+])? ?(.*)$");
    private static final Pattern ERROR = Pattern.compile("^\\[ERROR] (\\S.*)$");

    private final Workspace workspace;

    public FailureAnalyzer(Workspace workspace) {
        this.workspace = workspace;
    }

    public Failure analyze(List<String> lines, boolean timedOut) {
        String module = null;
        Set<String> coordinates = new LinkedHashSet<>();
        String compileError = null;
        String firstError = null;
        boolean compilationFailure = false;
        for (String raw : lines) {
            String line = raw.strip();
            Matcher project = PROJECT.matcher(line);
            if (module == null && project.find()) {
                module = project.group(1);
            }
            collect(NOT_FOUND, line, coordinates);
            collect(ABSENT, line, coordinates);
            collect(DEPENDENCY, line, coordinates);
            collect(CACHED, line, coordinates);
            collect(OFFLINE, line, coordinates);
            collect(PLUGIN, line, coordinates);
            if (line.contains("COMPILATION ERROR") || line.contains("Compilation failure")) {
                compilationFailure = true;
            }
            Matcher compile = COMPILE_ERROR.matcher(line);
            if (compileError == null && compile.matches()) {
                compileError = compile.group(1) + (compile.group(2) == null ? "" : ":" + compile.group(2))
                        + (compile.group(3).isBlank() ? "" : " " + compile.group(3).strip());
            }
            Matcher error = ERROR.matcher(line);
            if (firstError == null && error.matches() && !line.contains("-> [Help")) {
                firstError = error.group(1);
            }
        }
        if (timedOut) {
            return new Failure(Kind.TIMEOUT, module, List.of(), null);
        }
        if (!coordinates.isEmpty()) {
            List<MissingArtifact> missing = new ArrayList<>();
            for (String coordinate : coordinates) {
                MissingArtifact artifact = parse(coordinate);
                if (artifact != null && !missing.contains(artifact)) {
                    missing.add(artifact);
                }
            }
            if (!missing.isEmpty()) {
                return new Failure(Kind.MISSING_ARTIFACT, module, missing, firstError);
            }
        }
        if (compilationFailure || compileError != null) {
            return new Failure(Kind.COMPILATION, module, List.of(), compileError != null ? compileError : firstError);
        }
        return new Failure(Kind.OTHER, module, List.of(), firstError);
    }

    private static void collect(Pattern pattern, String line, Set<String> into) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /** {@code g:a:v}, {@code g:a:type:v} or {@code g:a:type:classifier:v}: the version is always last. */
    MissingArtifact parse(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            return null;
        }
        ArtifactKey key = new ArtifactKey(parts[0], parts[1]);
        String producer = workspace.producer(key).map(ModuleInfo::repository).orElse(null);
        return new MissingArtifact(key, parts[parts.length - 1], producer);
    }
}
