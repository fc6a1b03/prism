package com.acme.prism.core.json;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;
import java.util.Locale;

/**
 * JSON Mock 数据生成器：按样例 JSON 的结构与类型生成随机测试数据。
 *
 * <p>字符串字段按 key 名启发式生成（name/email/phone/date/id 等），
 * 数字/布尔/数组/对象保持结构与类型，值随机。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonMockGenerator implements JsonOperation {

    /**
     * 名字候选池
     */
    private static final List<String> NAMES = List.of("张三", "李四", "王五", "赵六", "陈七");
    /**
     * 随机词候选池
     */
    private static final List<String> WORDS = List.of("alpha", "beta", "gamma", "delta", "omega", "prism");

    /**
     * JSON 操作契约：按样例生成 Mock 数据。
     *
     * @param json 样例 JSON
     * @return Mock 数据 JSON；输入非法时原样返回
     */
    @Override
    public String process(final String json) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            final Object mock = generate(JSON.parse(json), "");
            return JSON.toJSONString(mock, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (final Exception ignored) {
            return json;
        }
    }

    /**
     * 递归生成 Mock 值。
     *
     * @param value 样例值
     * @param key   字段名（用于字符串启发式）
     * @return Mock 值
     */
    private static Object generate(final Object value, final String key) {
        if (value instanceof JSONObject obj) {
            final JSONObject mock = new JSONObject(obj.size());
            for (final String k : obj.keySet()) {
                mock.put(k, generate(obj.get(k), k));
            }
            return mock;
        }
        if (value instanceof JSONArray arr) {
            final JSONArray mock = new JSONArray(arr.size());
            for (final Object item : arr) {
                mock.add(generate(item, key));
            }
            return mock;
        }
        if (value instanceof String) {
            return randomString(key);
        }
        if (value instanceof Boolean) {
            return RandomUtil.randomBoolean();
        }
        if (value instanceof Integer) {
            return RandomUtil.randomInt(1, 1000);
        }
        if (value instanceof Long) {
            return RandomUtil.randomLong(1000, 1_000_000);
        }
        if (value instanceof Number) {
            return RandomUtil.randomDouble(1, 1000);
        }
        // null 保持 null
        return null;
    }

    /**
     * 按字段名启发式生成随机字符串。
     *
     * @param key 字段名
     * @return 随机字符串
     */
    private static String randomString(final String key) {
        final String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("name")) {
            return RandomUtil.randomEle(NAMES);
        }
        if (k.contains("email") || k.contains("mail")) {
            return "user%s@example.com".formatted(RandomUtil.randomNumbers(6));
        }
        if (k.contains("phone") || k.contains("mobile") || k.contains("tel")) {
            return "138%s".formatted(RandomUtil.randomNumbers(8));
        }
        if (k.contains("date") || k.contains("time")) {
            return "2026-%02d-%02d".formatted(RandomUtil.randomInt(1, 12), RandomUtil.randomInt(1, 28));
        }
        if (k.contains("id") || k.contains("no") || k.contains("num") || k.contains("code")) {
            return RandomUtil.randomNumbers(8);
        }
        if (k.contains("url")) {
            return "https://example.com/%s".formatted(RandomUtil.randomEle(WORDS));
        }
        return RandomUtil.randomEle(WORDS);
    }
}
