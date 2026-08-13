package com.acme.prism.core.parser.converter;

import com.acme.prism.common.enums.AnyFile;
import com.acme.prism.core.parser.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Markdown 表格转换器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-13
 */
class MarkdownTableConverterTest {

    private final MarkdownTableConverter converter = new MarkdownTableConverter();

    @Test
    @DisplayName("正常：对象数组转并集列头表格")
    void convertsObjectArrayToTable() {
        final String result = this.converter.convert("[{\"name\":\"a\",\"age\":1},{\"name\":\"b\",\"city\":\"X\"}]");
        assertTrue(result.contains("| name | age | city |"), result);
        assertTrue(result.contains("| a | 1 |  |"), result);
        assertTrue(result.contains("| b |  | X |"), result);
        assertTrue(result.contains("| --- | --- | --- |"), "应包含分隔行");
    }

    @Test
    @DisplayName("正常：单对象转键值两列表")
    void convertsObjectToKeyValueTable() {
        final String result = this.converter.convert("{\"name\":\"acme\",\"age\":18}");
        assertTrue(result.contains("| Key | Value |"), result);
        assertTrue(result.contains("| name | acme |"), result);
        assertTrue(result.contains("| age | 18 |"), result);
    }

    @Test
    @DisplayName("正常：混合数组转索引值两列表")
    void convertsMixedArrayToIndexTable() {
        final String result = this.converter.convert("[1,\"x\",null]");
        assertTrue(result.contains("| Index | Value |"), result);
        assertTrue(result.contains("| 0 | 1 |"), result);
        assertTrue(result.contains("| 1 | x |"), result);
        assertTrue(result.contains("| 2 |  |"), "null 应输出空单元格");
    }

    @Test
    @DisplayName("正常：null 值输出空单元格")
    void nullValueProducesEmptyCell() {
        final String result = this.converter.convert("[{\"a\":null}]");
        assertTrue(result.contains("| a |"), result);
        assertTrue(result.contains("|  |"), "null 单元格应为空");
    }

    @Test
    @DisplayName("正常：单元格管道符与换行被转义")
    void escapesPipeAndNewline() {
        final String result = this.converter.convert("[{\"a\":\"x|y\",\"b\":\"l1\\nl2\"}]");
        assertTrue(result.contains("x\\|y"), result);
        assertTrue(result.contains("l1<br>l2"), result);
    }

    @Test
    @DisplayName("正常：嵌套对象数组值 JSON 字符串化")
    void stringifiesNestedValues() {
        final String result = this.converter.convert("[{\"a\":{\"b\":1},\"c\":[1,2]}]");
        assertTrue(result.contains("{\"b\":1}"), result);
        assertTrue(result.contains("[1,2]"), result);
    }

    @Test
    @DisplayName("正常：标量转单列表")
    void convertsScalarToSingleColumn() {
        final String result = this.converter.convert("42");
        assertTrue(result.contains("| Value |"), result);
        assertTrue(result.contains("| 42 |"), result);
    }

    @Test
    @DisplayName("异常：空输入与非法 JSON 返回空串")
    void returnsEmptyForBlankOrInvalid() {
        assertEquals("", this.converter.convert(""));
        assertEquals("", this.converter.convert("{bad"));
    }

    @Test
    @DisplayName("异常：空数组返回空串")
    void returnsEmptyForEmptyArray() {
        assertEquals("", this.converter.convert("[]"));
    }

    @Test
    @DisplayName("注册表：MARKDOWN 已接入 JsonParser 转换")
    void registeredInJsonParser() {
        final String result = JsonParser.convert("[{\"name\":\"acme\"}]", AnyFile.MARKDOWN);
        assertFalse(result.isEmpty(), "注册表转换输出不应为空");
        assertTrue(result.contains("| name |"), result);
    }
}
