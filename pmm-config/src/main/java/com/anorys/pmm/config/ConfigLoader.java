package com.anorys.pmm.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@code pmm.yaml} and, when present, the personal {@code pmm.local.yaml} next to it.
 * Local values override shared ones: mappings are merged key by key, repositories are merged
 * by {@code name}, and any other list is replaced.
 */
public final class ConfigLoader {

    public static final String CONFIG_FILE = "pmm.yaml";
    public static final String LOCAL_CONFIG_FILE = "pmm.local.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /** Looks for {@code pmm.yaml} in {@code start} and its parent directories. */
    public static Optional<Path> locate(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(CONFIG_FILE);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static PmmConfig load(Path configFile) {
        JsonNode tree = read(configFile);
        Path local = configFile.toAbsolutePath().resolveSibling(LOCAL_CONFIG_FILE);
        if (Files.isRegularFile(local)) {
            tree = merge(tree, read(local));
        }
        return new ConfigParser(configFile).parse(tree);
    }

    /** Parses YAML text as if it were the content of {@code configFile}. Used by tests and {@code pmm init}. */
    public static PmmConfig parse(String yaml, Path configFile) {
        try {
            return new ConfigParser(configFile).parse(nonEmpty(YAML.readTree(yaml), configFile));
        } catch (JsonProcessingException e) {
            throw syntaxError(configFile, e);
        }
    }

    private static JsonNode read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(file, List.of("file not found"));
        }
        try {
            return nonEmpty(YAML.readTree(file.toFile()), file);
        } catch (JsonProcessingException e) {
            throw syntaxError(file, e);
        } catch (IOException e) {
            throw new ConfigException(file, "cannot read file: " + e.getMessage(), e);
        }
    }

    private static JsonNode nonEmpty(JsonNode tree, Path file) {
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            throw new ConfigException(file, List.of("file is empty"));
        }
        return tree;
    }

    private static ConfigException syntaxError(Path file, JsonProcessingException e) {
        JsonLocation location = e.getLocation();
        String where = location == null ? "" : "line " + location.getLineNr() + ", column " + location.getColumnNr() + ": ";
        return new ConfigException(file, "YAML syntax error at " + where + e.getOriginalMessage(), e);
    }

    static JsonNode merge(JsonNode base, JsonNode override) {
        if (base instanceof ObjectNode baseObject && override instanceof ObjectNode overrideObject) {
            ObjectNode result = baseObject.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = overrideObject.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode existing = result.get(field.getKey());
                result.set(field.getKey(), existing == null ? field.getValue() : merge(existing, field.getValue()));
            }
            return result;
        }
        if (base instanceof ArrayNode baseArray && override instanceof ArrayNode overrideArray
                && isNamedList(baseArray) && isNamedList(overrideArray)) {
            Map<String, JsonNode> byName = new LinkedHashMap<>();
            baseArray.forEach(item -> byName.put(item.get("name").asText(), item));
            overrideArray.forEach(item -> byName.merge(item.get("name").asText(), item, ConfigLoader::merge));
            ArrayNode result = YAML.createArrayNode();
            byName.values().forEach(result::add);
            return result;
        }
        return override;
    }

    private static boolean isNamedList(ArrayNode array) {
        for (JsonNode item : array) {
            if (!item.isObject() || !item.hasNonNull("name")) {
                return false;
            }
        }
        return true;
    }
}
