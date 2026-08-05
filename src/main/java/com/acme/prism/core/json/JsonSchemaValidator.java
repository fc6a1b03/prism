package com.acme.prism.core.json;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema 校验器：按 JSON Schema（Draft 7 常用关键字）逐字段校验 JSON 数据。
 *
 * <p>支持关键字：type（含多类型数组）、required、properties、items、enum、minLength/maxLength、
 * minimum/maximum、pattern、format（email/date-time/date/time/uri/uuid/ipv4/ipv6）、
 * oneOf/anyOf/allOf、minItems/maxItems/uniqueItems、minProperties/maxProperties、
 * exclusiveMinimum/exclusiveMaximum、multipleOf。输出每个失败项（路径/期望/实际/说明）
 * 与已校验字段总数。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonSchemaValidator {

    /**
     * email 格式正则
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /**
     * uuid 格式正则
     */
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /**
     * ipv4 格式正则
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    /**
     * ipv6 格式正则（覆盖常见书写，非完整 RFC 4291）
     */
    private static final Pattern IPV6_PATTERN = Pattern.compile("^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");

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
        // 3. 组合约束校验（oneOf 恰好一个 / anyOf 至少一个 / allOf 全部满足）
        validateCombinators(value, schemaNode, path, issues);
        // 4. 字符串约束
        if (value instanceof final String text) {
            validateString(text, schemaNode, path, issues);
        }
        // 5. 数字约束
        if (value instanceof final Number number) {
            validateNumber(number, schemaNode, path, issues);
        }
        // 6. 对象：必填字段 + 属性数量 + 子属性递归
        if (value instanceof final JSONObject obj) {
            validateObject(obj, schemaNode, path, issues, checked);
        }
        // 7. 数组：长度约束 + 元素唯一 + 元素递归
        if (value instanceof final JSONArray arr) {
            validateArray(arr, schemaNode, path, issues, checked);
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
        final String format = schemaNode.getString("format");
        if (StrUtil.isNotBlank(format)) {
            validateFormat(text, format, path, issues);
        }
    }

    /**
     * 校验字符串格式约束（未知 format 不拦截，符合 Draft 7 语义）。
     *
     * @param text   字符串
     * @param format 格式名
     * @param path   路径
     * @param issues 失败项收集器
     */
    private static void validateFormat(final String text, final String format, final String path,
                                       final List<ValidationIssue> issues) {
        final boolean valid = switch (format) {
            case "email" -> EMAIL_PATTERN.matcher(text).matches();
            case "uuid" -> UUID_PATTERN.matcher(text).matches();
            case "ipv4" -> IPV4_PATTERN.matcher(text).matches();
            case "ipv6" -> IPV6_PATTERN.matcher(text).matches();
            case "date" -> isParseable(text, LocalDate.class);
            case "time" -> isParseable(text, LocalTime.class);
            case "date-time" -> isParseable(text, OffsetDateTime.class) || isParseable(text, Instant.class);
            case "uri" -> isUri(text);
            default -> Boolean.TRUE;
        };
        if (!valid) {
            issues.add(new ValidationIssue(path, "格式 " + format, text, "不符合格式要求"));
        }
    }

    /**
     * 尝试按类型解析时间文本。
     *
     * @param text      文本
     * @param timeClass 时间类型
     * @return boolean
     */
    private static boolean isParseable(final String text, final Class<?> timeClass) {
        try {
            if (timeClass == LocalDate.class) {
                LocalDate.parse(text);
            } else if (timeClass == LocalTime.class) {
                LocalTime.parse(text);
            } else if (timeClass == OffsetDateTime.class) {
                OffsetDateTime.parse(text);
            } else {
                Instant.parse(text);
            }
            return Boolean.TRUE;
        } catch (final DateTimeParseException ignored) {
            return Boolean.FALSE;
        }
    }

    /**
     * 校验是否为合法 URI（需含 scheme）。
     *
     * @param text 文本
     * @return boolean
     */
    private static boolean isUri(final String text) {
        try {
            final URI uri = URI.create(text);
            return StrUtil.isNotBlank(uri.getScheme());
        } catch (final IllegalArgumentException ignored) {
            return Boolean.FALSE;
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
        final BigDecimal exclusiveMinimum = toBigDecimal(schemaNode.get("exclusiveMinimum"));
        if (Objects.nonNull(exclusiveMinimum) && value.compareTo(exclusiveMinimum) <= 0) {
            issues.add(new ValidationIssue(path, "> " + exclusiveMinimum.toPlainString(), String.valueOf(number), "不大于排他最小值"));
        }
        final BigDecimal exclusiveMaximum = toBigDecimal(schemaNode.get("exclusiveMaximum"));
        if (Objects.nonNull(exclusiveMaximum) && value.compareTo(exclusiveMaximum) >= 0) {
            issues.add(new ValidationIssue(path, "< " + exclusiveMaximum.toPlainString(), String.valueOf(number), "不小于排他最大值"));
        }
        final BigDecimal multipleOf = toBigDecimal(schemaNode.get("multipleOf"));
        if (Objects.nonNull(multipleOf) && multipleOf.compareTo(BigDecimal.ZERO) > 0
                && value.remainder(multipleOf).compareTo(BigDecimal.ZERO) != 0) {
            issues.add(new ValidationIssue(path, "是 " + multipleOf.toPlainString() + " 的倍数", String.valueOf(number), "不是倍数"));
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
        final Integer minProperties = schemaNode.getInteger("minProperties");
        if (Objects.nonNull(minProperties) && obj.size() < minProperties) {
            issues.add(new ValidationIssue(path, "属性数 ≥ %d".formatted(minProperties), String.valueOf(obj.size()), "对象属性过少"));
        }
        final Integer maxProperties = schemaNode.getInteger("maxProperties");
        if (Objects.nonNull(maxProperties) && obj.size() > maxProperties) {
            issues.add(new ValidationIssue(path, "属性数 ≤ %d".formatted(maxProperties), String.valueOf(obj.size()), "对象属性过多"));
        }
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
     * 校验数组：长度约束 + 元素唯一 + 元素递归。
     *
     * @param arr        数组
     * @param schemaNode Schema 节点
     * @param path       路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateArray(final JSONArray arr, final JSONObject schemaNode, final String path,
                                      final List<ValidationIssue> issues, final AtomicInteger checked) {
        final Integer minItems = schemaNode.getInteger("minItems");
        if (Objects.nonNull(minItems) && arr.size() < minItems) {
            issues.add(new ValidationIssue(path, "长度 ≥ %d".formatted(minItems), String.valueOf(arr.size()), "数组元素过少"));
        }
        final Integer maxItems = schemaNode.getInteger("maxItems");
        if (Objects.nonNull(maxItems) && arr.size() > maxItems) {
            issues.add(new ValidationIssue(path, "长度 ≤ %d".formatted(maxItems), String.valueOf(arr.size()), "数组元素过多"));
        }
        final Boolean uniqueItems = schemaNode.getBoolean("uniqueItems");
        if (Boolean.TRUE.equals(uniqueItems) && hasDuplicates(arr)) {
            issues.add(new ValidationIssue(path, "元素唯一", String.valueOf(arr.size()) + " 个元素", "数组存在重复元素"));
        }
        final Object items = schemaNode.get("items");
        if (items instanceof final JSONObject itemSchema) {
            for (int index = 0; index < arr.size(); index++) {
                validateValue(arr.get(index), itemSchema, "%s[%d]".formatted(path, index), issues, checked);
            }
        }
    }

    /**
     * 数组元素是否含重复（JSON 值按字符串化比较）。
     *
     * @param arr 数组
     * @return boolean
     */
    private static boolean hasDuplicates(final JSONArray arr) {
        final Set<String> seen = new HashSet<>(arr.size());
        for (final Object item : arr) {
            if (!seen.add(JSON.toJSONString(item))) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * 校验组合约束（oneOf 恰好一个 / anyOf 至少一个 / allOf 全部满足）。
     *
     * @param value      实际值
     * @param schemaNode Schema 节点
     * @param path       路径
     * @param issues     失败项收集器
     */
    private static void validateCombinators(final Object value, final JSONObject schemaNode, final String path,
                                            final List<ValidationIssue> issues) {
        final JSONArray oneOf = schemaNode.getJSONArray("oneOf");
        if (Objects.nonNull(oneOf)) {
            final long matched = countMatches(value, oneOf, path);
            if (matched != 1) {
                issues.add(new ValidationIssue(path, "恰好匹配一个子约束", "匹配 " + matched + " 个", "oneOf 约束不满足"));
            }
        }
        final JSONArray anyOf = schemaNode.getJSONArray("anyOf");
        if (Objects.nonNull(anyOf) && countMatches(value, anyOf, path) == 0) {
            issues.add(new ValidationIssue(path, "至少匹配一个子约束", "匹配 0 个", "anyOf 约束不满足"));
        }
        final JSONArray allOf = schemaNode.getJSONArray("allOf");
        if (Objects.nonNull(allOf)) {
            final long matched = countMatches(value, allOf, path);
            if (matched != allOf.size()) {
                issues.add(new ValidationIssue(path, "满足全部 " + allOf.size() + " 个子约束", "满足 " + matched + " 个", "allOf 约束不满足"));
            }
        }
    }

    /**
     * 统计值与子约束列表匹配的数量（独立校验，不污染主失败项与计数）。
     *
     * @param value      实际值
     * @param schemas    子约束列表
     * @param path       路径
     * @return 匹配数量
     */
    private static long countMatches(final Object value, final JSONArray schemas, final String path) {
        return schemas.stream()
                .filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .filter(subSchema -> {
                    final List<ValidationIssue> temp = new ArrayList<>();
                    validateValue(value, subSchema, path, temp, new AtomicInteger());
                    return temp.isEmpty();
                })
                .count();
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
