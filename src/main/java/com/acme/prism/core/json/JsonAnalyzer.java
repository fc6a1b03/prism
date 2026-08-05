package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.acme.prism.core.parser.JsonNodeParser;
import com.acme.prism.core.parser.JsonNodeParser.JsonNode;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JSON 结构分析器：统计键数、对象数、数组数、最大嵌套深度与文本大小。
 *
 * <p>独立于树构建运行，超大文本（超过树构建阈值）也能单独输出统计，
 * 避免"树不渲染时统计栏失效"的体验割裂。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonAnalyzer {

    /**
     * 统计结果。
     *
     * @param keys     对象键总数（含所有嵌套层级的对象键）
     * @param objects  对象节点数
     * @param arrays   数组节点数
     * @param maxDepth 最大嵌套深度（根节点深度为 1）
     * @param sizeBytes 文本 UTF-8 字节数
     */
    public record Stats(int keys, int objects, int arrays, int maxDepth, long sizeBytes) {
        /**
         * 是否为空统计（输入为空或解析失败）。
         *
         * @return boolean
         */
        public boolean isEmpty() {
            return this.keys == 0 && this.objects == 0 && this.arrays == 0;
        }
    }

    /**
     * 统计结果。
     */
    private static final Stats EMPTY_STATS = new Stats(0, 0, 0, 0, 0);

    /**
     * 统计 JSON 文本结构。
     *
     * @param json JSON 文本
     * @return 统计结果；空文本或非法 JSON 返回全零统计
     */
    public static Stats analyze(final String json) {
        if (StrUtil.isBlank(json)) {
            return EMPTY_STATS;
        }
        final Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (final Exception ignored) {
            return EMPTY_STATS;
        }
        final MutableStat stat = new MutableStat(json.getBytes(StandardCharsets.UTF_8).length);
        walk(parsed, 1, stat);
        return stat.toStats();
    }

    /**
     * 统计已解析的 JSON 节点树（复用树面板解析结果，避免二次解析）。
     *
     * @param root      解析根节点
     * @param sizeBytes 原始文本字节数
     * @return 统计结果；空节点返回全零统计
     */
    public static Stats analyze(final JsonNode root, final long sizeBytes) {
        if (Objects.isNull(root)) {
            return EMPTY_STATS;
        }
        final MutableStat stat = new MutableStat(sizeBytes);
        walkNode(root, 1, stat);
        return stat.toStats();
    }

    /**
     * 递归遍历统计节点。
     *
     * @param value 节点值
     * @param depth 当前深度（根节点为 1）
     * @param stat  统计累加器
     */
    private static void walk(final Object value, final int depth, final MutableStat stat) {
        if (value instanceof final JSONObject obj) {
            stat.objects++;
            stat.maxDepth = Math.max(stat.maxDepth, depth);
            stat.keys += obj.size();
            for (final Object child : obj.values()) {
                walk(child, depth + 1, stat);
            }
        } else if (value instanceof final JSONArray arr) {
            stat.arrays++;
            stat.maxDepth = Math.max(stat.maxDepth, depth);
            for (final Object child : arr) {
                walk(child, depth + 1, stat);
            }
        }
    }

    /**
     * 基于 JsonNode 树递归统计（与 {@link #walk} 语义一致，保证两类入口结果相同）。
     *
     * @param node  节点
     * @param depth 当前深度（根节点为 1）
     * @param stat  统计累加器
     */
    private static void walkNode(final JsonNode node, final int depth, final MutableStat stat) {
        final String type = node.type();
        if ("Object".equals(type)) {
            stat.objects++;
            stat.maxDepth = Math.max(stat.maxDepth, depth);
            stat.keys += node.children().size();
            for (final JsonNode child : node.children()) {
                walkNode(child, depth + 1, stat);
            }
        } else if ("Array".equals(type)) {
            stat.arrays++;
            stat.maxDepth = Math.max(stat.maxDepth, depth);
            for (final JsonNode child : node.children()) {
                walkNode(child, depth + 1, stat);
            }
        }
    }

    /**
     * 检测重复键（按出现次数降序）。
     *
     * <p>fastjson2 解析对象时重复键会直接覆盖，解析后无法感知，因此必须走字符串层检测：
     * 引号感知扫描，仅统计字符串外 {@code "key":} 形式的对象键。</p>
     *
     * @param json JSON 文本
     * @return 重复键名到出现次数的映射；无重复或输入非法时返回空映射
     */
    public static Map<String, Integer> duplicateKeys(final String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        final Map<String, Integer> counts = new LinkedHashMap<>();
        boolean inString = false;
        boolean inKeyCandidate = false;
        final StringBuilder key = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (inString) {
                key.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    key.append(json.charAt(i + 1));
                    i++;
                } else if (c == '"') {
                    inString = false;
                    inKeyCandidate = true;
                }
            } else if (c == '"') {
                inString = true;
                inKeyCandidate = false;
                key.setLength(0);
                key.append(c);
            } else if (inKeyCandidate) {
                if (c == ':') {
                    // 字符串后紧跟冒号 → 判定为对象键，去除首尾引号后计数
                    final String name = key.toString();
                    if (name.length() >= 2) {
                        counts.merge(name.substring(1, name.length() - 1), 1, Integer::sum);
                    }
                    inKeyCandidate = false;
                } else if (!Character.isWhitespace(c)) {
                    // 字符串后非冒号 → 是字符串值，非键
                    inKeyCandidate = false;
                }
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right, LinkedHashMap::new));
    }

    /**
     * 可变统计累加器。
     */
    private static final class MutableStat {
        /**
         * 对象键总数
         */
        private int keys;
        /**
         * 对象节点数
         */
        private int objects;
        /**
         * 数组节点数
         */
        private int arrays;
        /**
         * 最大嵌套深度
         */
        private int maxDepth;
        /**
         * 文本字节数
         */
        private final long sizeBytes;

        /**
         * 构造累加器
         *
         * @param sizeBytes 文本字节数
         */
        private MutableStat(final long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * 转为不可变统计
         *
         * @return {@link Stats }
         */
        private Stats toStats() {
            return new Stats(this.keys, this.objects, this.arrays, this.maxDepth, this.sizeBytes);
        }
    }
}
