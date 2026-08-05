package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/**
 * JSON 扁平化器：将嵌套 JSON 展开为点号路径键值对（数组用 {@code [i]} 索引）。
 *
 * <p>示例：{@code {"a":{"b":1},"c":[1,2]}} → {@code {"a.b":1,"c[0]":1,"c[1]":2}}。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonFlattener implements JsonOperation {

    /**
     * 默认路径分隔符
     */
    private static final String DEFAULT_SEPARATOR = ".";

    /**
     * JSON 操作契约：使用默认点号分隔符展开。
     *
     * @param json 输入
     * @return 展开后的扁平 JSON；输入非法时原样返回
     */
    @Override
    public String process(final String json) {
        return flatten(json, DEFAULT_SEPARATOR);
    }

    /**
     * 扁平化 JSON。
     *
     * @param json      输入
     * @param separator 路径分隔符
     * @return 展开后的扁平 JSON；输入非法时原样返回
     */
    public static String flatten(final String json, final String separator) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            final JSONObject result = new JSONObject();
            flattenValue(JSON.parse(json), "", separator, result);
            return JSON.toJSONString(result, JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (final Exception ignored) {
            return json;
        }
    }

    /**
     * 递归展开值。
     *
     * @param value     值
     * @param prefix    当前路径前缀
     * @param separator 路径分隔符
     * @param result    结果容器
     */
    private static void flattenValue(final Object value, final String prefix, final String separator, final JSONObject result) {
        if (value instanceof final JSONObject obj) {
            for (final String key : obj.keySet()) {
                final String path = prefix.isEmpty() ? key : prefix + separator + key;
                flattenValue(obj.get(key), path, separator, result);
            }
        } else if (value instanceof final JSONArray arr) {
            for (int index = 0; index < arr.size(); index++) {
                flattenValue(arr.get(index), "%s[%d]".formatted(prefix, index), separator, result);
            }
        } else {
            result.put(prefix, value);
        }
    }
}
