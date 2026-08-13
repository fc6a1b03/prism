package com.acme.prism.core.parser.converter;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Markdown 表格转换器：将 JSON 转为 Markdown 表格文本。
 *
 * <p>转换规则：纯对象数组转并集列头表格（列头取全部对象键的首次出现顺序并集，
 * null 值输出空单元格，对象/数组值 JSON 字符串化）；混合数组转索引/值两列表；
 * 单对象转键/值两列表；标量转单列表。管道符转义为 {@code \|}，换行转 {@code <br>}，
 * 保证单元格不破坏表格结构。转换失败或输入为空返回空串。</p>
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
public final class MarkdownTableConverter implements DataFormatConverter {

    /**
     * 表头分隔行单元格内容
     */
    private static final String HEADER_DIVIDER = "---";
    /**
     * 键值表头列名
     */
    private static final String HEADER_KEY = "Key";
    /**
     * 键值表头列名
     */
    private static final String HEADER_VALUE = "Value";
    /**
     * 混合数组索引列名
     */
    private static final String HEADER_INDEX = "Index";
    /**
     * 管道符转义目标
     */
    private static final String ESCAPED_PIPE = "\\|";
    /**
     * 换行转义目标（Markdown 单元格内换行）
     */
    private static final String ESCAPED_NEWLINE = "<br>";

    /**
     * 转换 JSON 为 Markdown 表格。
     *
     * @param json JSON 文本
     * @return Markdown 表格文本；输入为空或转换失败时返回空串
     */
    @Override
    public String convert(final String json) {
        if (StrUtil.isBlank(json)) {
            return "";
        }
        try {
            return switch (JSON.parse(json)) {
                case final JSONArray arr -> arrayToTable(arr);
                case final JSONObject obj -> objectToTable(obj);
                default -> singleValueTable(json);
            };
        } catch (final Exception ignored) {
            return "";
        }
    }

    /**
     * 数组转表格：纯对象数组转键列表，混合数组转索引/值两列表（避免非对象元素丢失）。
     *
     * @param arr 数组
     * @return Markdown 表格；空数组返回空串
     */
    private static String arrayToTable(final JSONArray arr) {
        if (arr.isEmpty()) {
            return "";
        }
        if (arr.stream().allMatch(JSONObject.class::isInstance)) {
            return objectArrayToTable(arr);
        }
        final StringBuilder sb = new StringBuilder();
        appendRow(sb, List.of(HEADER_INDEX, HEADER_VALUE));
        appendRow(sb, List.of(HEADER_DIVIDER, HEADER_DIVIDER));
        for (int index = 0; index < arr.size(); index++) {
            appendRow(sb, List.of(String.valueOf(index), cellOf(arr.get(index))));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 纯对象数组转并集列头表格。
     *
     * @param arr 对象数组
     * @return Markdown 表格
     */
    private static String objectArrayToTable(final JSONArray arr) {
        final Set<String> headers = new LinkedHashSet<>();
        for (final Object item : arr) {
            headers.addAll(((JSONObject) item).keySet());
        }
        final List<String> headerList = List.copyOf(headers);
        final StringBuilder sb = new StringBuilder();
        appendRow(sb, headerList.stream().map(MarkdownTableConverter::escapeCell).toList());
        appendRow(sb, headerList.stream().map(_ -> HEADER_DIVIDER).toList());
        for (final Object item : arr) {
            final JSONObject obj = (JSONObject) item;
            appendRow(sb, headerList.stream().map(key -> cellOf(obj.get(key))).toList());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 单对象转键/值两列表格。
     *
     * @param obj 对象
     * @return Markdown 表格
     */
    private static String objectToTable(final JSONObject obj) {
        final StringBuilder sb = new StringBuilder();
        appendRow(sb, List.of(HEADER_KEY, HEADER_VALUE));
        appendRow(sb, List.of(HEADER_DIVIDER, HEADER_DIVIDER));
        for (final String key : obj.keySet()) {
            appendRow(sb, List.of(escapeCell(key), cellOf(obj.get(key))));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 标量转单列表格。
     *
     * @param text 原始 JSON 文本（标量字面量）
     * @return Markdown 表格
     */
    private static String singleValueTable(final String text) {
        final StringBuilder sb = new StringBuilder();
        appendRow(sb, List.of(HEADER_VALUE));
        appendRow(sb, List.of(HEADER_DIVIDER));
        appendRow(sb, List.of(escapeCell(text)));
        return sb.toString().stripTrailing();
    }

    /**
     * 追加一行表格（首尾管道符 + 单元格空格包裹）。
     *
     * @param sb    目标构建器
     * @param cells 单元格内容
     */
    private static void appendRow(final StringBuilder sb, final List<String> cells) {
        sb.append('|');
        for (final String cell : cells) {
            sb.append(' ').append(cell).append(" |");
        }
        sb.append('\n');
    }

    /**
     * 值转单元格：null 输出空单元格，对象/数组 JSON 字符串化后转义。
     *
     * @param value 值
     * @return 单元格内容
     */
    private static String cellOf(final Object value) {
        if (Objects.isNull(value)) {
            return "";
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return escapeCell(JSON.toJSONString(value));
        }
        return escapeCell(String.valueOf(value));
    }

    /**
     * 单元格转义：管道符与换行会破坏表格结构，统一转义。
     *
     * @param text 原文
     * @return 转义后的单元格内容
     */
    private static String escapeCell(final String text) {
        return text.replace("|", ESCAPED_PIPE).replace("\n", ESCAPED_NEWLINE);
    }
}
