package com.anorys.pmm.build;

import com.anorys.pmm.git.GitClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Identifies the exact sources a build would see: the committed tree, the uncommitted changes, the
 * untracked files and the Maven command line. Two equal fingerprints mean the build would give the
 * same result, as long as the dependencies are releases (which never change in Nexus).
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(GitClient git, List<String> command) {
        MessageDigest digest = sha256();
        update(digest, "tree", git.headTree());
        update(digest, "diff", git.diffHead());
        for (String file : git.untrackedFiles()) {
            update(digest, "untracked", file);
            Path path = git.repository().resolve(file);
            try {
                if (Files.isRegularFile(path)) {
                    digest.update(Files.readAllBytes(path));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        update(digest, "command", String.join("\u0000", command));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String label, String value) {
        digest.update((label + "\u0000" + value + "\u0000").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
