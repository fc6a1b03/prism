package com.acme.prism.core.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonFoldingCalculator 单元测试：跨行括号对折叠区域计算。
 *
 * @author 拒绝者
 * @date 2026-07-31
 */
class JsonFoldingCalculatorTest {

    @Test
    @DisplayName("正常：格式化 JSON 的对象跨行可折叠")
    void formattedObjectFoldable() {
        final String json = "{\n  \"a\": 1\n}";
        final List<JsonFoldingCalculator.FoldRegion> regions = JsonFoldingCalculator.calculate(json);
        assertAll(
                () -> assertEquals(1, regions.size(), "一个跨行对象应产生一个折叠区"),
                () -> assertEquals(0, regions.getFirst().startOffset()),
                () -> assertEquals(json.length(), regions.getFirst().endOffset()),
                () -> assertEquals("{...}", regions.getFirst().placeholder())
        );
    }

    @Test
    @DisplayName("正常：嵌套对象产生多个折叠区域（外层+内层）")
    void nestedObjectsMultipleRegions() {
        final String json = "{\n  \"a\": {\n    \"b\": 1\n  }\n}";
        final List<JsonFoldingCalculator.FoldRegion> regions = JsonFoldingCalculator.calculate(json);
        assertEquals(2, regions.size(), "外层与内层对象都应可折叠");
        // 内层区间应在外层区间内部
        assertTrue(regions.getFirst().startOffset() < regions.getLast().startOffset(),
                "外层折叠区应包含内层折叠区");
    }

    @Test
    @DisplayName("正常：数组跨行可折叠")
    void formattedArrayFoldable() {
        final String json = "[\n  1,\n  2\n]";
        final List<JsonFoldingCalculator.FoldRegion> regions = JsonFoldingCalculator.calculate(json);
        assertAll(
                () -> assertEquals(1, regions.size()),
                () -> assertEquals("[...]", regions.getFirst().placeholder())
        );
    }

    @Test
    @DisplayName("边界：压缩单行 JSON 无可折叠区域")
    void compactJsonNoFoldRegion() {
        final String json = "{\"a\":{\"b\":1},\"c\":[1,2]}";
        assertTrue(JsonFoldingCalculator.calculate(json).isEmpty(),
                "单行 JSON 不应产生折叠区域");
    }

    @Test
    @DisplayName("边界：字符串内的括号不参与折叠")
    void bracesInsideStringIgnored() {
        // 字符串 {"fake"} 内含括号，不应误判为折叠点
        final String json = "{\n  \"a\": \"{\\\"fake\\\"}\"\n}";
        final List<JsonFoldingCalculator.FoldRegion> regions = JsonFoldingCalculator.calculate(json);
        assertEquals(1, regions.size(), "仅外层对象可折叠，字符串内括号应忽略");
    }

    @Test
    @DisplayName("边界：null 与空串返回空列表")
    void blankInputReturnsEmpty() {
        assertTrue(JsonFoldingCalculator.calculate(null).isEmpty());
        assertTrue(JsonFoldingCalculator.calculate("").isEmpty());
    }

    @Test
    @DisplayName("边界：非法 JSON 括号不匹配时安全返回（不抛异常）")
    void unbalancedBracesSafeReturn() {
        final String json = "{\n  \"a\": 1\n";
        assertDoesNotThrow(() -> JsonFoldingCalculator.calculate(json),
                "括号不匹配不应抛异常");
    }
}
