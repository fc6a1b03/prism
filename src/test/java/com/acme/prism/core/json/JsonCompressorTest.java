package com.acme.prism.core.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON压缩器单元测试
 *
 * @author 拒绝者
 * @date 2026-07-19
 */
class JsonCompressorTest {

    private final JsonCompressor compressor = new JsonCompressor();

    @Test
    @DisplayName("正常：带空白与换行的 JSON 被压缩为单行")
    void compressesWhitespaceJson() {
        final String result = compressor.process("{\n  \"a\": 1,\n  \"b\": [1, 2]\n}");
        assertAll(
                () -> assertEquals("{\"a\":1,\"b\":[1,2]}", result, "压缩结果应去除全部冗余空白"),
                () -> assertFalse(result.contains("\n"), "压缩结果不应包含换行")
        );
    }

    @ParameterizedTest(name = "[{index}] {0} => {1}")
    @MethodSource("boundaryCases")
    @DisplayName("边界：空对象、空数组与纯数字的压缩")
    void compressesBoundaryInputs(final String input, final String expected) {
        assertEquals(expected, compressor.process(input), "边界输入压缩结果应符合预期");
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(
                Arguments.of("{}", "{}"),
                Arguments.of("[]", "[]"),
                Arguments.of("  42  ", "42")
        );
    }

    @Test
    @DisplayName("边界：空字符串压缩后输出 null 字面量（fastjson2 解析空串得 null）")
    void compressesEmptyStringToNullLiteral() {
        assertEquals("null", compressor.process(""), "空串经 fastjson2 解析为 null，序列化输出 \"null\"");
    }

    @Test
    @DisplayName("边界：二十层深层嵌套被压缩为单行")
    void compressesDeeplyNestedJson() {
        final String result = compressor.process(deepJson(20));
        assertAll(
                () -> assertFalse(result.contains("\n"), "深层嵌套压缩结果不应包含换行"),
                () -> assertFalse(result.contains(" "), "深层嵌套压缩结果不应包含空格"),
                () -> assertTrue(result.startsWith("{\"v\":"), "深层嵌套压缩结果应保留根字段"),
                () -> assertTrue(result.contains("\"n\":1"), "深层嵌套压缩结果应保留叶子字段")
        );
    }

    @Test
    @DisplayName("正常：null 字段在压缩后保留（WriteMapNullValue）")
    void preservesNullFields() {
        final String result = compressor.process("{\"a\":null,\"b\":1}");
        assertEquals("{\"a\":null,\"b\":1}", result, "压缩不应丢弃 null 字段");
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalidJson() {
        final String garbage = "not a json";
        assertEquals(garbage, compressor.process(garbage));
    }

    @Test
    @DisplayName("校验：isValid 对合法与非法输入的判断")
    void validatesJson() {
        assertAll(
                () -> assertTrue(compressor.isValid("{\"a\":1}"), "合法 JSON 应判定有效"),
                () -> assertFalse(compressor.isValid("not a json"), "非法 JSON 应判定无效")
        );
    }

    private static String deepJson(final int depth) {
        final StringBuilder sb = new StringBuilder("{\"v\":");
        for (int i = 0; i < depth; i++) {
            sb.append("{\"n\":");
        }
        sb.append("1");
        for (int i = 0; i < depth; i++) {
            sb.append("}");
        }
        return sb.append("}").toString();
    }
}
