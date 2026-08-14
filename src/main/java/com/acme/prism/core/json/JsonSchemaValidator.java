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
 * JSON Schema 校验器：按 JSON Schema（2020-12 常用关键字）逐字段校验 JSON 数据。
 *
 * <p>支持关键字：type（含多类型数组）、required、properties、items、enum、minLength/maxLength、
 * minimum/maximum、pattern、format（email/date-time/date/time/uri/uuid/ipv4/ipv6）、
 * oneOf/anyOf/allOf/not、if/then/else、minItems/maxItems/uniqueItems、minProperties/maxProperties、
 * contains/minContains/maxContains、propertyNames、dependentRequired、dependentSchemas、
 * $ref（当前文档内 {@code #/} 指针，含 {@code $defs}/{@code definitions}，2020-12 与兄弟关键字共存）、
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
     * $ref 关键字名
     */
    private static final String REF_KEY = "$ref";
    /**
     * $ref 链式引用最大解析层数（防引用环无限展开，如 A 引用 B 且 B 引用 A）
     */
    private static final int MAX_REF_CHAIN = 16;
    /**
     * JSON 指针段转义目标（指针内 {@code ~1} 表示 {@code /}，{@code ~0} 表示 {@code ~}）
     */
    private static final String POINTER_ESCAPED_SLASH = "~1";
    /**
     * JSON 指针段转义目标（指针内 {@code ~0} 表示 {@code ~}）
     */
    private static final String POINTER_ESCAPED_TILDE = "~0";
    /**
     * JSON 指针段转义还原（斜杠）
     */
    private static final String POINTER_SLASH = "/";
    /**
     * JSON 指针段转义还原（波浪号）
     */
    private static final String POINTER_TILDE = "~";
    /**
     * 文档内引用前缀（仅支持当前文档内指针，外部 URI 引用不支持）
     */
    private static final String REF_DOCUMENT_PREFIX = "#";

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
        validateValue(jsonValue, schemaObj, schemaObj, "$", issues, checked);
        return new ValidationOutcome(issues, checked.get());
    }

    /**
     * 递归校验值。
     *
     * @param value      实际值
     * @param schemaNode Schema 节点
     * @param rootSchema Schema 根（$ref 指针解析基准）
     * @param path       当前路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateValue(final Object value, final JSONObject schemaNode, final JSONObject rootSchema,
                                      final String path, final List<ValidationIssue> issues, final AtomicInteger checked) {
        checked.incrementAndGet();
        // 0. $ref 解析（2020-12 语义：$ref 与兄弟关键字共存，解析后合并校验）
        final JSONObject effective = resolveRefs(schemaNode, rootSchema, path, issues);
        // 1. 类型校验（失败后跳过其余关键字，避免连环误报）
        final Object type = effective.get("type");
        if (Objects.nonNull(type) && !matchesType(value, type)) {
            issues.add(new ValidationIssue(path, "类型 " + type, actualType(value), "类型不匹配"));
            return;
        }
        // 2. 枚举与常量校验
        final JSONArray enumValues = effective.getJSONArray("enum");
        if (Objects.nonNull(enumValues) && !enumValues.contains(value)) {
            issues.add(new ValidationIssue(path, "枚举值之一", String.valueOf(value), "不在枚举范围内"));
        }
        final Object constValue = effective.get("const");
        if (Objects.nonNull(constValue)) {
            // 数值按数值比较（2020-12 语义：1 与 1.0 相等），其余按 JSON 值比较
            final boolean constMatches = constValue instanceof final Number constNum && value instanceof final Number valueNum
                    ? BigDecimal.valueOf(constNum.doubleValue()).compareTo(BigDecimal.valueOf(valueNum.doubleValue())) == 0
                    : JSON.toJSONString(constValue).equals(JSON.toJSONString(value));
            if (!constMatches) {
                issues.add(new ValidationIssue(path, "等于 " + JSON.toJSONString(constValue), String.valueOf(value), "const 约束不满足"));
            }
        }
        // 3. 组合约束校验（oneOf 恰好一个 / anyOf 至少一个 / allOf 全部满足 / not 否定）
        validateCombinators(value, effective, rootSchema, path, issues);
        final Object notSchema = effective.get("not");
        if (notSchema instanceof final JSONObject notObj && checkMatches(value, notObj, rootSchema, path)) {
            issues.add(new ValidationIssue(path, "不匹配否定约束", String.valueOf(value), "not 约束不满足"));
        }
        // 3.5 条件约束校验（if/then/else，2020-12 语义）
        validateConditionals(value, effective, rootSchema, path, issues);
        // 4. 字符串约束
        if (value instanceof final String text) {
            validateString(text, effective, path, issues);
        }
        // 5. 数字约束
        if (value instanceof final Number number) {
            validateNumber(number, effective, path, issues);
        }
        // 6. 对象：必填字段 + 属性数量 + 动态键名 + 未知字段 + 子属性递归
        if (value instanceof final JSONObject obj) {
            validateObject(obj, effective, rootSchema, path, issues, checked);
        }
        // 7. 数组：长度约束 + 元素唯一 + 元组校验 + 元素递归
        if (value instanceof final JSONArray arr) {
            validateArray(arr, effective, rootSchema, path, issues, checked);
        }
    }

    /**
     * 解析 $ref 链并合并关键字（2020-12 语义：$ref 与兄弟关键字共存）。
     * <p>目标 schema 关键字与当前节点兄弟关键字合并（兄弟覆盖目标同名）；
     * 目标自身仍含 {@code $ref} 时继续内联展开，直接自引用（target 与 current 同一对象）
     * 立即截断，引用环/超长链按 {@link #MAX_REF_CHAIN} 上限截断防死循环。
     * 非法/外部引用报失败项并保留当前节点继续校验（不中断）。</p>
     *
     * @param schemaNode Schema 节点
     * @param rootSchema Schema 根
     * @param path       当前路径
     * @param issues     失败项收集器
     * @return 合并后的有效 Schema（无待解析 $ref 或已按上限截断）
     */
    private static JSONObject resolveRefs(final JSONObject schemaNode, final JSONObject rootSchema,
                                          final String path, final List<ValidationIssue> issues) {
        JSONObject current = schemaNode;
        for (int chain = 0; chain < MAX_REF_CHAIN; chain++) {
            final String ref = current.getString(REF_KEY);
            if (StrUtil.isBlank(ref)) {
                return current;
            }
            final JSONObject target = resolveJsonPointer(ref, rootSchema);
            if (Objects.isNull(target)) {
                issues.add(new ValidationIssue(path, "可解析的 $ref", ref, "无法解析 $ref（仅支持当前文档内 #/ 指针）"));
                return current;
            }
            // 直接自引用（target 与 current 同一对象）：无新约束，立即截断
            if (target == current) {
                return current;
            }
            final JSONObject merged = new JSONObject(target.size() + current.size());
            merged.putAll(target);
            current.forEach((key, value) -> {
                if (!REF_KEY.equals(key)) {
                    merged.put(key, value);
                }
            });
            current = merged;
        }
        // 引用环/超长链：按上限截断，剩余关键字照常生效
        return current;
    }

    /**
     * 解析文档内 JSON 指针（{@code #} 或 {@code #/a/b}）到 Schema 子节点。
     * <p>支持 {@code $defs}/{@code definitions} 等任意嵌套路径与数组索引；
     * 段转义 {@code ~1→/}、{@code ~0→~}；仅支持当前文档内指针，外部 URI 返回 null。</p>
     *
     * @param ref        $ref 值
     * @param rootSchema Schema 根
     * @return 指针目标 Schema；非法指针或目标非对象返回 {@code null}
     */
    private static JSONObject resolveJsonPointer(final String ref, final JSONObject rootSchema) {
        if (Objects.isNull(rootSchema) || StrUtil.isBlank(ref) || !ref.startsWith(REF_DOCUMENT_PREFIX)) {
            return null;
        }
        if (REF_DOCUMENT_PREFIX.equals(ref)) {
            return rootSchema;
        }
        Object current = rootSchema;
        for (final String rawSegment : ref.substring(1).split(POINTER_SLASH)) {
            if (rawSegment.isEmpty()) {
                continue;
            }
            final String segment = rawSegment.replace(POINTER_ESCAPED_SLASH, POINTER_SLASH)
                    .replace(POINTER_ESCAPED_TILDE, POINTER_TILDE);
            if (current instanceof final JSONObject obj) {
                current = obj.get(segment);
            } else if (current instanceof final JSONArray arr) {
                try {
                    final int index = Integer.parseInt(segment);
                    // 越界/负索引按无法解析处理（返回 null 报失败项），不抛异常中断校验
                    if (index < 0 || index >= arr.size()) {
                        return null;
                    }
                    current = arr.get(index);
                } catch (final NumberFormatException ignored) {
                    return null;
                }
            } else {
                return null;
            }
            if (Objects.isNull(current)) {
                return null;
            }
        }
        return current instanceof final JSONObject result ? result : null;
    }

    /**
     * 校验条件约束（if/then/else，2020-12 语义）。
     * <p>{@code if} 子 schema 匹配时应用 {@code then}，否则应用 {@code else}；
     * 无 {@code if} 时 {@code then}/{@code else} 被忽略（标准语义）。
     * 分支校验独立计数（不污染主计数），失败项并入主列表。</p>
     *
     * @param value      实际值
     * @param schemaNode Schema 节点
     * @param rootSchema Schema 根
     * @param path       路径
     * @param issues     失败项收集器
     */
    private static void validateConditionals(final Object value, final JSONObject schemaNode, final JSONObject rootSchema,
                                             final String path, final List<ValidationIssue> issues) {
        final Object ifSchema = schemaNode.get("if");
        if (ifSchema instanceof final JSONObject ifObj) {
            if (checkMatches(value, ifObj, rootSchema, path)) {
                if (schemaNode.get("then") instanceof final JSONObject thenObj) {
                    validateValue(value, thenObj, rootSchema, path, issues, new AtomicInteger());
                }
            } else if (schemaNode.get("else") instanceof final JSONObject elseObj) {
                validateValue(value, elseObj, rootSchema, path, issues, new AtomicInteger());
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
        final String format = schemaNode.getString("format");
        if (StrUtil.isNotBlank(format)) {
            validateFormat(text, format, path, issues);
        }
    }

    /**
     * 校验字符串格式约束（未知 format 不拦截，符合 2020-12 语义）。
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
     * @param rootSchema Schema 根
     * @param path       路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateObject(final JSONObject obj, final JSONObject schemaNode, final JSONObject rootSchema,
                                       final String path, final List<ValidationIssue> issues, final AtomicInteger checked) {
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
        if (Objects.nonNull(properties)) {
            for (final String key : properties.keySet()) {
                if (obj.containsKey(key) && properties.get(key) instanceof final JSONObject childSchema) {
                    validateValue(obj.get(key), childSchema, rootSchema, "%s.%s".formatted(path, key), issues, checked);
                }
            }
        }
        // patternProperties：动态键名校验（键匹配正则 → 对应子 schema）
        final JSONObject patternProperties = schemaNode.getJSONObject("patternProperties");
        if (Objects.nonNull(patternProperties)) {
            for (final String pattern : patternProperties.keySet()) {
                final Pattern compiled = compilePattern(pattern);
                if (Objects.isNull(compiled)) {
                    continue;
                }
                final Object childSchema = patternProperties.get(pattern);
                if (childSchema instanceof final JSONObject sub) {
                    for (final String key : obj.keySet()) {
                        if (compiled.matcher(key).matches()) {
                            validateValue(obj.get(key), sub, rootSchema, "%s.%s".formatted(path, key), issues, checked);
                        }
                    }
                }
            }
        }
        // additionalProperties：false 拒绝未知字段；schema 形式对未知字段按子 schema 校验
        final Object additionalProperties = schemaNode.get("additionalProperties");
        if (Objects.nonNull(additionalProperties)) {
            final Set<String> known = new HashSet<>();
            if (Objects.nonNull(properties)) {
                known.addAll(properties.keySet());
            }
            if (Objects.nonNull(patternProperties)) {
                for (final String pattern : patternProperties.keySet()) {
                    final Pattern compiled = compilePattern(pattern);
                    if (Objects.isNull(compiled)) {
                        continue;
                    }
                    for (final String key : obj.keySet()) {
                        if (compiled.matcher(key).matches()) {
                            known.add(key);
                        }
                    }
                }
            }
            if (additionalProperties instanceof final JSONObject additionalSchema) {
                for (final String key : obj.keySet()) {
                    if (!known.contains(key)) {
                        validateValue(obj.get(key), additionalSchema, rootSchema, "%s.%s".formatted(path, key), issues, checked);
                    }
                }
            } else if (Boolean.FALSE.equals(additionalProperties)) {
                for (final String key : obj.keySet()) {
                    if (!known.contains(key)) {
                        issues.add(new ValidationIssue(path, "未声明字段", key, "additionalProperties 禁止未知字段"));
                    }
                }
            }
        }
        // propertyNames：所有键名均需匹配子 schema（键名作为字符串值独立校验）
        final Object propertyNames = schemaNode.get("propertyNames");
        if (propertyNames instanceof final JSONObject namesSchema) {
            for (final String key : obj.keySet()) {
                if (!checkMatches(key, namesSchema, rootSchema, path)) {
                    issues.add(new ValidationIssue(path, "键名满足 propertyNames 约束", key, "键名不满足 propertyNames"));
                }
            }
        }
        // dependentRequired：存在触发键时联动必填其他键（2020-12 语义）
        final JSONObject dependentRequired = schemaNode.getJSONObject("dependentRequired");
        if (Objects.nonNull(dependentRequired)) {
            for (final String trigger : dependentRequired.keySet()) {
                if (!obj.containsKey(trigger)) {
                    continue;
                }
                final JSONArray requires = dependentRequired.getJSONArray(trigger);
                if (Objects.isNull(requires)) {
                    continue;
                }
                for (final Object item : requires) {
                    final String requiredKey = Convert.toStr(item);
                    if (!obj.containsKey(requiredKey)) {
                        issues.add(new ValidationIssue(path,
                                "存在 %s 时必填 %s".formatted(trigger, requiredKey), "缺失", "dependentRequired 约束不满足"));
                    }
                }
            }
        }
        // dependentSchemas：存在触发键时整个对象需满足对应子 schema（独立校验避免递归，与 not 一致）
        final JSONObject dependentSchemas = schemaNode.getJSONObject("dependentSchemas");
        if (Objects.nonNull(dependentSchemas)) {
            for (final String trigger : dependentSchemas.keySet()) {
                if (obj.containsKey(trigger) && dependentSchemas.get(trigger) instanceof final JSONObject depSchema
                        && !checkMatches(obj, depSchema, rootSchema, path)) {
                    issues.add(new ValidationIssue(path,
                            "存在 %s 时满足依赖 schema".formatted(trigger), "不满足", "dependentSchemas 约束不满足"));
                }
            }
        }
    }

    /**
     * 编译 Schema 中的正则（非法正则返回 null，不中断校验）。
     *
     * @param pattern 正则文本
     * @return 编译后的正则；非法时返回 {@code null}
     */
    private static Pattern compilePattern(final String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (final PatternSyntaxException ignored) {
            return null;
        }
    }

    /**
     * 校验数组：长度约束 + 元素唯一 + 元组校验 + 元素递归。
     *
     * @param arr        数组
     * @param schemaNode Schema 节点
     * @param rootSchema Schema 根
     * @param path       路径
     * @param issues     失败项收集器
     * @param checked    已校验计数
     */
    private static void validateArray(final JSONArray arr, final JSONObject schemaNode, final JSONObject rootSchema,
                                      final String path, final List<ValidationIssue> issues, final AtomicInteger checked) {
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
        // prefixItems：2020-12 元组校验（前 N 个位置对应子 schema）
        final JSONArray prefixItems = schemaNode.getJSONArray("prefixItems");
        if (Objects.nonNull(prefixItems)) {
            for (int index = 0; index < Math.min(arr.size(), prefixItems.size()); index++) {
                final Object itemSchema = prefixItems.get(index);
                if (itemSchema instanceof final JSONObject sub) {
                    validateValue(arr.get(index), sub, rootSchema, "%s[%d]".formatted(path, index), issues, checked);
                }
            }
        }
        // items：其余元素按元素 schema 校验
        if (items instanceof final JSONObject itemSchema) {
            final int start = Objects.nonNull(prefixItems) ? prefixItems.size() : 0;
            for (int index = start; index < arr.size(); index++) {
                validateValue(arr.get(index), itemSchema, rootSchema, "%s[%d]".formatted(path, index), issues, checked);
            }
        }
        // contains/minContains/maxContains：2020-12 包含约束（minContains 缺省 1，0 时允许零匹配）
        final Object contains = schemaNode.get("contains");
        if (contains instanceof final JSONObject containsSchema) {
            final long matched = countContainsMatches(arr, containsSchema, rootSchema, path);
            final int minContains = Objects.requireNonNullElse(schemaNode.getInteger("minContains"), 1);
            final int effectiveMin = minContains == 0 ? 0 : Math.max(minContains, 1);
            if (matched < effectiveMin) {
                issues.add(new ValidationIssue(path, "至少 %d 个元素匹配 contains".formatted(effectiveMin),
                        "匹配 " + matched + " 个", "contains 约束不满足"));
            }
            final Integer maxContains = schemaNode.getInteger("maxContains");
            if (Objects.nonNull(maxContains) && matched > maxContains) {
                issues.add(new ValidationIssue(path, "至多 %d 个元素匹配 contains".formatted(maxContains),
                        "匹配 " + matched + " 个", "maxContains 约束不满足"));
            }
        }
    }

    /**
     * 统计数组元素中匹配 contains 子 schema 的数量（独立校验，不污染主失败项与计数）。
     *
     * @param arr        数组
     * @param schema     子 schema
     * @param rootSchema Schema 根
     * @param path       路径
     * @return 匹配元素数量
     */
    private static long countContainsMatches(final JSONArray arr, final JSONObject schema, final JSONObject rootSchema,
                                             final String path) {
        return arr.stream()
                .filter(element -> checkMatches(element, schema, rootSchema, path))
                .count();
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
    private static void validateCombinators(final Object value, final JSONObject schemaNode, final JSONObject rootSchema,
                                            final String path, final List<ValidationIssue> issues) {
        final JSONArray oneOf = schemaNode.getJSONArray("oneOf");
        if (Objects.nonNull(oneOf)) {
            final long matched = countMatches(value, oneOf, rootSchema, path);
            if (matched != 1) {
                issues.add(new ValidationIssue(path, "恰好匹配一个子约束", "匹配 " + matched + " 个", "oneOf 约束不满足"));
            }
        }
        final JSONArray anyOf = schemaNode.getJSONArray("anyOf");
        if (Objects.nonNull(anyOf) && countMatches(value, anyOf, rootSchema, path) == 0) {
            issues.add(new ValidationIssue(path, "至少匹配一个子约束", "匹配 0 个", "anyOf 约束不满足"));
        }
        final JSONArray allOf = schemaNode.getJSONArray("allOf");
        if (Objects.nonNull(allOf)) {
            final long matched = countMatches(value, allOf, rootSchema, path);
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
     * @param rootSchema Schema 根
     * @param path       路径
     * @return 匹配数量
     */
    private static long countMatches(final Object value, final JSONArray schemas, final JSONObject rootSchema, final String path) {
        return schemas.stream()
                .filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .filter(subSchema -> checkMatches(value, subSchema, rootSchema, path))
                .count();
    }

    /**
     * 值是否匹配单个子约束（独立校验，不污染主失败项与计数）。
     *
     * @param value      实际值
     * @param schema     子约束
     * @param rootSchema Schema 根
     * @param path       路径
     * @return 是否匹配
     */
    private static boolean checkMatches(final Object value, final JSONObject schema, final JSONObject rootSchema, final String path) {
        final List<ValidationIssue> temp = new ArrayList<>();
        validateValue(value, schema, rootSchema, path, temp, new AtomicInteger());
        return temp.isEmpty();
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
