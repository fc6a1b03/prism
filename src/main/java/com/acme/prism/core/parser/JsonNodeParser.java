package com.acme.prism.core.parser;

import cn.hutool.core.lang.Opt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSON节点解析器
 *
 * @author 拒绝者
 * @date 2025-01-28
 */
public class JsonNodeParser {
    /**
     * 解析
     * <p>
     * 单次解析策略：直接尝试解析，失败时按原始文本处理（避免 isValid 与 parse 双重解析）
     *
     * @param key  钥匙
     * @param json json
     * @return {@link JsonNode }
     */
    public static JsonNode parse(final String key, final String json) {
        final Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (final Exception ignored) {
            // 非 JSON 文本时按原始字符串构建叶子节点
            return parseNode(key, json);
        }
        return parseNode(key, parsed);
    }

    /**
     * 解析节点
     *
     * @param key   钥匙
     * @param value 价值
     * @return {@link JsonNode }
     */
    private static JsonNode parseNode(final String key, final Object value) {
        return switch (value) {
            case final JSONObject obj -> new JsonNode(key, obj, parseObjectChildren(obj));
            case final JSONArray arr -> new JsonNode(key, arr, parseArrayChildren(arr));
            case null -> new JsonNode(key, null, Collections.emptyList());
            default -> new JsonNode(
                    key,
                    value,
                    Collections.emptyList()
            );
        };
    }

    private static List<JsonNode> parseObjectChildren(final JSONObject obj) {
        final List<JsonNode> children = new ArrayList<>(obj.size());
        for (final String childKey : obj.keySet()) {
            children.add(parseNode(childKey, obj.get(childKey)));
        }
        return children;
    }

    private static List<JsonNode> parseArrayChildren(final JSONArray arr) {
        final List<JsonNode> children = new ArrayList<>(arr.size());
        for (int index = 0; index < arr.size(); index++) {
            children.add(parseNode("[%d]".formatted(index), arr.get(index)));
        }
        return children;
    }

    /**
     * JSON节点
     *
     * @author 拒绝者
     * @date 2025-01-28
     */
    public record JsonNode(String key, Object value, List<JsonNode> children) {
        @Override
        public @NotNull String toString() {
            return Opt.ofNullable(this.value)
                    .map(_ -> "{\"%s\": %s}".formatted(this.key, this.value()))
                    .orElse("");
        }

        /**
         * 值（JSON 字符串形式，供树渲染展示）
         *
         * @return {@link String }
         */
        public Object value() {
            return Opt.ofNullable(this.value)
                    .map(JSON::toJSONString)
                    .orElse("");
        }

        /**
         * 原始值（未序列化，供结构分析复用，避免二次解析）
         *
         * @return 原始对象值；可能为 {@code null}
         */
        public Object rawValue() {
            return this.value;
        }

        /**
         * 类型
         *
         * @return {@link String }
         */
        public String type() {
            return switch (this.value) {
                case null -> "";
                case final JSONObject ignored -> "Object";
                case final JSONArray ignored -> "Array";
                default -> this.value.getClass().getSimpleName();
            };
        }
    }
}
