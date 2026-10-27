package com.anorys.pmm.maven;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maven version helpers: ordering as Maven does it, and how far apart two releases are. */
public final class Versions {

    /** How far a used version is behind the latest release, by the first numeric part that differs. */
    public enum Lag {
        NONE, PATCH, MINOR, MAJOR;

        public static Lag parse(String text) {
            return Lag.valueOf(text.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final Pattern PRE_RELEASE =
            Pattern.compile("(?i)^(?:.*[-.])?(alpha|beta|rc|cr|m|milestone|preview|ea)[-.]?\\d*(?:[-.].*)?$");
    private static final Pattern NUMBERS = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private Versions() {
    }

    public static ComparableVersion comparable(String version) {
        return new ComparableVersion(version);
    }

    public static int compare(String a, String b) {
        return comparable(a).compareTo(comparable(b));
    }

    public static boolean isSnapshot(String version) {
        return version.endsWith("-SNAPSHOT");
    }

    /** {@code [1.0,2.0)} and similar; such versions are not checked. */
    public static boolean isRange(String version) {
        return version.startsWith("[") || version.startsWith("(");
    }

    /** Alpha, beta, release candidates and milestones, which are not proposed as upgrades. */
    public static boolean isPreRelease(String version) {
        String qualifier = version.replaceFirst("^[\\d.]+", "");
        return !qualifier.isEmpty() && PRE_RELEASE.matcher(qualifier).matches();
    }

    /** The lag of {@code used} behind {@code latest}; {@link Lag#NONE} when it is not older. */
    public static Lag lag(String used, String latest) {
        if (compare(used, latest) >= 0) {
            return Lag.NONE;
        }
        int[] a = numbers(used);
        int[] b = numbers(latest);
        if (a[0] != b[0]) {
            return Lag.MAJOR;
        }
        if (a[1] != b[1]) {
            return Lag.MINOR;
        }
        return Lag.PATCH;
    }

    private static int[] numbers(String version) {
        Matcher matcher = NUMBERS.matcher(version);
        int[] numbers = new int[3];
        if (matcher.find()) {
            for (int i = 0; i < 3; i++) {
                String group = matcher.group(i + 1);
                numbers[i] = group == null ? 0 : Integer.parseInt(group);
            }
        }
        return numbers;
    }
}
