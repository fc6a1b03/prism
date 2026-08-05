package com.acme.prism.core.json;

import com.acme.prism.core.json.JsonAnalyzer.Stats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 结构分析器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonAnalyzerTest {

    @Test
    @DisplayName("正常：简单对象统计键数与对象数")
    void countsSimpleObject() {
        final Stats stats = JsonAnalyzer.analyze("{\"a\":1,\"b\":2}");
        assertEquals(2, stats.keys());
        assertEquals(1, stats.objects());
        assertEquals(0, stats.arrays());
        assertEquals(1, stats.maxDepth());
        assertTrue(stats.sizeBytes() > 0);
    }

    @Test
    @DisplayName("正常：嵌套结构统计深度与各级数量")
    void countsNestedStructure() {
        final Stats stats = JsonAnalyzer.analyze("{\"a\":{\"b\":[1,2]}}");
        assertEquals(2, stats.keys(), "应统计所有层级的键数");
        assertEquals(2, stats.objects(), "根对象与嵌套对象共 2 个");
        assertEquals(1, stats.arrays());
        assertEquals(3, stats.maxDepth(), "最大深度应到数组元素（根=1）");
    }

    @Test
    @DisplayName("正常：数组元素内的对象键被统计")
    void countsKeysInsideArrayElements() {
        final Stats stats = JsonAnalyzer.analyze("{\"list\":[{\"x\":1},{\"y\":2}]}");
        assertEquals(3, stats.keys(), "list 键 + 两个元素对象键共 3 个");
        assertEquals(3, stats.objects(), "根对象 + 2 个数组元素对象");
        assertEquals(1, stats.arrays());
    }

    @Test
    @DisplayName("边界：空文本返回全零统计")
    void returnsEmptyStatsForBlank() {
        final Stats stats = JsonAnalyzer.analyze("  ");
        assertTrue(stats.isEmpty());
        assertEquals(0, stats.sizeBytes());
    }

    @Test
    @DisplayName("边界：null 输入不抛异常")
    void handlesNullInput() {
        assertTrue(JsonAnalyzer.analyze(null).isEmpty());
        assertTrue(JsonAnalyzer.duplicateKeys(null).isEmpty());
    }

    @Test
    @DisplayName("异常：非法 JSON 返回全零统计")
    void returnsEmptyStatsForInvalid() {
        assertTrue(JsonAnalyzer.analyze("not json").isEmpty());
    }

    @Test
    @DisplayName("验证：sizeBytes 为 UTF-8 字节数")
    void countsUtf8Bytes() {
        final Stats stats = JsonAnalyzer.analyze("{\"a\":\"中文\"}");
        assertEquals("{\"a\":\"中文\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, stats.sizeBytes());
    }

    @Test
    @DisplayName("正常：检测重复键")
    void detectsDuplicateKeys() {
        final Map<String, Integer> duplicates = JsonAnalyzer.duplicateKeys("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals(1, duplicates.size());
        assertEquals(2, duplicates.get("a"));
    }

    @Test
    @DisplayName("正常：字符串值中的内容不误报为键")
    void ignoresStringValues() {
        assertTrue(JsonAnalyzer.duplicateKeys("{\"a\":\"x: y\",\"b\":2}").isEmpty(), "字符串值内的冒号内容不应误报");
    }

    @Test
    @DisplayName("边界：无重复键返回空映射")
    void returnsEmptyWhenNoDuplicates() {
        assertTrue(JsonAnalyzer.duplicateKeys("{\"a\":1,\"b\":2}").isEmpty());
    }

    @Test
    @DisplayName("边界：空文本返回空映射")
    void returnsEmptyForBlank() {
        assertTrue(JsonAnalyzer.duplicateKeys("  ").isEmpty());
    }

    @Test
    @DisplayName("验证：analyze(JsonNode) 与 analyze(String) 结果一致（消除双解析）")
    void nodeAnalysisMatchesStringAnalysis() {
        final String json = "{\"a\":{\"b\":[1,2]},\"c\":\"x\"}";
        final Stats fromString = JsonAnalyzer.analyze(json);
        final Stats fromNode = JsonAnalyzer.analyze(
                com.acme.prism.core.parser.JsonNodeParser.parse("root", json),
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        );
        assertEquals(fromString.keys(), fromNode.keys());
        assertEquals(fromString.objects(), fromNode.objects());
        assertEquals(fromString.arrays(), fromNode.arrays());
        assertEquals(fromString.maxDepth(), fromNode.maxDepth());
        assertEquals(fromString.sizeBytes(), fromNode.sizeBytes());
    }

    @Test
    @DisplayName("边界：null 节点统计返回空统计")
    void handlesNullNode() {
        assertTrue(JsonAnalyzer.analyze(null, 0L).isEmpty());
    }
}
