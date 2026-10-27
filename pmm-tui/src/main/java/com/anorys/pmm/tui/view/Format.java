package com.anorys.pmm.tui.view;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Short human texts for durations and moments: "1m12", "3 min ago". */
public final class Format {

    private Format() {
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return seconds >= 60 ? seconds / 60 + "m" + String.format(Locale.ROOT, "%02d", seconds % 60) : seconds + "s";
    }

    public static String ago(Instant then, Instant now) {
        if (then == null) {
            return "never";
        }
        long seconds = Math.max(0, Duration.between(then, now).toSeconds());
        if (seconds < 10) {
            return "just now";
        }
        if (seconds < 60) {
            return seconds + " s ago";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min ago";
        }
        if (seconds < 86400) {
            return seconds / 3600 + " h ago";
        }
        return seconds / 86400 + " d ago";
    }

    public static String age(Instant since, Instant now) {
        long days = Duration.between(since, now).toDays();
        if (days >= 1) {
            return days + (days == 1 ? " day" : " days");
        }
        long hours = Duration.between(since, now).toHours();
        return hours + " h";
    }
}
