package com.acme.prism.core.json;

import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 反扁平化器：将点号路径键值对还原为嵌套 JSON（{@code [i]} 重建数组）。
 *
 * <p>示例：{@code {"a.b":1,"c[0]":1,"c[1]":2}} → {@code {"a":{"b":1},"c":[1,2]}}。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonUnflattener implements JsonOperation {

    /**
     * 数组索引段模式（如 {@code [0]}、{@code [1][2]}）
     */
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[(\\d+)]");

    /**
     * 路径段：名称与数组索引（两者互斥，数组段 index 有效）。
     *
     * @param name  段名称（数组段为空串）
     * @param index 数组索引（非数组段为 -1）
     */
    private record Segment(String name, int index) {

        /**
         * 是否为数组索引段
         *
         * @return boolean
         */
        private boolean isArray() {
            return this.index >= 0;
        }
    }

    /**
     * JSON 操作契约：还原嵌套 JSON；冲突或输入非法时原样返回。
     *
     * @param json 输入
     * @return 还原后的嵌套 JSON；键路径冲突时原样返回
     */
    @Override
    public String process(final String json) {
        return Opt.ofNullable(unflatten(json)).orElse(json);
    }

    /**
     * 反扁平化 JSON。
     *
     * @param json 扁平 JSON
     * @return 嵌套 JSON 文本；输入非法或键路径冲突时返回 {@code null}
     */
    public static String unflatten(final String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            final JSONObject source = JSON.parseObject(json);
            if (Objects.isNull(source)) {
                return null;
            }
            final Object root = buildNested(source);
            if (Objects.isNull(root)) {
                return null;
            }
            return JSON.toJSONString(root, JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * 构建嵌套结构。
     *
     * @param source 扁平键值对
     * @return 嵌套 JSON 对象；键路径冲突时返回 {@code null}
     */
    private static Object buildNested(final JSONObject source) {
        final JSONObject root = new JSONObject();
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            final List<Segment> segments = parseSegments(entry.getKey());
            if (segments.isEmpty()) {
                return null;
            }
            Object container = root;
            for (int i = 0; i < segments.size() - 1; i++) {
                final boolean nextIsArray = segments.get(i + 1).isArray();
                container = descend(container, segments.get(i), nextIsArray);
                if (Objects.isNull(container)) {
                    return null;
                }
            }
            if (!assign(container, segments.get(segments.size() - 1), entry.getValue())) {
                return null;
            }
        }
        return root;
    }

    /**
     * 解析点号路径为路径段列表（支持 {@code a.b[0].c} 形式）。
     *
     * @param path 路径
     * @return 路径段列表
     */
    private static List<Segment> parseSegments(final String path) {
        final List<Segment> segments = new ArrayList<>(4);
        for (final String part : path.split("\\.")) {
            final int bracketIndex = part.indexOf('[');
            if (bracketIndex < 0) {
                segments.add(new Segment(part, -1));
                continue;
            }
            final String name = part.substring(0, bracketIndex);
            if (StrUtil.isNotEmpty(name)) {
                segments.add(new Segment(name, -1));
            }
            final Matcher matcher = ARRAY_INDEX.matcher(part);
            while (matcher.find()) {
                segments.add(new Segment("", Integer.parseInt(matcher.group(1))));
            }
        }
        return segments;
    }

    /**
     * 进入下一层容器（不存在则按需创建）。
     *
     * @param container   当前容器
     * @param segment     当前路径段
     * @param nextIsArray 下一段是否为数组索引
     * @return 子容器；冲突（已有标量）或结构不匹配时返回 {@code null}
     */
    private static Object descend(final Object container, final Segment segment, final boolean nextIsArray) {
        if (container instanceof final JSONObject obj) {
            if (obj.containsKey(segment.name())) {
                final Object existing = obj.get(segment.name());
                return existing instanceof JSONObject || existing instanceof JSONArray ? existing : null;
            }
            final Object child = nextIsArray ? new JSONArray() : new JSONObject();
            obj.put(segment.name(), child);
            return child;
        }
        if (container instanceof final JSONArray arr && segment.isArray()) {
            ensureCapacity(arr, segment.index());
            final Object existing = arr.get(segment.index());
            if (Objects.nonNull(existing)) {
                return existing instanceof JSONObject || existing instanceof JSONArray ? existing : null;
            }
            final Object child = nextIsArray ? new JSONArray() : new JSONObject();
            arr.set(segment.index(), child);
            return child;
        }
        return null;
    }

    /**
     * 赋值叶子路径段。
     *
     * @param container 当前容器
     * @param segment   叶子路径段
     * @param value     值
     * @return 是否赋值成功；键冲突或结构不匹配时返回 {@code false}
     */
    private static boolean assign(final Object container, final Segment segment, final Object value) {
        if (container instanceof final JSONObject obj) {
            if (obj.containsKey(segment.name())) {
                return false;
            }
            obj.put(segment.name(), value);
            return true;
        }
        if (container instanceof final JSONArray arr && segment.isArray()) {
            ensureCapacity(arr, segment.index());
            if (Objects.nonNull(arr.get(segment.index()))) {
                return false;
            }
            arr.set(segment.index(), value);
            return true;
        }
        return false;
    }

    /**
     * 扩容数组至指定索引（不足位置补 null 占位）。
     *
     * @param arr   数组
     * @param index 目标索引
     */
    private static void ensureCapacity(final JSONArray arr, final int index) {
        while (arr.size() <= index) {
            arr.add(null);
        }
    }
}
