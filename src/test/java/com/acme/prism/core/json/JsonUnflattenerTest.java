package com.acme.prism.core.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 反扁平化器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonUnflattenerTest {

    private final JsonUnflattener unflattener = new JsonUnflattener();

    @Test
    @DisplayName("正常：点号键还原为嵌套对象")
    void unflattensNestedObjects() {
        final String result = unflattener.process("{\"a.b\":1}");
        assertEquals("{\"a\":{\"b\":1}}", result);
    }

    @Test
    @DisplayName("正常：索引键重建数组")
    void rebuildsArrays() {
        final String result = unflattener.process("{\"c[0]\":1,\"c[1]\":2}");
        assertEquals("{\"c\":[1,2]}", result);
    }

    @Test
    @DisplayName("正常：深层嵌套混合结构还原")
    void rebuildsDeepNesting() {
        final String result = unflattener.process("{\"a.b[0].c\":1}");
        assertEquals("{\"a\":{\"b\":[{\"c\":1}]}}", result);
    }

    @Test
    @DisplayName("验证：与扁平化往返一致")
    void roundTripWithFlattener() {
        final String original = "{\"user\":{\"name\":\"kimi\",\"tags\":[\"a\",\"b\"]},\"score\":99}";
        final String flat = new JsonFlattener().process(original);
        assertEquals(original, unflattener.process(flat), "扁平化后再还原应保持一致");
    }

    @Test
    @DisplayName("异常：键路径冲突返回 null（原样返回）")
    void returnsInputOnKeyConflict() {
        // "a" 同时作为容器与标量值，冲突
        final String conflicting = "{\"a.b\":1,\"a\":2}";
        assertEquals(conflicting, unflattener.process(conflicting));
    }

    @Test
    @DisplayName("边界：重复键被解析层覆盖，不报错")
    void handlesDuplicateSourceKey() {
        final String duplicate = "{\"a\":1,\"a\":2}";
        assertNotNull(unflattener.process(duplicate));
    }

    @Test
    @DisplayName("异常：非法输入返回 null")
    void returnsNullOnInvalid() {
        assertNull(JsonUnflattener.unflatten("not json"));
    }

    @Test
    @DisplayName("边界：null 与空输入返回 null（无 NPE）")
    void returnsNullOnBlank() {
        assertNull(JsonUnflattener.unflatten(null));
        assertNull(JsonUnflattener.unflatten(""));
        assertNull(JsonUnflattener.unflatten("  "));
        assertNull(unflattener.process(null));
    }
}
