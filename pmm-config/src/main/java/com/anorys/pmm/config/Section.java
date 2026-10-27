package com.anorys.pmm.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, error-collecting access to one YAML mapping. Every key read is remembered, so
 * {@link #rejectUnknownKeys()} can report typos such as {@code baseBrach}.
 */
final class Section {

    private final JsonNode node;
    private final String path;
    private final List<String> problems;
    private final Set<String> readKeys = new LinkedHashSet<>();

    Section(JsonNode node, String path, List<String> problems) {
        this.node = node == null ? MissingNode.getInstance() : node;
        this.path = path;
        this.problems = problems;
        if (!this.node.isMissingNode() && !this.node.isNull() && !this.node.isObject()) {
            problem(null, "must be a mapping");
        }
    }

    String path() {
        return path;
    }

    boolean has(String key) {
        JsonNode value = node.get(key);
        return value != null && !value.isNull();
    }

    JsonNode raw(String key) {
        readKeys.add(key);
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value;
    }

    String string(String key, String defaultValue) {
        JsonNode value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isValueNode()) {
            problem(key, "must be a single value");
            return defaultValue;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    String requiredString(String key) {
        String value = string(key, null);
        if (value == null) {
            problem(key, "is required");
        }
        return value;
    }

    boolean bool(String key, boolean defaultValue) {
        JsonNode value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            problem(key, "must be true or false");
            return defaultValue;
        }
        return value.booleanValue();
    }

    int integer(String key, int defaultValue, int min) {
        JsonNode value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            problem(key, "must be a whole number");
            return defaultValue;
        }
        if (value.intValue() < min) {
            problem(key, "must be at least " + min);
            return defaultValue;
        }
        return value.intValue();
    }

    /** Accepts a list, or a single value meaning a one-element list. */
    List<String> stringList(String key, List<String> defaultValue) {
        JsonNode value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        if (value.isValueNode()) {
            return List.of(value.asText().trim());
        }
        if (!value.isArray()) {
            problem(key, "must be a list");
            return defaultValue;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            if (!item.isValueNode() || item.isNull()) {
                problem(key + "[" + i + "]", "must be a single value");
            } else {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    Section section(String key) {
        return new Section(raw(key), child(key), problems);
    }

    List<Section> sectionList(String key) {
        JsonNode value = raw(key);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            problem(key, "must be a list");
            return List.of();
        }
        List<Section> result = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            result.add(new Section(value.get(i), child(key) + "[" + i + "]", problems));
        }
        return result;
    }

    /** All entries of this mapping, marking them as read. */
    List<Map.Entry<String, JsonNode>> entries() {
        List<Map.Entry<String, JsonNode>> all = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                readKeys.add(e.getKey());
                all.add(e);
            });
        }
        return all;
    }

    void rejectUnknownKeys() {
        if (!node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!readKeys.contains(name)) {
                problem(name, "is not a known setting");
            }
        });
    }

    void problem(String key, String message) {
        String where = key == null ? path : child(key);
        problems.add((where.isEmpty() ? "(root)" : where) + " " + message);
    }

    String child(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
