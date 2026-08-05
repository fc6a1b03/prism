package com.acme.prism.core.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 扁平化器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonFlattenerTest {

    private final JsonFlattener flattener = new JsonFlattener();

    @Test
    @DisplayName("正常：嵌套对象展开为点号键")
    void flattensNestedObjects() {
        final String result = flattener.process("{\"a\":{\"b\":1}}");
        assertEquals("{\"a.b\":1}", result);
    }

    @Test
    @DisplayName("正常：数组元素展开为索引键")
    void flattensArrays() {
        final String result = flattener.process("{\"c\":[1,2]}");
        assertEquals("{\"c[0]\":1,\"c[1]\":2}", result);
    }

    @Test
    @DisplayName("正常：深层嵌套混合结构")
    void flattensDeepNesting() {
        final String result = flattener.process("{\"a\":{\"b\":[{\"c\":1}]}}");
        assertEquals("{\"a.b[0].c\":1}", result);
    }

    @Test
    @DisplayName("正常：标量与 null 值保留")
    void keepsScalarsAndNull() {
        final String result = flattener.process("{\"a\":null,\"b\":\"x\"}");
        assertEquals("{\"a\":null,\"b\":\"x\"}", result);
    }

    @Test
    @DisplayName("边界：空对象输出空对象")
    void flattensEmptyObject() {
        assertEquals("{}", flattener.process("{}"));
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalid() {
        final String garbage = "not json";
        assertEquals(garbage, flattener.process(garbage));
    }

    @Test
    @DisplayName("边界：null 与空输入原样返回（无 NPE）")
    void returnsInputOnNull() {
        assertNull(flattener.process(null));
        assertEquals("", flattener.process(""));
        assertNull(JsonFlattener.flatten(null, "."));
    }
}
