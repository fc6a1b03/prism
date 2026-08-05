package com.acme.prism.core.json;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema 校验器：按 JSON Schema（Draft 7 常用关键字）逐字段校验 JSON 数据。
 *
 * <p>支持关键字：type（含多类型数组）、required、properties、items、enum、
 * minLength/maxLength、minimum/maximum、pattern。输出每个失败项（路径/期望/实际/说明）
 * 与已校验字段总数。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonSchemaValidator {

    /**
     * 校验失败项。
     *
     * @param path     JSONPath 路径（如 {@code $.user.name}）
     * @param expected 期望
     * @param actual   实际
     * @param message  失败说明
     */
    public record ValidationIssue(String path, String expected, String actual, String message) {
    }

    /**
     * 校验结果。
     *
     * @param issues      失败项列表（校验通过为空列表）
     * @param checkedCount 已校验字段/值总数（用于汇总展示）
     */
    public record ValidationOutcome(List<ValidationIssue> issues, int checkedCount) {
    }

    /**
     * 校验 JSON 数据是否符合 Schema。
     *
     * @param json   JSON 数据
     * @param schema JSON Schema（对象形式）
     * @return 校验结果；输入为空或解析失败时返回解析失败项
     */
    public static ValidationOutcome validate(final String json, final String schema) {
        if (StrUtil.isBlank(json) || StrUtil.isBlank(schema)) {
            return new ValidationOutcome(List.of(), 0);
        }
        final Object jsonValue;
        final JSONObject schemaObj;
        try {
            jsonValue = JSON.parse(json);
            schemaObj = JSON.parseObject(schema);
        } catch (final Exception ignored) {
            return new ValidationOutcome(
                    List.of(new ValidationIssue("$", "合法 JSON 与 Schema", "解析失败", "JSON 或 Schema 无法解析")),
                    0
            );
        }
        if (Objects.isNull(schemaObj)) {
            return new ValidationOutcome(
                    List.of(new ValidationIssue("$", "Schema 对象", "非对象", "Schema 必须是 JSON 对象")),
                    0
            );
        }
        final List<ValidationIssue> issues = new ArrayList<>();
        final AtomicInteger checked = new AtomicInteger();
        validateValue(jsonValue, schemaObj, "$", issues, checked);
        return new ValidationOutcome(issues, checked.get());
    }

    /**
     * 递归校验值。
     *
     * @param value      实际值
     * @param schemaNode Schema 节点
     * @param path       当前路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateValue(final Object value, final JSONObject schemaNode, final String path,
                                      final List<ValidationIssue> issues, final AtomicInteger checked) {
        checked.incrementAndGet();
        // 1. 类型校验（失败后跳过其余关键字，避免连环误报）
        final Object type = schemaNode.get("type");
        if (Objects.nonNull(type) && !matchesType(value, type)) {
            issues.add(new ValidationIssue(path, "类型 " + type, actualType(value), "类型不匹配"));
            return;
        }
        // 2. 枚举校验
        final JSONArray enumValues = schemaNode.getJSONArray("enum");
        if (Objects.nonNull(enumValues) && !enumValues.contains(value)) {
            issues.add(new ValidationIssue(path, "枚举值之一", String.valueOf(value), "不在枚举范围内"));
        }
        // 3. 字符串约束
        if (value instanceof final String text) {
            validateString(text, schemaNode, path, issues);
        }
        // 4. 数字约束
        if (value instanceof final Number number) {
            validateNumber(number, schemaNode, path, issues);
        }
        // 5. 对象：必填字段 + 子属性递归
        if (value instanceof final JSONObject obj) {
            validateObject(obj, schemaNode, path, issues, checked);
        }
        // 6. 数组：元素递归
        if (value instanceof final JSONArray arr) {
            final Object items = schemaNode.get("items");
            if (items instanceof final JSONObject itemSchema) {
                for (int index = 0; index < arr.size(); index++) {
                    validateValue(arr.get(index), itemSchema, "%s[%d]".formatted(path, index), issues, checked);
                }
            }
        }
    }

    /**
     * 校验字符串约束。
     *
     * @param text       字符串
     * @param schemaNode Schema 节点
     * @param path       路径
     * @param issues     失败项收集器
     */
    private static void validateString(final String text, final JSONObject schemaNode, final String path,
                                       final List<ValidationIssue> issues) {
        final Integer minLength = schemaNode.getInteger("minLength");
        if (Objects.nonNull(minLength) && text.length() < minLength) {
            issues.add(new ValidationIssue(path, "长度 ≥ %d".formatted(minLength), String.valueOf(text.length()), "字符串过短"));
        }
        final Integer maxLength = schemaNode.getInteger("maxLength");
        if (Objects.nonNull(maxLength) && text.length() > maxLength) {
            issues.add(new ValidationIssue(path, "长度 ≤ %d".formatted(maxLength), String.valueOf(text.length()), "字符串过长"));
        }
        final String pattern = schemaNode.getString("pattern");
        if (StrUtil.isNotBlank(pattern)) {
            try {
                if (!Pattern.compile(pattern).matcher(text).matches()) {
                    issues.add(new ValidationIssue(path, "匹配 " + pattern, text, "不匹配正则"));
                }
            } catch (final PatternSyntaxException ignored) {
                // Schema 中非法正则忽略，不中断校验
            }
        }
    }

    /**
     * 校验数字约束。
     *
     * @param number     数字
     * @param schemaNode Schema 节点
     * @param path       路径
     * @param issues     失败项收集器
     */
    private static void validateNumber(final Number number, final JSONObject schemaNode, final String path,
                                       final List<ValidationIssue> issues) {
        final BigDecimal value = BigDecimal.valueOf(number.doubleValue());
        final BigDecimal minimum = toBigDecimal(schemaNode.get("minimum"));
        if (Objects.nonNull(minimum) && value.compareTo(minimum) < 0) {
            issues.add(new ValidationIssue(path, "≥ " + minimum.toPlainString(), String.valueOf(number), "小于最小值"));
        }
        final BigDecimal maximum = toBigDecimal(schemaNode.get("maximum"));
        if (Objects.nonNull(maximum) && value.compareTo(maximum) > 0) {
            issues.add(new ValidationIssue(path, "≤ " + maximum.toPlainString(), String.valueOf(number), "大于最大值"));
        }
    }

    /**
     * 校验对象：必填字段与子属性递归。
     *
     * @param obj        对象
     * @param schemaNode Schema 节点
     * @param path       路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateObject(final JSONObject obj, final JSONObject schemaNode, final String path,
                                       final List<ValidationIssue> issues, final AtomicInteger checked) {
        final JSONArray required = schemaNode.getJSONArray("required");
        if (Objects.nonNull(required)) {
            for (final Object item : required) {
                final String key = Convert.toStr(item);
                if (!obj.containsKey(key)) {
                    issues.add(new ValidationIssue(path, "必填字段 " + key, "缺失", "缺少必填字段 " + key));
                }
            }
        }
        final JSONObject properties = schemaNode.getJSONObject("properties");
        if (Objects.isNull(properties)) {
            return;
        }
        for (final String key : properties.keySet()) {
            if (obj.containsKey(key) && properties.get(key) instanceof final JSONObject childSchema) {
                validateValue(obj.get(key), childSchema, "%s.%s".formatted(path, key), issues, checked);
            }
        }
    }

    /**
     * 值类型是否匹配 Schema 类型声明（支持多类型数组）。
     *
     * @param value 实际值
     * @param type  类型声明（String 或 JSONArray）
     * @return boolean
     */
    private static boolean matchesType(final Object value, final Object type) {
        if (type instanceof final String single) {
            return matchesType(value, single);
        }
        if (type instanceof final JSONArray types) {
            for (final Object item : types) {
                if (matchesType(value, Convert.toStr(item))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 值类型是否匹配单个类型名。
     *
     * @param value 实际值
     * @param type  类型名
     * @return boolean
     */
    private static boolean matchesType(final Object value, final String type) {
        return switch (type) {
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "null" -> Objects.isNull(value);
            // 未知类型声明不拦截
            default -> true;
        };
    }

    /**
     * 实际值类型描述。
     *
     * @param value 实际值
     * @return 类型描述
     */
    private static String actualType(final Object value) {
        if (Objects.isNull(value)) {
            return "null";
        }
        if (value instanceof JSONObject) {
            return "object";
        }
        if (value instanceof JSONArray) {
            return "array";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        return "string";
    }

    /**
     * 转 BigDecimal（用于数值范围比较）。
     *
     * @param value 值
     * @return BigDecimal；非数字返回 {@code null}
     */
    private static BigDecimal toBigDecimal(final Object value) {
        return value instanceof final Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
    }
}
