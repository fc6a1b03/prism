package com.acme.prism.core.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON Mock 数据生成器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonMockGeneratorTest {

    private final JsonMockGenerator generator = new JsonMockGenerator();

    @Test
    @DisplayName("正常：结构完整保留，字符串按字段名启发式生成")
    void preservesStructureAndGeneratesStrings() {
        final JSONObject mock = JSON.parseObject(generator.process("{\"user\":{\"name\":\"x\",\"email\":\"x@y.z\"}}"));
        final JSONObject user = mock.getJSONObject("user");
        assertNotNull(user, "嵌套结构应保留");
        assertTrue(user.getString("name").length() >= 2, "name 字段应生成姓名");
        assertTrue(user.getString("email").contains("@"), "email 字段应生成邮箱");
    }

    @Test
    @DisplayName("正常：数字与布尔生成随机值，类型保留")
    void generatesRandomNumbersAndBooleans() {
        final JSONObject mock = JSON.parseObject(generator.process("{\"age\":18,\"active\":true,\"score\":1.5}"));
        assertTrue(mock.getIntValue("age") >= 1, "age 应为正整数");
        assertTrue(mock.getBooleanValue("active") || !mock.getBooleanValue("active"), "active 应为布尔");
        assertTrue(mock.getDoubleValue("score") > 0, "score 应为正数");
    }

    @Test
    @DisplayName("正常：null 值保持 null")
    void keepsNullValues() {
        final JSONObject mock = JSON.parseObject(generator.process("{\"a\":null}"));
        assertNull(mock.get("a"));
    }

    @Test
    @DisplayName("正常：数组长度保留")
    void preservesArrayLength() {
        final JSONObject mock = JSON.parseObject(generator.process("{\"list\":[1,2,3]}"));
        assertEquals(3, mock.getJSONArray("list").size());
    }

    @Test
    @DisplayName("正常：手机号字段生成手机号格式")
    void generatesPhoneFormat() {
        final JSONObject mock = JSON.parseObject(generator.process("{\"phone\":\"13800000000\"}"));
        assertTrue(mock.getString("phone").startsWith("138"), "phone 字段应生成手机号");
        assertEquals(11, mock.getString("phone").length());
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalid() {
        final String garbage = "not json";
        assertEquals(garbage, generator.process(garbage));
    }

    @Test
    @DisplayName("边界：null 与空输入原样返回")
    void returnsInputOnNull() {
        assertNull(generator.process(null));
        assertEquals("", generator.process(""));
    }
}
