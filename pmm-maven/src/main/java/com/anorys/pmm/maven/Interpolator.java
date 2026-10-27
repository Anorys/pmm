package com.anorys.pmm.maven;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${name}} with property values, repeatedly so that properties may refer to
 * other properties. Unknown properties are left as they are, so callers can detect them.
 */
final class Interpolator {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_DEPTH = 10;

    private final Map<String, String> properties;

    Interpolator(Map<String, String> properties) {
        this.properties = properties;
    }

    String apply(String value) {
        if (value == null) {
            return null;
        }
        String current = value.trim();
        for (int depth = 0; depth < MAX_DEPTH && current.contains("${"); depth++) {
            Matcher matcher = EXPRESSION.matcher(current);
            StringBuilder result = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement != null) {
                    changed = true;
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement != null ? replacement.trim() : matcher.group()));
            }
            matcher.appendTail(result);
            current = result.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }
}
