package com.anorys.pmm.core;

import java.nio.file.Path;
import java.util.Locale;

/** Operating-system facts the tool depends on. */
public final class Platform {

    private Platform() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    /** Expands a leading {@code ~} to the user's home directory, for both {@code /} and {@code \} separators. */
    public static String expandHome(String path) {
        if (path.equals("~")) {
            return userHome().toString();
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return userHome().resolve(path.substring(2)).toString();
        }
        return path;
    }
}
