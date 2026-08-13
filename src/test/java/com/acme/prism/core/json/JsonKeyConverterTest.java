package com.acme.prism.core.json;

import com.acme.prism.core.json.JsonKeyConverter.KeyCase;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 键名风格转换器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
class JsonKeyConverterTest {

    @Test
    @DisplayName("正常：snake_case 转 camelCase")
    void convertsSnakeToCamel() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL).process("{\"user_name\": 1, \"first_name\": \"a\"}");
        assertNotNull(result);
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("userName"));
        assertTrue(obj.containsKey("firstName"));
        assertEquals(1, obj.getIntValue("userName"));
    }

    @Test
    @DisplayName("正常：camelCase 转 snake_case（含缩写 userID）")
    void convertsCamelToSnake() {
        final String result = new JsonKeyConverter(KeyCase.SNAKE).process("{\"userID\": 1, \"phoneNumber\": \"x\"}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("user_id"));
        assertTrue(obj.containsKey("phone_number"));
    }

    @Test
    @DisplayName("正常：连续大写缩略词 HTTPServer 转 http_server")
    void handlesAcronymBoundary() {
        final String result = new JsonKeyConverter(KeyCase.SNAKE).process("{\"HTTPServer\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("http_server"), result);
    }

    @Test
    @DisplayName("正常：字母数字边界 user2name 转 user_2_name")
    void handlesDigitBoundary() {
        final String result = new JsonKeyConverter(KeyCase.SNAKE).process("{\"user2name\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("user_2_name"), result);
    }

    @Test
    @DisplayName("正常：kebab-case 转 camelCase")
    void convertsKebabToCamel() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL).process("{\"user-name\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("userName"), result);
    }

    @Test
    @DisplayName("正常：snake_case 转 PascalCase")
    void convertsSnakeToPascal() {
        final String result = new JsonKeyConverter(KeyCase.PASCAL).process("{\"user_name\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("UserName"), result);
    }

    @Test
    @DisplayName("正常：snake_case 转 kebab-case")
    void convertsSnakeToKebab() {
        final String result = new JsonKeyConverter(KeyCase.KEBAB).process("{\"user_name\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("user-name"), result);
    }

    @Test
    @DisplayName("正常：数组内嵌套对象键递归转换")
    void convertsNestedArrayKeys() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL)
                .process("{\"items\": [{\"item_id\": 1}, {\"item_id\": 2}]}");
        final JSONArray items = JSON.parseObject(result).getJSONArray("items");
        assertNotNull(items);
        assertEquals(2, items.size());
        assertTrue(items.getJSONObject(0).containsKey("itemId"));
        assertTrue(items.getJSONObject(1).containsKey("itemId"));
    }

    @Test
    @DisplayName("正常：值内容不参与转换")
    void keepsValuesUntouched() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL).process("{\"user_name\": \"hello_world\"}");
        final JSONObject obj = JSON.parseObject(result);
        assertEquals("hello_world", obj.getString("userName"));
    }

    @Test
    @DisplayName("正常：null 值字段保留（与排序器惯例一致）")
    void keepsNullValues() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL).process("{\"user_name\": null, \"age\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("userName"), result);
        assertNull(obj.get("userName"));
        assertEquals(1, obj.getIntValue("age"));
    }

    @Test
    @DisplayName("边界：转换后键名冲突时后者覆盖前者（JSON 无法承载重复键）")
    void laterKeyWinsOnCollision() {
        final String result = new JsonKeyConverter(KeyCase.CAMEL).process("{\"user_id\": 1, \"userId\": 2}");
        final JSONObject obj = JSON.parseObject(result);
        assertFalse(obj.containsKey("user_id"), result);
        assertTrue(obj.containsKey("userId"), result);
        assertEquals(2, obj.getIntValue("userId"), "后者覆盖前者，属 JSON 重复键固有限制");
    }

    @Test
    @DisplayName("正常：连续分隔符键名归一化")
    void normalizesMixedSeparators() {
        final String result = new JsonKeyConverter(KeyCase.SNAKE).process("{\"user--name\": 1, \"other__key\": 2}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey("user_name"), result);
        assertTrue(obj.containsKey("other_key"), result);
        assertEquals(1, obj.getIntValue("user_name"));
        assertEquals(2, obj.getIntValue("other_key"));
    }

    @Test
    @DisplayName("异常：空输入与非法 JSON 原样返回")
    void returnsInputForBlankOrInvalid() {
        final JsonKeyConverter converter = new JsonKeyConverter(KeyCase.CAMEL);
        assertEquals("", converter.process(""));
        assertEquals("{bad", converter.process("{bad"));
    }

    @Test
    @DisplayName("边界：空键名保持为空键")
    void keepsEmptyKey() {
        final String result = new JsonKeyConverter(KeyCase.SNAKE).process("{\"\": 1}");
        final JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.containsKey(""), result);
    }
}
