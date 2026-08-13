package com.acme.prism.core.json;

import com.acme.prism.core.json.JsonRepairer.FixType;
import com.acme.prism.core.json.JsonRepairer.RepairResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 修复器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonRepairerTest {

    private final JsonRepairer repairer = new JsonRepairer();

    @Test
    @DisplayName("正常：尾逗号被移除")
    void removesTrailingComma() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1,}");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertTrue(result.fixes().contains(FixType.TRAILING_COMMA));
    }

    @Test
    @DisplayName("正常：单引号转双引号")
    void convertsSingleQuotes() {
        final RepairResult result = JsonRepairer.repair("{'a':'b'}");
        assertNotNull(result);
        assertEquals("{\"a\":\"b\"}", result.json());
        assertTrue(result.fixes().contains(FixType.SINGLE_QUOTE));
    }

    @Test
    @DisplayName("正常：单引号字符串内的转义单引号与双引号正确处理")
    void handlesEscapedCharsInSingleQuotes() {
        final RepairResult result = JsonRepairer.repair("{'it\\'s':'x\"y'}");
        assertNotNull(result);
        assertEquals("{\"it's\":\"x\\\"y\"}", result.json());
    }

    @Test
    @DisplayName("正常：对象内缺逗号被补齐")
    void addsMissingComma() {
        final RepairResult result = JsonRepairer.repair("{\"a\":[1] \"b\":2}");
        assertNotNull(result);
        assertEquals("{\"a\":[1], \"b\":2}", result.json());
        assertTrue(result.fixes().contains(FixType.MISSING_COMMA));
    }

    @Test
    @DisplayName("异常：顶层连续对象无法修复（诚实返回 null）")
    void returnsNullForTopLevelConcat() {
        // {"a":1}{"b":2} 补逗号后仍是顶层多值，不是合法 JSON，不应强行修复
        assertNull(JsonRepairer.repair("{\"a\":1}{\"b\":2}"));
    }

    @Test
    @DisplayName("正常：行注释与块注释被剥离")
    void stripsComments() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1, // 注释\n \"b\":2 /* 块 */}");
        assertNotNull(result);
        final JSONObject parsed = JSON.parseObject(result.json());
        assertEquals(1, parsed.getIntValue("a"));
        assertEquals(2, parsed.getIntValue("b"));
        assertTrue(result.fixes().contains(FixType.COMMENT));
    }

    @Test
    @DisplayName("正常：JSONP 包装被剥离")
    void stripsJsonpWrapper() {
        final RepairResult result = JsonRepairer.repair("callback({\"a\":1})");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertTrue(result.fixes().contains(FixType.JSONP));
    }

    @Test
    @DisplayName("正常：JSONP 内容含转义引号时正确剥离")
    void stripsJsonpWithEscapedQuotes() {
        final RepairResult result = JsonRepairer.repair("callback({\"a\":\"\\\"quoted\\\"\"})");
        assertNotNull(result);
        assertEquals("{\"a\":\"\\\"quoted\\\"\"}", result.json());
        assertTrue(result.fixes().contains(FixType.JSONP));
    }

    @Test
    @DisplayName("正常：未加引号的键被补引号")
    void quotesUnquotedKeys() {
        final RepairResult result = JsonRepairer.repair("{a:1, b:2}");
        assertNotNull(result);
        assertEquals("{\"a\":1, \"b\":2}", result.json());
        assertTrue(result.fixes().contains(FixType.UNQUOTED_KEY));
    }

    @Test
    @DisplayName("正常：非标准字面量被规范化")
    void normalizesLiterals() {
        final RepairResult result = JsonRepairer.repair("{\"a\":undefined, \"b\":True, \"c\":NaN}");
        assertNotNull(result);
        assertEquals("{\"a\":null, \"b\":true, \"c\":null}", result.json());
        assertTrue(result.fixes().contains(FixType.LITERAL));
    }

    @Test
    @DisplayName("正常：字符串内容包含逗号/括号时不被误修")
    void preservesStringContent() {
        final RepairResult result = JsonRepairer.repair("{\"a\":\"x, y} z\", \"b\":\"{q:1}\"}");
        assertNotNull(result);
        assertEquals("{\"a\":\"x, y} z\", \"b\":\"{q:1}\"}", result.json());
        assertFalse(result.fixes().contains(FixType.MISSING_COMMA));
        assertFalse(result.fixes().contains(FixType.UNQUOTED_KEY));
    }

    @Test
    @DisplayName("正常：合法 JSON 原样返回且置信度 1.0")
    void returnsValidJsonUnchanged() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1}");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertEquals(1.0d, result.confidence());
        assertTrue(result.fixes().isEmpty());
    }

    @Test
    @DisplayName("边界：空输入返回 null")
    void returnsNullForBlankInput() {
        assertNull(JsonRepairer.repair(null));
        assertNull(JsonRepairer.repair(""));
        assertNull(JsonRepairer.repair("   "));
    }

    @Test
    @DisplayName("异常：无法修复的输入返回 null")
    void returnsNullWhenUnrepairable() {
        assertNull(JsonRepairer.repair("not json at all !!!"));
    }

    @Test
    @DisplayName("验证：置信度随修复点数单调下降")
    void confidenceDecreasesWithMoreFixes() {
        final RepairResult single = JsonRepairer.repair("{a:1}");
        final RepairResult multi = JsonRepairer.repair("{a:undefined, b:2,}");
        assertNotNull(single);
        assertNotNull(multi);
        assertTrue(multi.confidence() < single.confidence(), "修复点越多置信度应越低");
    }

    @Test
    @DisplayName("契约：process 无法修复时原样返回")
    void processFallsBackToInput() {
        final String garbage = "garbage";
        assertEquals(garbage, this.repairer.process(garbage));
        assertEquals("{\"a\":1}", this.repairer.process("{\"a\":1}"));
    }

    @Test
    @DisplayName("护栏：超过 1MB 的输入拒绝修复")
    void rejectsOversizeInput() {
        final String big = "{\"a\":\"%s\"}".formatted("x".repeat(1024 * 1024));
        assertNull(JsonRepairer.repair(big));
    }

    @Test
    @DisplayName("正常：Markdown 代码块被剥离")
    void stripsFencedCodeBlock() {
        final RepairResult result = JsonRepairer.repair("```json\n{\"a\":1}\n```");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertTrue(result.fixes().contains(FixType.FENCE));
    }

    @Test
    @DisplayName("正常：日志前缀被剥离")
    void stripsLogPrefix() {
        final RepairResult result = JsonRepairer.repair("INFO: {\"a\":1}");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertTrue(result.fixes().contains(FixType.LOG_PREFIX));
    }

    @Test
    @DisplayName("正常：特殊引号转标准引号")
    void convertsSpecialQuotes() {
        final RepairResult result = JsonRepairer.repair("{\"a\":\u201Ctext\u201D}");
        assertNotNull(result);
        assertEquals("{\"a\":\"text\"}", result.json());
        assertTrue(result.fixes().contains(FixType.SPECIAL_QUOTE));
    }

    @Test
    @DisplayName("正常：值间缺失逗号被补齐（数字后键）")
    void addsCommaBetweenValueAndKey() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1 \"b\":2}");
        assertNotNull(result);
        assertTrue(result.json().contains("\"a\":1,") && result.json().contains("\"b\":2"), "逗号应补在值间");
        assertTrue(result.fixes().contains(FixType.MISSING_COMMA));
    }

    @Test
    @DisplayName("正常：值间缺失逗号被补齐（字符串值后键）")
    void addsCommaBetweenStringValueAndKey() {
        final RepairResult result = JsonRepairer.repair("{\"a\":\"x\" \"b\":1}");
        assertNotNull(result);
        assertTrue(result.json().contains("\"a\":\"x\",") && result.json().contains("\"b\":1"), "逗号应补在字符串值与键之间");
        assertTrue(result.fixes().contains(FixType.MISSING_COMMA));
    }

    @Test
    @DisplayName("正常：数组元素间缺失逗号被补齐（连续多缺）")
    void addsCommaBetweenArrayElements() {
        final RepairResult result = JsonRepairer.repair("[1 2 3]");
        assertNotNull(result);
        assertTrue(result.json().contains("[1,") && result.json().contains("2,") && result.json().contains("3]"), "连续缺逗号应全部补齐");
        assertTrue(result.fixes().contains(FixType.MISSING_COMMA));
    }

    @Test
    @DisplayName("正常：截断 JSON 补齐缺失闭合括号")
    void closesTruncatedJson() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1");
        assertNotNull(result);
        assertEquals("{\"a\":1}", result.json());
        assertTrue(result.fixes().contains(FixType.MISSING_BRACKET));
    }

    @Test
    @DisplayName("正常：截断嵌套 JSON 按序补齐括号")
    void closesNestedTruncatedJson() {
        final RepairResult result = JsonRepairer.repair("{\"a\":{\"b\":[1,2]");
        assertNotNull(result);
        assertEquals("{\"a\":{\"b\":[1,2]}}", result.json());
        assertTrue(result.fixes().contains(FixType.MISSING_BRACKET));
    }

    @Test
    @DisplayName("回归：多修复点去重后列表唯一")
    void deduplicatesFixTypes() {
        final RepairResult result = JsonRepairer.repair("{a:1, b:2,}");
        assertNotNull(result);
        final List<FixType> fixes = result.fixes();
        assertEquals(fixes.stream().distinct().toList(), fixes, "修复类型列表应去重");
    }

    @Test
    @DisplayName("正常：NDJSON 两行流组装为数组")
    void assemblesNdjsonStream() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1}\n{\"b\":2}");
        assertNotNull(result);
        final JSONArray arr = JSON.parseArray(result.json());
        assertNotNull(arr);
        assertEquals(2, arr.size());
        assertTrue(result.fixes().contains(FixType.NDJSON));
    }

    @Test
    @DisplayName("正常：NDJSON 空行被忽略，内容行全部保留")
    void ignoresBlankLinesInNdjson() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1}\n\n{\"b\":2}\n");
        assertNotNull(result);
        final JSONArray arr = JSON.parseArray(result.json());
        assertNotNull(arr);
        assertEquals(2, arr.size());
    }

    @Test
    @DisplayName("正常：NDJSON 行内嵌套数组与对象完整保留")
    void keepsNestedContentInNdjson() {
        final RepairResult result = JsonRepairer.repair("{\"a\":[1,2]}\n{\"b\":{\"c\":3}}");
        assertNotNull(result);
        final JSONArray arr = JSON.parseArray(result.json());
        assertNotNull(arr);
        assertEquals(2, arr.size());
        assertEquals(2, arr.getJSONObject(0).getJSONArray("a").size());
        assertEquals(3, arr.getJSONObject(1).getJSONObject("b").getIntValue("c"));
    }

    @Test
    @DisplayName("异常：任一行非法则不走 NDJSON（走常规管道）")
    void rejectsNdjsonWithInvalidLine() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1}\n{bad");
        if (Objects.nonNull(result)) {
            assertFalse(result.fixes().contains(FixType.NDJSON), "含非法行不应按 NDJSON 组装");
        }
    }

    @Test
    @DisplayName("边界：单行合法 JSON 不触发 NDJSON")
    void singleLineJsonNotNdjson() {
        final RepairResult result = JsonRepairer.repair("{\"a\":1}");
        assertNotNull(result);
        assertFalse(result.fixes().contains(FixType.NDJSON));
    }
}
