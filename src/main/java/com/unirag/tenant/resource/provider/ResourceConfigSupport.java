package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.TenantResourceConfig;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 各 Provider 解析 JSONB 配置体的公共工具。
 * 校验失败一律抛 {@link IllegalArgumentException}，message 只含字段名不含值（FR-008）。
 */
final class ResourceConfigSupport {

    private ResourceConfigSupport() {
    }

    static JsonNode requireObject(TenantResourceConfig config) {
        JsonNode node = config.getConfig();
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("config must be a JSON object");
        }
        return node;
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing required config field: " + field);
        }
        return value.asText();
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("missing required config field: " + field);
        }
        return value.asInt();
    }

    static void optionalText(JsonNode node, String field, Consumer<String> setter) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            setter.accept(value.asText());
        }
    }

    static void optionalInt(JsonNode node, String field, Consumer<Integer> setter) {
        JsonNode value = node.get(field);
        if (value != null && value.isNumber()) {
            setter.accept(value.asInt());
        }
    }

    static void optionalDouble(JsonNode node, String field, Consumer<Double> setter) {
        JsonNode value = node.get(field);
        if (value != null && value.isNumber()) {
            setter.accept(value.asDouble());
        }
    }

    static void optionalBoolean(JsonNode node, String field, Consumer<Boolean> setter) {
        JsonNode value = node.get(field);
        if (value != null && value.isBoolean()) {
            setter.accept(value.asBoolean());
        }
    }

    static void optionalDuration(JsonNode node, String field, Consumer<Duration> setter) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            setter.accept(Duration.parse(value.asText()));
        }
    }
}
