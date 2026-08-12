package com.acme.prism.ui.dialog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.acme.prism.ui.dialog.JsonValidateDialog.extractLastKey;
import static com.acme.prism.ui.dialog.JsonValidateDialog.findKeyOffset;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验弹窗定位逻辑单元测试（extractLastKey / findKeyOffset 纯逻辑部分）。
 *
 * <p>光标移动/滚动等 IDE 编辑器交互依赖运行时，按项目测试策略属手动验证范围。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonValidateDialogTest {

    @Test
    @DisplayName("正常：提取路径最后一段键名")
    void extractsLastKey() {
        assertEquals("name", extractLastKey("$.user.name"));
        assertEquals("list", extractLastKey("$.list[0]"));
        assertEquals("c", extractLastKey("$.a.b.c"));
        assertEquals("c", extractLastKey("c"));
    }

    @Test
    @DisplayName("边界：根路径与空路径返回空串")
    void extractsEmptyForRoot() {
        assertEquals("", extractLastKey("$"));
        assertEquals("", extractLastKey(""));
        assertEquals("", extractLastKey(null));
    }

    @Test
    @DisplayName("正常：定位键名偏移")
    void findsKeyOffset() {
        final String json = "{\"a\":1,\"b\":2}";
        assertEquals(json.indexOf("\"b\""), findKeyOffset(json, "b"), "应定位到 b 键的引号起始位置");
    }

    @Test
    @DisplayName("正常：字符串值中的同名内容不误报（后跟冒号才是键）")
    void ignoresStringValues() {
        // 字符串值中出现 "b"（位置 5）但非键；键 b 在位置 10 → 应定位到键而非字符串值
        final String json = "{\"a\":\"b x\",\"b\":1}";
        assertEquals(json.indexOf("\"b\":") , findKeyOffset(json, "b"), "应定位到键 b 而非字符串值中的 b");
    }

    @Test
    @DisplayName("边界：不存在的键返回 -1")
    void returnsMinusOneWhenNotFound() {
        assertEquals(-1, findKeyOffset("{\"a\":1}", "missing"));
        assertEquals(-1, findKeyOffset("", "a"));
        assertEquals(-1, findKeyOffset("{\"a\":1}", null));
    }
}
