package com.acme.prism.core.parser.converter;

import com.acme.prism.common.enums.AnyFile;
import com.acme.prism.core.parser.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeScript 转换器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-14
 */
class TypeScriptConverterTest {

    private final TypeScriptConverter converter = new TypeScriptConverter();

    @Test
    @DisplayName("正常：基本类型字段映射")
    void mapsBasicTypes() {
        final String result = this.converter.convert("{\"name\":\"x\",\"age\":18,\"active\":true}");
        assertTrue(result.contains("export interface Dummy {"), result);
        assertTrue(result.contains("  name: string;"), result);
        assertTrue(result.contains("  age: number;"), result);
        assertTrue(result.contains("  active: boolean;"), result);
    }

    @Test
    @DisplayName("正常：嵌套对象平铺为独立 interface")
    void flattensNestedObjects() {
        final String result = this.converter.convert("{\"user\":{\"name\":\"x\"}}");
        assertTrue(result.contains("export interface Dummy {"), result);
        assertTrue(result.contains("  user: User;"), result);
        assertTrue(result.contains("export interface User {"), result);
        assertTrue(result.contains("  name: string;"), result);
    }

    @Test
    @DisplayName("正常：对象数组映射为元素类型数组 + 嵌套 interface")
    void mapsObjectArray() {
        final String result = this.converter.convert("{\"items\":[{\"id\":1}]}");
        assertTrue(result.contains("  items: Items[];"), result);
        assertTrue(result.contains("export interface Items {"), result);
        assertTrue(result.contains("  id: number;"), result);
    }

    @Test
    @DisplayName("正常：基础类型数组与空数组映射")
    void mapsPrimitiveAndEmptyArrays() {
        final String result = this.converter.convert("{\"tags\":[\"a\"],\"empty\":[]}");
        assertTrue(result.contains("  tags: string[];"), result);
        assertTrue(result.contains("  empty: any[];"), result);
    }

    @Test
    @DisplayName("正常：null 值字段映射为 any")
    void mapsNullToAny() {
        final String result = this.converter.convert("{\"maybe\":null}");
        assertTrue(result.contains("  maybe: any;"), result);
    }

    @Test
    @DisplayName("正常：深层嵌套递归平铺")
    void flattensDeepNesting() {
        final String result = this.converter.convert("{\"a\":{\"b\":{\"c\":1}}}");
        assertTrue(result.contains("export interface A {"), result);
        assertTrue(result.contains("export interface B {"), result);
        assertTrue(result.contains("  c: number;"), result);
    }

    @Test
    @DisplayName("异常：空输入与非法 JSON 返回空串")
    void returnsEmptyForBlankOrInvalid() {
        assertEquals("", this.converter.convert(""));
        assertEquals("", this.converter.convert("{bad"));
    }

    @Test
    @DisplayName("注册表：TYPESCRIPT 已接入 JsonParser 转换")
    void registeredInJsonParser() {
        final String result = JsonParser.convert("{\"name\":\"x\"}", AnyFile.TYPESCRIPT);
        assertFalse(result.isEmpty(), "注册表转换输出不应为空");
        assertTrue(result.contains("export interface Dummy {"), result);
        assertTrue(result.contains("  name: string;"), result);
    }
}
