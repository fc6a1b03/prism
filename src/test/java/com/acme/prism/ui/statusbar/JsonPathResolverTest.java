package com.acme.prism.ui.statusbar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 路径解析器单元测试：从文本和光标位置计算 JsonPath。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class JsonPathResolverTest {

    @Test
    @DisplayName("顶层：光标在键名内返回 $.key")
    void topLevelKeyCursor() {
        assertEquals("$.name", JsonPathStatusBarWidget.resolveJsonPath("{\"name\":\"test\"}", 2));
    }

    @Test
    @DisplayName("顶层：光标在值内返回 $.key")
    void topLevelValueCursor() {
        assertEquals("$.name", JsonPathStatusBarWidget.resolveJsonPath("{\"name\":\"test\"}", 10));
    }

    @Test
    @DisplayName("嵌套二层：光标在嵌套键处返回完整路径")
    void nestedTwoLevelKeyCursor() {
        assertEquals("$.a.b", JsonPathStatusBarWidget.resolveJsonPath("{\"a\":{\"b\":1}}", 8));
    }

    @Test
    @DisplayName("数组：光标在数组元素对象内返回 $.key[index].subkey")
    void arrayElementNestedKey() {
        final String json = "{\"versions\":[{\"type\":\"release\"}]}";
        // "type" 键名位置
        assertEquals("$.versions[0].type", JsonPathStatusBarWidget.resolveJsonPath(json, 20));
    }

    @Test
    @DisplayName("数组：光标在第二个数组元素内返回 $.key[1].subkey")
    void secondArrayElementKey() {
        final String json = "{\"versions\":[{\"type\":\"a\"},{\"type\":\"b\"}]}";
        // 第二个元素的 "type" 键名内（offset 29 附近）
        assertEquals("$.versions[1].type", JsonPathStatusBarWidget.resolveJsonPath(json, 29));
    }

    @Test
    @DisplayName("缓存：文本不变时二次调用返回一致结果（命中缓存）")
    void cacheHitReturnsConsistentResult() {
        final String json = "{\"a\":{\"b\":1}}";
        final String first = JsonPathStatusBarWidget.resolveJsonPath(json, 8);
        final String second = JsonPathStatusBarWidget.resolveJsonPath(json, 8);
        assertEquals(first, second, "相同文本相同 offset 应返回一致路径");
        assertFalse(first.isEmpty(), "路径不应为空");
    }

    @Test
    @DisplayName("缓存：文本变化后返回新结果（缓存失效）")
    void cacheInvalidatesOnTextChange() {
        final String jsonA = "{\"a\":{\"b\":1}}";
        final String jsonB = "{\"x\":{\"y\":2}}";
        final String pathA = JsonPathStatusBarWidget.resolveJsonPath(jsonA, 8);
        final String pathB = JsonPathStatusBarWidget.resolveJsonPath(jsonB, 8);
        assertEquals("$.a.b", pathA, "文本 A 应解析出 $.a.b");
        assertEquals("$.x.y", pathB, "文本 B 应解析出 $.x.y（缓存已失效）");
    }

    @Test
    @DisplayName("边界：offset 为 0 返回空串")
    void offsetZeroReturnsRoot() {
        assertEquals("", JsonPathStatusBarWidget.resolveJsonPath("{\"a\":1}", 0));
    }

    @Test
    @DisplayName("边界：空字符串返回空串")
    void emptyStringReturnsRoot() {
        assertEquals("", JsonPathStatusBarWidget.resolveJsonPath("", 0));
    }

    @Test
    @DisplayName("边界：非法 JSON 返回 $（优雅降级不抛异常）")
    void invalidJsonGracefulDegradation() {
        assertDoesNotThrow(() -> JsonPathStatusBarWidget.resolveJsonPath("{bad", 3),
                "非法 JSON 不应抛异常");
    }
}
