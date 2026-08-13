package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JSON 键名风格转换器：递归将对象键转换为目标命名风格（camelCase / snake_case /
 * PascalCase / kebab-case），数组元素与值内容保持不变（null 值字段保留）。
 *
 * <p>键分词规则：分隔符（下划线/连字符等非字母数字字符）、大小写边界
 * （{@code userID → user | ID}）、字母数字边界（{@code user2name → user | 2 | name}）
 * 均作为分词点；连续大写缩略词按尾字母回退处理（{@code HTTPServer → HTTP | Server}）。
 * 转换后键名冲突（如 {@code user_id} 与 {@code userId} 同转 {@code userId}）时，
 * 后者覆盖前者——JSON 对象无法承载重复键，属转换语义固有限制。</p>
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
public final class JsonKeyConverter implements JsonOperation {

    /**
     * 键名命名风格。
     */
    public enum KeyCase {
        /**
         * 小驼峰：userName
         */
        CAMEL("json.key.case.camel"),
        /**
         * 下划线：user_name
         */
        SNAKE("json.key.case.snake"),
        /**
         * 大驼峰：UserName
         */
        PASCAL("json.key.case.pascal"),
        /**
         * 连字符：user-name
         */
        KEBAB("json.key.case.kebab");

        /**
         * 下划线分隔符
         */
        private static final String SEPARATOR_UNDERSCORE = "_";
        /**
         * 连字符分隔符
         */
        private static final String SEPARATOR_HYPHEN = "-";
        /**
         * 风格 i18n 键
         */
        private final String i18nKey;

        /**
         * 构造命名风格。
         *
         * @param i18nKey i18n 键
         */
        KeyCase(final String i18nKey) {
            this.i18nKey = i18nKey;
        }

        /**
         * 获取 i18n 键。
         *
         * @return {@link String }
         */
        public String i18nKey() {
            return this.i18nKey;
        }

        /**
         * 按目标风格拼接分词。
         *
         * @param tokens 分词（保留原大小写）
         * @return 拼接后的键名
         */
        String join(final List<String> tokens) {
            if (tokens.isEmpty()) {
                return "";
            }
            return switch (this) {
                case CAMEL -> joinCamel(tokens, Boolean.FALSE);
                case PASCAL -> joinCamel(tokens, Boolean.TRUE);
                case SNAKE -> joinSeparated(tokens, SEPARATOR_UNDERSCORE);
                case KEBAB -> joinSeparated(tokens, SEPARATOR_HYPHEN);
            };
        }

        /**
         * 驼峰系拼接（camel 首词小写，Pascal 全部首字母大写）。
         *
         * @param tokens    分词
         * @param capitalizeFirst 首个分词是否首字母大写
         * @return 拼接结果
         */
        private static String joinCamel(final List<String> tokens, final boolean capitalizeFirst) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                final String token = tokens.get(i).toLowerCase(Locale.ROOT);
                if (i == 0 && !capitalizeFirst) {
                    sb.append(token);
                } else {
                    sb.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
                }
            }
            return sb.toString();
        }

        /**
         * 分隔符系拼接（全小写 + 统一分隔符）。
         *
         * @param tokens    分词
         * @param separator 分隔符
         * @return 拼接结果
         */
        private static String joinSeparated(final List<String> tokens, final String separator) {
            return String.join(separator, tokens.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
        }
    }

    /**
     * 目标命名风格
     */
    private final KeyCase targetCase;

    /**
     * 构造键名转换器。
     *
     * @param targetCase 目标命名风格
     */
    public JsonKeyConverter(final KeyCase targetCase) {
        this.targetCase = targetCase;
    }

    /**
     * JSON 操作契约：递归转换对象键为目标风格。
     *
     * @param json 输入 JSON
     * @return 转换后的 JSON；输入为空或非法时原样返回
     */
    @Override
    public String process(final String json) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            final Object converted = convertKeys(JSON.parse(json));
            return JSON.toJSONString(converted, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (final Exception ignored) {
            return json;
        }
    }

    /**
     * 递归转换值中的对象键。
     *
     * @param value 值
     * @return 转换后的值
     */
    private Object convertKeys(final Object value) {
        if (value instanceof JSONObject obj) {
            // 重建对象保持插入序，键名转换后放回原位置
            final JSONObject converted = new JSONObject(obj.size());
            for (final String key : obj.keySet()) {
                converted.put(this.targetCase.join(tokenize(key)), this.convertKeys(obj.get(key)));
            }
            return converted;
        }
        if (value instanceof JSONArray arr) {
            final JSONArray converted = new JSONArray(arr.size());
            for (final Object item : arr) {
                converted.add(this.convertKeys(item));
            }
            return converted;
        }
        return value;
    }

    /**
     * 将键名切分为分词：分隔符、大小写边界、字母数字边界均为分词点。
     *
     * @param key 键名
     * @return 分词列表（保留原大小写）
     */
    private static List<String> tokenize(final String key) {
        final List<String> tokens = new ArrayList<>(4);
        final StringBuilder current = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                // 分隔符：结束当前分词
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (!current.isEmpty()) {
                final char prev = current.charAt(current.length() - 1);
                // 小写/数字 → 大写：userID → user | ID
                final boolean camelBoundary = Character.isUpperCase(c) && !Character.isUpperCase(prev);
                // 字母 ↔ 数字：user2name → user | 2 | name
                final boolean digitBoundary = Character.isDigit(c) != Character.isDigit(prev);
                // 连续大写缩略词结尾：HTTPServer → HTTP | Server（大写后跟小写时，缩略词整体弹出）
                final boolean acronymBoundary = Character.isUpperCase(prev) && Character.isUpperCase(c)
                        && i + 1 < key.length() && Character.isLowerCase(key.charAt(i + 1));
                if (camelBoundary || digitBoundary || acronymBoundary) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
