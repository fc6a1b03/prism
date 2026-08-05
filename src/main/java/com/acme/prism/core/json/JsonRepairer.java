package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 修复器：自动修复损坏的 JSON（单引号、缺逗号、尾逗号、注释、JSONP 包装、
 * 未加引号的键、非标准字面量等），输出修复后的 JSON、修复点日志与置信度。
 *
 * <p>修复采用分层管道：BOM 剥离 → JSONP 剥离 → 单引号规范化 → 裸键补引号 →
 * 注释剥离 → 字符串提取保护 → 骨架修复（逗号/字面量）→ 字符串还原 → 合法性验证。
 * 字符串内容在修复期间被占位符保护，避免正则误伤值内容。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonRepairer implements JsonOperation {

    /**
     * 修复输入长度上限（字符），对齐既有 1MB 大文件护栏
     */
    private static final int MAX_REPAIR_CHARS = 1024 * 1024;
    /**
     * BOM 字符
     */
    private static final String BOM = "\uFEFF";
    /**
     * 字符串占位符前缀
     */
    private static final String PLACEHOLDER_PREFIX = "__PRISM_STR_";
    /**
     * 字符串占位符后缀
     */
    private static final String PLACEHOLDER_SUFFIX = "__";
    /**
     * JSONP 包装模式：标识符 + 左括号
     */
    private static final Pattern JSONP_PATTERN = Pattern.compile("^[A-Za-z_$][\\w$]*\\s*\\(");
    /**
     * 尾逗号模式：逗号后紧跟闭合括号
     */
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*(}|])");
    /**
     * 缺逗号模式：闭合括号/键引号后紧跟新的键值对开头
     * （键提取为占位符后以 {@code __PRISM_STR_n__} 形式出现，同样需要识别）
     */
    private static final Pattern MISSING_COMMA = Pattern.compile("(}|]|\")(\\s*)(\\{|\\[|\"|__PRISM_STR_\\d+__)");
    /**
     * 非标准字面量模式
     */
    private static final Pattern LITERAL = Pattern.compile("\\b(undefined|None|True|False|NaN|Infinity)\\b");

    /**
     * 修复类型与对应置信度扣减值、i18n 键。
     */
    public enum FixType {
        /**
         * BOM 剥离
         */
        BOM("json.repair.fix.bom", 0.05),
        /**
         * JSONP 包装剥离
         */
        JSONP("json.repair.fix.jsonp", 0.15),
        /**
         * 单引号转双引号
         */
        SINGLE_QUOTE("json.repair.fix.single.quote", 0.15),
        /**
         * 未加引号的键补引号
         */
        UNQUOTED_KEY("json.repair.fix.unquoted.key", 0.15),
        /**
         * 注释剥离
         */
        COMMENT("json.repair.fix.comment", 0.15),
        /**
         * 补齐缺失逗号
         */
        MISSING_COMMA("json.repair.fix.missing.comma", 0.15),
        /**
         * 移除尾逗号
         */
        TRAILING_COMMA("json.repair.fix.trailing.comma", 0.05),
        /**
         * 非标准字面量规范化
         */
        LITERAL("json.repair.fix.literal", 0.05);

        /**
         * 修复类型 i18n 键
         */
        private final String i18nKey;
        /**
         * 置信度扣减值
         */
        private final double penalty;

        /**
         * 构造修复类型
         *
         * @param i18nKey i18n 键
         * @param penalty 置信度扣减值
         */
        FixType(final String i18nKey, final double penalty) {
            this.i18nKey = i18nKey;
            this.penalty = penalty;
        }

        /**
         * 获取 i18n 键
         *
         * @return {@link String }
         */
        public String i18nKey() {
            return this.i18nKey;
        }

        /**
         * 获取置信度扣减值
         *
         * @return double
         */
        public double penalty() {
            return this.penalty;
        }
    }

    /**
     * 修复结果。
     *
     * @param json       修复后的 JSON 文本
     * @param fixes      修复类型列表（去重后）
     * @param confidence 置信度（1.0 表示无需修复，越低表示改动越多）
     */
    public record RepairResult(String json, List<FixType> fixes, double confidence) {
    }

    /**
     * 修复损坏的 JSON。
     *
     * @param input 原始输入
     * @return 修复结果；输入为空、超过护栏或无法修复时返回 {@code null}
     */
    public static RepairResult repair(final String input) {
        if (Objects.isNull(input) || StrUtil.isBlank(input) || input.length() > MAX_REPAIR_CHARS) {
            return null;
        }
        final List<FixType> fixes = new ArrayList<>(4);
        // 1. 剥离 BOM
        String text = input;
        if (text.startsWith(BOM)) {
            text = text.substring(1);
            fixes.add(FixType.BOM);
        }
        // 2. 剥离 JSONP 包装
        final String stripped = stripJsonp(text);
        if (Objects.nonNull(stripped)) {
            text = stripped;
            fixes.add(FixType.JSONP);
        }
        // 3. 单引号转双引号（规范化后字符串统一为双引号）
        text = normalizeSingleQuotes(text, fixes);
        // 4. 未加引号的键补引号
        text = fixUnquotedKeys(text, fixes);
        // 5. 剥离注释
        text = stripComments(text, fixes);
        // 6. 提取字符串为占位符，保护值内容
        final StringExtraction extraction = extractStrings(text);
        String skeleton = extraction.skeleton();
        // 7. 骨架修复（骨架无字符串内容，正则安全）
        final String beforeTrailing = skeleton;
        skeleton = TRAILING_COMMA.matcher(skeleton).replaceAll("$1");
        if (!skeleton.equals(beforeTrailing)) {
            fixes.add(FixType.TRAILING_COMMA);
        }
        final String beforeMissing = skeleton;
        skeleton = MISSING_COMMA.matcher(skeleton).replaceAll("$1,$2$3");
        if (!skeleton.equals(beforeMissing)) {
            fixes.add(FixType.MISSING_COMMA);
        }
        final String beforeLiteral = skeleton;
        skeleton = LITERAL.matcher(skeleton).replaceAll(JsonRepairer::normalizeLiteral);
        if (!skeleton.equals(beforeLiteral)) {
            fixes.add(FixType.LITERAL);
        }
        // 8. 还原字符串
        final String restored = restoreStrings(skeleton, extraction.strings());
        // 9. 合法性验证，失败不返回修复结果（绝不写回非法内容）
        if (!JSON.isValid(restored)) {
            return null;
        }
        return new RepairResult(restored, fixes.stream().distinct().toList(), confidence(fixes));
    }

    /**
     * JSON 操作契约：返回修复后的文本；无法修复时原样返回。
     *
     * @param json 输入
     * @return 修复后的 JSON
     */
    @Override
    public String process(final String json) {
        final RepairResult result = repair(json);
        return Objects.isNull(result) ? json : result.json();
    }

    /**
     * 计算置信度：1.0 起按修复类型扣减，下限 0.1。
     *
     * @param fixes 修复类型列表
     * @return double
     */
    private static double confidence(final List<FixType> fixes) {
        if (fixes.isEmpty()) {
            return 1.0d;
        }
        double value = 1.0d;
        for (final FixType fix : fixes) {
            value -= fix.penalty();
        }
        return Math.max(value, 0.1d);
    }

    /**
     * 剥离 JSONP 包装（{@code callback({...})} → 括号内容），字符串扫描转义感知。
     *
     * @param text 输入文本
     * @return 括号内容；未匹配 JSONP 包装时返回 {@code null}
     */
    private static String stripJsonp(final String text) {
        final String trimmed = text.stripLeading();
        final Matcher matcher = JSONP_PATTERN.matcher(trimmed);
        if (!matcher.find()) {
            return null;
        }
        final int openIndex = matcher.end() - 1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (inString) {
                // 转义序列（含 \"）不触发字符串结束
                if (c == '\\' && i + 1 < trimmed.length()) {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(openIndex + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * 单引号字符串转双引号字符串。
     *
     * @param text  输入文本
     * @param fixes 修复日志
     * @return 规范化后的文本
     */
    private static String normalizeSingleQuotes(final String text, final List<FixType> fixes) {
        if (text.indexOf('\'') < 0) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean fixed = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            // 转义序列：单引号字符串内的 \' 与 \" 需按双引号语境改写
            if (c == '\\' && i + 1 < text.length() && (inSingle || inDouble)) {
                final char next = text.charAt(i + 1);
                if (inSingle && next == '\'') {
                    sb.append('\'');
                    i++;
                } else if (inSingle && next == '"') {
                    sb.append("\\\"");
                    i++;
                } else {
                    sb.append(c);
                }
            } else if (c == '\'' && !inDouble) {
                // 引号外的单引号：转为双引号并切换单引号字符串状态
                sb.append('"');
                inSingle = !inSingle;
                fixed = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                sb.append(c);
            } else if (c == '"' && inSingle) {
                // 单引号字符串内的双引号需转义
                sb.append("\\\"");
                fixed = true;
            } else {
                sb.append(c);
            }
        }
        if (fixed) {
            fixes.add(FixType.SINGLE_QUOTE);
        }
        return sb.toString();
    }

    /**
     * 为对象键补引号（{@code {a:1}} → {@code {"a":1}}），引号感知避免误伤字符串内容。
     *
     * @param text  输入文本
     * @param fixes 修复日志
     * @return 修复后的文本
     */
    private static String fixUnquotedKeys(final String text, final List<FixType> fixes) {
        final StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        // 是否为期待对象键的位置（紧邻 { 或 , 之后且不在字符串内）
        boolean atKeyPosition = false;
        boolean fixed = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                atKeyPosition = false;
                sb.append(c);
                continue;
            }
            if (c == '{' || c == ',') {
                atKeyPosition = true;
                sb.append(c);
                continue;
            }
            // 期待键的位置跳过空白（保持待键状态，避免 `, b` 场景漏修）
            if (atKeyPosition && Character.isWhitespace(c)) {
                sb.append(c);
                continue;
            }
            if (atKeyPosition && (Character.isLetter(c) || c == '_' || c == '$')) {
                final int start = i;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
                        || text.charAt(i) == '_' || text.charAt(i) == '$'
                        || text.charAt(i) == '-' || text.charAt(i) == '.')) {
                    i++;
                }
                int colonIndex = i;
                while (colonIndex < text.length() && Character.isWhitespace(text.charAt(colonIndex))) {
                    colonIndex++;
                }
                if (colonIndex < text.length() && text.charAt(colonIndex) == ':') {
                    sb.append('"').append(text, start, i).append('"').append(text, i, colonIndex + 1);
                    i = colonIndex;
                    fixed = true;
                } else {
                    sb.append(text, start, i);
                    i--;
                }
                atKeyPosition = false;
                continue;
            }
            atKeyPosition = false;
            sb.append(c);
        }
        if (fixed) {
            fixes.add(FixType.UNQUOTED_KEY);
        }
        return sb.toString();
    }

    /**
     * 剥离注释（行注释 {@code //}、块注释 {@code /* *}{@code /}、哈希注释 {@code #}），引号感知。
     *
     * @param text  输入文本
     * @param fixes 修复日志
     * @return 剥离注释后的文本
     */
    private static String stripComments(final String text, final List<FixType> fixes) {
        final StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        boolean fixed = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                fixed = true;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                fixed = true;
            } else if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                fixed = true;
            } else {
                sb.append(c);
            }
        }
        if (fixed) {
            fixes.add(FixType.COMMENT);
        }
        return sb.toString();
    }

    /**
     * 字符串提取结果。
     *
     * @param skeleton 替换占位符后的骨架
     * @param strings  提取的字符串（按占位符序号对应）
     */
    private record StringExtraction(String skeleton, List<String> strings) {
    }

    /**
     * 提取字符串为占位符。
     *
     * @param text 输入文本
     * @return 骨架与字符串列表
     */
    private static StringExtraction extractStrings(final String text) {
        final StringBuilder skeleton = new StringBuilder(text.length());
        final List<String> strings = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(c).append(text.charAt(i + 1));
                    i++;
                } else if (c == '"') {
                    current.append(c);
                    strings.add(current.toString());
                    current.setLength(0);
                    skeleton.append(PLACEHOLDER_PREFIX).append(strings.size() - 1).append(PLACEHOLDER_SUFFIX);
                    inString = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inString = true;
                current.append(c);
            } else {
                skeleton.append(c);
            }
        }
        // 未闭合的字符串：按字符串处理，保证骨架合法可修复
        if (inString) {
            strings.add(current.toString());
            skeleton.append(PLACEHOLDER_PREFIX).append(strings.size() - 1).append(PLACEHOLDER_SUFFIX);
        }
        return new StringExtraction(skeleton.toString(), strings);
    }

    /**
     * 还原占位符为字符串内容。
     *
     * @param skeleton 骨架
     * @param strings  字符串列表
     * @return 还原后的文本
     */
    private static String restoreStrings(final String skeleton, final List<String> strings) {
        if (strings.isEmpty()) {
            return skeleton;
        }
        final StringBuilder sb = new StringBuilder(skeleton.length());
        int cursor = 0;
        for (int i = 0; i < strings.size(); i++) {
            final String placeholder = PLACEHOLDER_PREFIX + i + PLACEHOLDER_SUFFIX;
            final int index = skeleton.indexOf(placeholder, cursor);
            if (index < 0) {
                break;
            }
            sb.append(skeleton, cursor, index).append(strings.get(i));
            cursor = index + placeholder.length();
        }
        sb.append(skeleton, cursor, skeleton.length());
        return sb.toString();
    }

    /**
     * 字面量规范化替换。
     *
     * @param match 匹配结果
     * @return 规范化后的字面量
     */
    private static String normalizeLiteral(final MatchResult match) {
        return switch (match.group(1)) {
            case "undefined", "None", "NaN", "Infinity" -> "null";
            case "True" -> "true";
            case "False" -> "false";
            default -> match.group(0);
        };
    }
}
