package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;
import java.util.Objects;

/**
 * JSON Schema 生成器：从样例 JSON 推断 JSON Schema（Draft 7），
 * 类型推断覆盖 object / array / string / number / integer / boolean / null，
 * 样例中存在的键全部标记为 required。
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonSchemaGenerator implements JsonOperation {

    /**
     * JSON Schema Draft 7 标识
     */
    private static final String DRAFT_7 = "http://json-schema.org/draft-07/schema#";

    /**
     * JSON 操作契约：推断 Draft 7 Schema。
     *
     * @param json 输入
     * @return JSON Schema 文本；输入非法时原样返回
     */
    @Override
    public String process(final String json) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            final JSONObject schema = new JSONObject();
            schema.put("$schema", DRAFT_7);
            schema.putAll(buildSchema(JSON.parse(json)));
            return JSON.toJSONString(schema, JSONWriter.Feature.PrettyFormat).trim();
        } catch (final Exception ignored) {
            return json;
        }
    }

    /**
     * 递归构建值对应的 Schema 片段。
     *
     * @param value 值
     * @return Schema 片段
     */
    private static JSONObject buildSchema(final Object value) {
        final JSONObject schema = new JSONObject();
        if (value instanceof final JSONObject obj) {
            schema.put("type", "object");
            final JSONObject properties = new JSONObject(obj.size());
            final JSONArray required = new JSONArray(obj.size());
            for (final String key : obj.keySet()) {
                properties.put(key, buildSchema(obj.get(key)));
                required.add(key);
            }
            schema.put("properties", properties);
            schema.put("required", required);
        } else if (value instanceof final JSONArray arr) {
            schema.put("type", "array");
            if (!arr.isEmpty()) {
                schema.put("items", mergeSchemas(arr.stream().map(JsonSchemaGenerator::buildSchema).toList()));
            }
        } else if (value instanceof String) {
            schema.put("type", "string");
        } else if (value instanceof Boolean) {
            schema.put("type", "boolean");
        } else if (value instanceof Integer || value instanceof Long) {
            schema.put("type", "integer");
        } else if (value instanceof Number) {
            schema.put("type", "number");
        } else if (Objects.isNull(value)) {
            schema.put("type", "null");
        }
        return schema;
    }

    /**
     * 合并多个元素 Schema 的类型（多样类型输出 type 数组）。
     *
     * @param schemas 元素 Schema 列表
     * @return 合并后的 Schema
     */
    private static JSONObject mergeSchemas(final List<JSONObject> schemas) {
        final JSONObject merged = new JSONObject();
        final JSONArray types = new JSONArray();
        for (final JSONObject schema : schemas) {
            final Object type = schema.get("type");
            if (Objects.nonNull(type) && !types.contains(type)) {
                types.add(type);
            }
        }
        merged.put("type", types.size() == 1 ? types.get(0) : types);
        return merged;
    }
}
