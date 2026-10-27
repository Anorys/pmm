package com.anorys.pmm.core.workspace;

/** Maven coordinates without version: {@code groupId:artifactId}. */
public record ArtifactKey(String groupId, String artifactId) implements Comparable<ArtifactKey> {

    public static ArtifactKey parse(String coordinates) {
        int colon = coordinates.indexOf(':');
        if (colon <= 0 || colon == coordinates.length() - 1) {
            throw new IllegalArgumentException("Expected groupId:artifactId, got " + coordinates);
        }
        return new ArtifactKey(coordinates.substring(0, colon), coordinates.substring(colon + 1));
    }

    /**
     * Matches a {@code groupId} pattern from the configuration: an exact group, or a prefix
     * ending with {@code .*} that also matches the group itself ({@code com.anorys.*} matches
     * {@code com.anorys} and {@code com.anorys.billing}).
     */
    public boolean groupMatches(String pattern) {
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return groupId.equals(prefix) || groupId.startsWith(prefix + ".");
        }
        return groupId.equals(pattern);
    }

    @Override
    public int compareTo(ArtifactKey other) {
        int byGroup = groupId.compareTo(other.groupId);
        return byGroup != 0 ? byGroup : artifactId.compareTo(other.artifactId);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
