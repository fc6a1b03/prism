package com.acme.prism.core.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON Schema 生成器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonSchemaGeneratorTest {

    private final JsonSchemaGenerator generator = new JsonSchemaGenerator();

    @Test
    @DisplayName("正常：基本类型推断")
    void infersBasicTypes() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"name\":\"x\",\"age\":18,\"height\":1.8,\"active\":true}"));
        final JSONObject properties = schema.getJSONObject("properties");
        assertEquals("object", schema.getString("type"));
        assertEquals("string", properties.getJSONObject("name").getString("type"));
        assertEquals("integer", properties.getJSONObject("age").getString("type"));
        assertEquals("number", properties.getJSONObject("height").getString("type"));
        assertEquals("boolean", properties.getJSONObject("active").getString("type"));
    }

    @Test
    @DisplayName("正常：嵌套对象递归生成")
    void generatesNestedObject() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"user\":{\"name\":\"x\"}}"));
        final JSONObject user = schema.getJSONObject("properties").getJSONObject("user");
        assertEquals("object", user.getString("type"));
        assertEquals("string", user.getJSONObject("properties").getJSONObject("name").getString("type"));
    }

    @Test
    @DisplayName("正常：数组元素类型推断")
    void infersArrayItemType() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"list\":[1,2]}"));
        assertEquals("array", schema.getJSONObject("properties").getJSONObject("list").getString("type"));
        assertEquals("integer", schema.getJSONObject("properties").getJSONObject("list").getJSONObject("items").getString("type"));
    }

    @Test
    @DisplayName("正常：混合类型数组合并为类型数组")
    void mergesMixedArrayTypes() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"list\":[1,\"a\"]}"));
        final Object itemsType = schema.getJSONObject("properties").getJSONObject("list").getJSONObject("items").get("type");
        assertTrue(itemsType instanceof JSONArray, "混合元素类型应输出类型数组");
        assertTrue(((JSONArray) itemsType).contains("integer") && ((JSONArray) itemsType).contains("string"));
    }

    @Test
    @DisplayName("正常：样例键全部标记 required")
    void marksAllKeysRequired() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"a\":1,\"b\":2}"));
        final JSONArray required = schema.getJSONArray("required");
        assertTrue(required.contains("a") && required.contains("b"));
        assertEquals(2, required.size());
    }

    @Test
    @DisplayName("正常：包含最新标准（2020-12）标识")
    void includesLatestDraftMarker() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"a\":1}"));
        assertTrue(schema.getString("$schema").contains("2020-12"), "应包含最新标准 2020-12 schema 标识");
    }

    @Test
    @DisplayName("边界：null 值推断为 null 类型")
    void handlesNullValue() {
        final JSONObject schema = JSON.parseObject(generator.process("{\"a\":null}"));
        assertEquals("null", schema.getJSONObject("properties").getJSONObject("a").getString("type"));
    }

    @Test
    @DisplayName("边界：空对象生成空 properties")
    void handlesEmptyObject() {
        final JSONObject schema = JSON.parseObject(generator.process("{}"));
        assertEquals("object", schema.getString("type"));
        assertNotNull(schema.getJSONObject("properties"));
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalid() {
        final String garbage = "not json";
        assertEquals(garbage, generator.process(garbage));
    }

    @Test
    @DisplayName("边界：null 与空输入原样返回（无 NPE）")
    void returnsInputOnNull() {
        assertNull(generator.process(null));
        assertEquals("", generator.process(""));
    }
}
