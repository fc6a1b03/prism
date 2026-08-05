package com.acme.prism.core.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 键排序器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonSorterTest {

    private final JsonSorter sorter = new JsonSorter();

    @Test
    @DisplayName("正常：对象键按字母序排序")
    void sortsObjectKeys() {
        final JSONObject parsed = JSON.parseObject(sorter.process("{\"b\":1,\"a\":2}"));
        assertEquals(List.of("a", "b"), new ArrayList<>(parsed.keySet()), "键应按字母序排列");
    }

    @Test
    @DisplayName("正常：嵌套对象递归排序")
    void sortsNestedObjects() {
        final JSONObject parsed = JSON.parseObject(sorter.process("{\"z\":{\"y\":1,\"x\":2},\"a\":0}"));
        assertEquals(List.of("a", "z"), new ArrayList<>(parsed.keySet()));
        assertEquals(List.of("x", "y"), new ArrayList<>(parsed.getJSONObject("z").keySet()), "嵌套键应排序");
    }

    @Test
    @DisplayName("正常：数组内对象元素递归排序")
    void sortsObjectsInsideArrays() {
        final JSONObject parsed = JSON.parseObject(sorter.process("{\"list\":[{\"b\":1,\"a\":2}]}"));
        final JSONObject element = parsed.getJSONArray("list").getJSONObject(0);
        assertEquals(List.of("a", "b"), new ArrayList<>(element.keySet()), "数组内对象键应排序");
    }

    @Test
    @DisplayName("边界：空对象原样返回")
    void handlesEmptyObject() {
        assertEquals("{}", sorter.process("{}"));
    }

    @Test
    @DisplayName("边界：数组顺序保持不变")
    void preservesArrayOrder() {
        final JSONArray arr = JSON.parseObject(sorter.process("{\"arr\":[3,1,2]}")).getJSONArray("arr");
        assertEquals(List.of(3, 1, 2), arr, "数组元素顺序不应改变");
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalid() {
        final String garbage = "not json";
        assertEquals(garbage, sorter.process(garbage));
    }

    @Test
    @DisplayName("边界：null 与空输入原样返回（无 NPE）")
    void returnsInputOnNull() {
        assertNull(sorter.process(null));
        assertEquals("", sorter.process(""));
        assertNull(JsonSorter.sort(null, Boolean.TRUE));
    }

    @Test
    @DisplayName("验证：降序参数生效")
    void supportsDescendingOrder() {
        final JSONObject parsed = JSON.parseObject(JsonSorter.sort("{\"a\":1,\"b\":2}", Boolean.FALSE));
        assertEquals(List.of("b", "a"), new ArrayList<>(parsed.keySet()), "降序时 b 应在前");
    }
}
