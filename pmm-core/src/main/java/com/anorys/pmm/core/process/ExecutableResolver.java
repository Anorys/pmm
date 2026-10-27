package com.anorys.pmm.core.process;

import com.anorys.pmm.core.Platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds executables on the PATH the way the shell would, including the Windows
 * {@code PATHEXT} rules ({@code mvn} resolves to {@code mvn.cmd}, {@code git} to {@code git.exe}).
 */
public final class ExecutableResolver {

    private static final List<String> DEFAULT_PATHEXT = List.of(".com", ".exe", ".bat", ".cmd");

    private final boolean windows;
    private final List<Path> searchPath;
    private final List<String> extensions;

    public ExecutableResolver(boolean windows, List<Path> searchPath, List<String> extensions) {
        this.windows = windows;
        this.searchPath = List.copyOf(searchPath);
        this.extensions = List.copyOf(extensions);
    }

    public static ExecutableResolver fromEnvironment() {
        boolean windows = Platform.isWindows();
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    try {
                        dirs.add(Path.of(entry.trim()));
                    } catch (InvalidPathException ignored) {
                        // Malformed PATH entries are skipped, like the shell does.
                    }
                }
            }
        }
        List<String> exts = DEFAULT_PATHEXT;
        String pathExt = System.getenv("PATHEXT");
        if (windows && pathExt != null && !pathExt.isBlank()) {
            exts = new ArrayList<>();
            for (String ext : pathExt.split(";")) {
                if (!ext.isBlank()) {
                    exts.add(ext.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ExecutableResolver(windows, dirs, exts);
    }

    /**
     * Resolves {@code name} to an executable file. A name containing a path separator is
     * treated as a path and only checked for existence.
     */
    public Optional<Path> resolve(String name) {
        if (name.contains("/") || name.contains("\\")) {
            Path direct = Path.of(name);
            return candidates(direct).stream().filter(this::isExecutable).findFirst();
        }
        for (Path dir : searchPath) {
            for (Path candidate : candidates(dir.resolve(name))) {
                if (isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private List<Path> candidates(Path base) {
        if (!windows || hasKnownExtension(base.getFileName().toString())) {
            return List.of(base);
        }
        List<Path> result = new ArrayList<>();
        for (String ext : extensions) {
            result.add(base.resolveSibling(base.getFileName() + ext));
        }
        return result;
    }

    private boolean hasKnownExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    private boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && (windows || Files.isExecutable(path));
    }

    /** True for files that must be started through {@code cmd.exe} on Windows. */
    static boolean isBatchFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }
}
