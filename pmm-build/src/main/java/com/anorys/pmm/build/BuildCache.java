package com.anorys.pmm.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per repository, the fingerprint of its last successful build, in
 * {@code .pmm/build-cache.json} under the workspace root. Failures are never cached. A corrupt or
 * unreadable file is treated as empty: the cache only ever saves time.
 */
public final class BuildCache {

    public record Entry(String fingerprint, Instant builtAt, long durationMillis) {
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private BuildCache(Path file) {
        this.file = file;
    }

    public static BuildCache load(Path file) {
        BuildCache cache = new BuildCache(file);
        if (Files.isRegularFile(file)) {
            try {
                JsonNode root = JSON.readTree(file.toFile());
                root.path("repositories").fields().forEachRemaining(e -> {
                    JsonNode node = e.getValue();
                    if (node.hasNonNull("fingerprint")) {
                        cache.entries.put(e.getKey(), new Entry(node.get("fingerprint").asText(),
                                Instant.parse(node.path("builtAt").asText(Instant.EPOCH.toString())),
                                node.path("durationMillis").asLong()));
                    }
                });
            } catch (IOException | RuntimeException e) {
                cache.entries.clear();
            }
        }
        return cache;
    }

    public Optional<Entry> get(String repository) {
        return Optional.ofNullable(entries.get(repository));
    }

    public void put(String repository, Entry entry) {
        entries.put(repository, entry);
    }

    public void remove(String repository) {
        entries.remove(repository);
    }

    public void save() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ObjectNode repositories = root.putObject("repositories");
        entries.keySet().stream().sorted().forEach(name -> {
            Entry entry = entries.get(name);
            ObjectNode node = repositories.putObject(name);
            node.put("fingerprint", entry.fingerprint());
            node.put("builtAt", entry.builtAt().toString());
            node.put("durationMillis", entry.durationMillis());
        });
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writeValue(temp.toFile(), root);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
