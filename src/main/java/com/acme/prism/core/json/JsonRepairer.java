package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 修复器：自动修复损坏的 JSON（单引号、缺逗号、尾逗号、注释、JSONP 包装、
 * 未加引号的键、非标准字面量等），输出修复后的 JSON、修复点日志与置信度。
 *
 * <p>修复采用分层管道：BOM 剥离 → NDJSON 检测组装 → Markdown 代码块剥离 → JSONP 剥离 →
 * 日志前缀剥离 → 特殊引号规范化 → 单引号转双引号 → 裸键补引号 → 注释剥离 →
 * 字符串提取保护 → MongoDB 包装剥离 → 骨架修复（逗号/字面量/闭合括号）→
 * 字符串还原 → 合法性验证。字符串内容在修复期间被占位符保护，避免正则误伤值内容。</p>
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
     * Markdown 代码块标记
     */
    private static final String FENCE_MARKER = "```";
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
     * 值间缺逗号模式：值边界（数字/字面量/占位符）后紧跟新值或键开头（需空白分隔）。
     * <p>内部分组必须用非捕获组 {@code (?:...)}，避免替换串 {@code $n} 引用错位。</p>
     */
    private static final Pattern VALUE_MISSING_COMMA = Pattern.compile(
            "(__PRISM_STR_\\d+__|\\d+(?:\\.\\d+)?|true|false|null)(\\s+)(__PRISM_STR_\\d+__|\\{|\\[|\"|\\d+(?:\\.\\d+)?|true|false|null)");
    /**
     * 非标准字面量模式
     */
    private static final Pattern LITERAL = Pattern.compile("\\b(undefined|None|True|False|NaN|Infinity)\\b");
    /**
     * MongoDB 扩展 JSON 包装模式（骨架层匹配：字符串内容已占位化，仅结构位置生效）。
     * <p>分组 1-4 为数字包装（直接取数字），5-10 为字符串包装（还原占位符），
     * MinKey/MaxKey 整体匹配转 null。交替分支用非捕获组，分组编号与
     * {@link #normalizeMongoWrapper} 对应。</p>
     */
    private static final Pattern MONGODB_WRAPPER = Pattern.compile(
            "\\bNumberLong\\s*\\(\\s*(-?\\d+)\\s*\\)" +
            "|\\bNumberInt\\s*\\(\\s*(-?\\d+)\\s*\\)" +
            "|\\bTimestamp\\s*\\(\\s*(-?\\d+)\\s*,\\s*-?\\d+\\s*\\)" +
            "|\\bnew\\s+Date\\s*\\(\\s*(-?\\d+)\\s*\\)" +
            "|\\bISODate\\s*\\(\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bObjectId\\s*\\(\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bUUID\\s*\\(\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bNumberDecimal\\s*\\(\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bBinData\\s*\\(\\s*\\d+\\s*,\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bnew\\s+Date\\s*\\(\\s*(__PRISM_STR_\\d+__)\\s*\\)" +
            "|\\bMinKey\\b|\\bMaxKey\\b");
    /**
     * MongoDB 包装特征签名（原始文本检测用：精确词边界匹配）。
     * <p>含 {@code new Date}：误报代价为零——普通代码文本原就被 URL 参数等检测
     * 误转或转不出 JSON，短路后行为只会更稳定，故不排除。</p>
     */
    private static final Pattern MONGODB_SIGNATURE = Pattern.compile(
            "\\b(?:NumberLong|NumberInt|Timestamp|ISODate|ObjectId|UUID|NumberDecimal|BinData|MinKey|MaxKey)\\b|\\bnew\\s+Date\\b");
    /**
     * NDJSON 组装缩进前缀
     */
    private static final String NDJSON_INDENT = "\n  ";
    /**
     * NDJSON 数组闭合行
     */
    private static final String NDJSON_CLOSE = "\n]";
    /**
     * NDJSON 数组元素分隔（逗号 + 缩进）
     */
    private static final String NDJSON_SEPARATOR = ",\n  ";
    /**
     * NDJSON 数组开括号
     */
    private static final String NDJSON_OPEN = "[";

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
         * Markdown 代码块剥离
         */
        FENCE("json.repair.fix.fence", 0.10),
        /**
         * 日志前缀剥离
         */
        LOG_PREFIX("json.repair.fix.log.prefix", 0.10),
        /**
         * 特殊引号转换
         */
        SPECIAL_QUOTE("json.repair.fix.special.quote", 0.05),
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
         * 补齐缺失闭合括号（截断 JSON）
         */
        MISSING_BRACKET("json.repair.fix.missing.bracket", 0.10),
        /**
         * 非标准字面量规范化
         */
        LITERAL("json.repair.fix.literal", 0.05),
        /**
         * NDJSON 流组装为 JSON 数组
         */
        NDJSON("json.repair.fix.ndjson", 0.10),
        /**
         * MongoDB 扩展 JSON 包装剥离
         */
        MONGODB("json.repair.fix.mongodb", 0.10);

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
        // 2. NDJSON 检测：整体非法但每行均为合法 JSON 时，组装为数组（短路返回）
        if (!JSON.isValid(text)) {
            final String ndjson = toNdjsonArray(text);
            if (Objects.nonNull(ndjson)) {
                fixes.add(FixType.NDJSON);
                return new RepairResult(ndjson, fixes.stream().distinct().toList(), confidence(fixes));
            }
        }
        // 3. 剥离 Markdown 代码块（```json ... ```）
        final String fenced = stripFencedCodeBlock(text);
        if (Objects.nonNull(fenced)) {
            text = fenced;
            fixes.add(FixType.FENCE);
        }
        // 4. 剥离 JSONP 包装
        final String stripped = stripJsonp(text);
        if (Objects.nonNull(stripped)) {
            text = stripped;
            fixes.add(FixType.JSONP);
        }
        // 5. 剥离日志前缀（INFO: {...} / 时间戳前缀）
        final String logStripped = stripLogPrefix(text);
        if (Objects.nonNull(logStripped)) {
            text = logStripped;
            fixes.add(FixType.LOG_PREFIX);
        }
        // 6. 特殊引号转标准引号（“ ” ‘ ’ → " '）
        text = normalizeSpecialQuotes(text, fixes);
        // 7. 单引号转双引号（规范化后字符串统一为双引号）
        text = normalizeSingleQuotes(text, fixes);
        // 8. 未加引号的键补引号
        text = fixUnquotedKeys(text, fixes);
        // 9. 剥离注释
        text = stripComments(text, fixes);
        // 10. 提取字符串为占位符，保护值内容
        final StringExtraction extraction = extractStrings(text);
        String skeleton = extraction.skeleton();
        // 10.5 MongoDB 扩展 JSON 包装剥离（骨架层：字符串值内容已占位化，不会误伤）
        final String beforeMongo = skeleton;
        skeleton = MONGODB_WRAPPER.matcher(skeleton).replaceAll(JsonRepairer::normalizeMongoWrapper);
        if (!skeleton.equals(beforeMongo)) {
            fixes.add(FixType.MONGODB);
        }
        // 11. 骨架修复（骨架无字符串内容，正则安全）
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
        // 值间缺逗号：循环应用直至稳定（连续缺逗号如 [1 2 3] 需多轮补齐）
        final String beforeValueComma = skeleton;
        while (true) {
            final String next = VALUE_MISSING_COMMA.matcher(skeleton).replaceAll("$1,$2$3");
            if (next.equals(skeleton)) {
                break;
            }
            skeleton = next;
        }
        if (!skeleton.equals(beforeValueComma)) {
            fixes.add(FixType.MISSING_COMMA);
        }
        final String beforeLiteral = skeleton;
        skeleton = LITERAL.matcher(skeleton).replaceAll(JsonRepairer::normalizeLiteral);
        if (!skeleton.equals(beforeLiteral)) {
            fixes.add(FixType.LITERAL);
        }
        // 12. 补齐缺失闭合括号（截断 JSON 场景）
        final String beforeBracket = skeleton;
        skeleton = closeBrackets(skeleton);
        if (!skeleton.equals(beforeBracket)) {
            fixes.add(FixType.MISSING_BRACKET);
        }
        // 13. 还原字符串
        final String restored = restoreStrings(skeleton, extraction.strings());
        // 14. 合法性验证，失败不返回修复结果（绝不写回非法内容）
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
     * 检测文本是否含 MongoDB 扩展 JSON 包装特征（精确词边界，供格式自动识别前置判断，
     * 避免被 YAML 检测抢先消费）。含 {@code new Date}：普通代码文本原就被 URL 参数等
     * 检测误转或转不出 JSON，短路后行为只会更稳定，无破坏面。
     *
     * @param text 输入文本
     * @return 是否含 MongoDB 包装特征
     */
    public static boolean containsMongoWrapper(final String text) {
        return StrUtil.isNotBlank(text) && MONGODB_SIGNATURE.matcher(text).find();
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
     * NDJSON 流组装为 JSON 数组：整体非法但每行（非空白）均为合法 JSON 时生效。
     * <p>至少两行合法 JSON 才算 NDJSON 流（单行合法 JSON 直接走 {@link JSON#isValid(String)} 短路，
     * 不会进入本方法）；任一行非法即放弃，返回 {@code null} 走常规修复管道。</p>
     *
     * @param text 输入文本
     * @return 组装后的 JSON 数组；非 NDJSON 流返回 {@code null}
     */
    private static String toNdjsonArray(final String text) {
        final List<String> jsonLines = new ArrayList<>();
        for (final String line : text.split("\\R")) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            final String stripped = line.strip();
            if (!JSON.isValid(stripped)) {
                return null;
            }
            jsonLines.add(stripped);
        }
        if (jsonLines.size() < 2) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(text.length() + 16);
        sb.append(NDJSON_OPEN);
        for (int i = 0; i < jsonLines.size(); i++) {
            sb.append(i > 0 ? NDJSON_SEPARATOR : NDJSON_INDENT).append(jsonLines.get(i));
        }
        sb.append(NDJSON_CLOSE);
        return sb.toString();
    }

    /**
     * 剥离 Markdown 代码块（{@code ```json ... ```} 或 {@code ``` ... ```}）。
     *
     * @param text 输入文本
     * @return 代码块内容；非代码块输入返回 {@code null}
     */
    private static String stripFencedCodeBlock(final String text) {
        final String trimmed = text.stripLeading();
        if (!trimmed.startsWith(FENCE_MARKER)) {
            return null;
        }
        final int newline = trimmed.indexOf('\n');
        final String body = newline >= 0 ? trimmed.substring(newline + 1) : "";
        final String cleaned = stripTrailingFence(body).strip();
        return JSON.isValid(cleaned) ? cleaned : null;
    }

    /**
     * 剥离尾部代码块标记。
     *
     * @param body 代码块内容
     * @return 剥离尾部标记后的内容
     */
    private static String stripTrailingFence(final String body) {
        final int fenceIndex = body.lastIndexOf(FENCE_MARKER);
        return fenceIndex >= 0 ? body.substring(0, fenceIndex) : body;
    }

    /**
     * 剥离日志前缀（{@code INFO: {...}}、时间戳前缀等）。
     * <p>取首个 {@code {} 或 {@code [} 之后的内容，仅当前缀不含引号（非 JSON 内容）时剥离。</p>
     *
     * @param text 输入文本
     * @return 剥离前缀后的内容；非日志输入返回 {@code null}
     */
    private static String stripLogPrefix(final String text) {
        final int structureIndex = firstStructureIndex(text);
        if (structureIndex <= 0) {
            return null;
        }
        // 前缀含引号说明是 JSON 内容的一部分（如字符串值），非纯日志前缀
        if (text.substring(0, structureIndex).indexOf('"') >= 0) {
            return null;
        }
        final String candidate = text.substring(structureIndex);
        return JSON.isValid(candidate) ? candidate : null;
    }

    /**
     * 定位首个 JSON 结构字符（{@code {} 或 {@code [}）。
     *
     * @param text 输入文本
     * @return 结构字符索引；不存在返回 -1
     */
    private static int firstStructureIndex(final String text) {
        final int brace = text.indexOf('{');
        final int bracket = text.indexOf('[');
        if (brace < 0) {
            return bracket;
        }
        return bracket < 0 ? brace : Math.min(brace, bracket);
    }

    /**
     * 特殊引号转标准引号（弯引号 {@code “ ” ‘ ’} → 直引号 {@code " '}）。
     *
     * @param text  输入文本
     * @param fixes 修复日志
     * @return 规范化后的文本
     */
    private static String normalizeSpecialQuotes(final String text, final List<FixType> fixes) {
        if (text.indexOf('“') < 0 && text.indexOf('”') < 0 && text.indexOf('‘') < 0 && text.indexOf('’') < 0) {
            return text;
        }
        fixes.add(FixType.SPECIAL_QUOTE);
        return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
    }

    /**
     * 补齐缺失的闭合括号（截断 JSON 场景，如 {@code {"a":1} → {"a":1}}）。
     *
     * @param skeleton 骨架（字符串已占位化，无引号干扰）
     * @return 补齐闭合括号后的骨架；括号已闭合时原样返回
     */
    private static String closeBrackets(final String skeleton) {
        final Deque<Character> stack = new ArrayDeque<>();
        for (int i = 0; i < skeleton.length(); i++) {
            final char c = skeleton.charAt(i);
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && isMatching(stack.peek(), c)) {
                    stack.pop();
                }
            }
        }
        if (stack.isEmpty()) {
            return skeleton;
        }
        final StringBuilder sb = new StringBuilder(skeleton.length() + stack.size());
        sb.append(skeleton);
        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    /**
     * 括号是否匹配。
     *
     * @param open  开括号
     * @param close 闭括号
     * @return boolean
     */
    private static boolean isMatching(final char open, final char close) {
        return open == '{' && close == '}' || open == '[' && close == ']';
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
     * MongoDB 包装规范化替换：数字包装返回数字，字符串包装返回占位符引用，
     * MinKey/MaxKey 转为 null。
     *
     * @param match 匹配结果
     * @return 规范化后的值
     */
    private static String normalizeMongoWrapper(final MatchResult match) {
        for (int group = 1; group <= 4; group++) {
            final String number = match.group(group);
            if (Objects.nonNull(number)) {
                return number;
            }
        }
        for (int group = 5; group <= 10; group++) {
            final String placeholder = match.group(group);
            if (Objects.nonNull(placeholder)) {
                return placeholder;
            }
        }
        return "null";
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
