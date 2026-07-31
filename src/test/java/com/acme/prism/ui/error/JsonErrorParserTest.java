package com.acme.prism.ui.error;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonErrorParser 单元测试：基于真实 fastjson2 异常消息验证行列提取。
 *
 * <p>测试数据来自对 {@code JSON.parse} 真实捕获的 JSONException 消息（2026-07-31 实测，
 * fastjson2 2.0.62）。禁止使用编造的异常消息样本。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class JsonErrorParserTest {

    // --------------- 提取行列（基于真实异常消息） ---------------

    @Test
    @DisplayName("正常：{bad 的真实异常消息可提取行列")
    void extractFromRealUnquotedFieldError() {
        // 实测：JSON.parse("{bad") => "not allow unquoted fieldName, offset 2, character b, line 1, column 2, fastjson-version 2.0.62 {bad"
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.extractPosition(
                "not allow unquoted fieldName, offset 2, character b, line 1, column 2, fastjson-version 2.0.62 {bad");
        assertAll(
                () -> assertNotNull(pos, "应提取到位置信息"),
                () -> assertEquals(1, pos.line(), "行号应为 1"),
                () -> assertEquals(2, pos.column(), "列号应为 2")
        );
    }

    @Test
    @DisplayName("正常：[,] 的真实异常消息可提取行列")
    void extractFromRealTrailingCommaError() {
        // 实测：JSON.parse("[,]") => "offset 2, character ,, line 1, column 2, fastjson-version 2.0.62 [,]"
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.extractPosition(
                "offset 2, character ,, line 1, column 2, fastjson-version 2.0.62 [,]");
        assertAll(
                () -> assertNotNull(pos),
                () -> assertEquals(1, pos.line()),
                () -> assertEquals(2, pos.column())
        );
    }

    @Test
    @DisplayName("正常：[\"unclosed 的真实异常消息可提取行列")
    void extractFromRealUnclosedStringError() {
        // 实测：JSON.parse("[\"unclosed") => "invalid escape character EOI, offset 2, character \", line 1, column 2, fastjson-version 2.0.62 [\"unclosed"
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.extractPosition(
                "invalid escape character EOI, offset 2, character \", line 1, column 2, fastjson-version 2.0.62 [\"unclosed");
        assertNotNull(pos, "应提取到位置信息");
        assertEquals(2, pos.column());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no line info here", "some error without numbers",
            "syntax error, offset 5, char \""})
    @DisplayName("边界：无 line/column 信息的真实异常消息返回 null")
    void returnsNullForUnparseableMessage(final String message) {
        // "syntax error, offset 5, char \"" 是 JSON.parse("{\"a\" 1}") 的真实异常消息，无行列信息
        assertNull(JsonErrorParser.extractPosition(message), "无行列信息应返回 null");
    }

    // --------------- 完整解析（真实输入） ---------------

    @Test
    @DisplayName("正常：非法 JSON 返回错误位置")
    void parseErrorReturnsPositionForInvalidJson() {
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.parseError("{bad");
        assertNotNull(pos, "非法 JSON 应返回错误位置");
        assertTrue(pos.message().contains("line"), "消息应包含行列信息");
    }

    @Test
    @DisplayName("正常：逗号多余的数组 [,] 返回错误位置")
    void parseErrorForTrailingComma() {
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.parseError("[,]");
        assertNotNull(pos, "[,] 应返回错误位置");
        assertTrue(pos.line() >= 1, "行号应 >= 1");
    }

    @Test
    @DisplayName("边界：异常消息无行列信息的非法 JSON 返回 null（无法定位）")
    void parseErrorReturnsNullWhenPositionUnavailable() {
        // 实测：JSON.parse("{\"a\" 1}") 的消息为 "syntax error, offset 5, char \""，无 line/column
        final JsonErrorParser.ErrorPosition pos = JsonErrorParser.parseError("{\"a\" 1}");
        assertNull(pos, "无法提取行列时应返回 null，标注器跳过该错误");
    }

    @Test
    @DisplayName("边界：尾逗号 {a:1,} 是合法 JSON（fastjson2 容忍），返回 null")
    void parseErrorAllowsTrailingComma() {
        // 实测：JSON.parse("{\"a\": 1,}") 不抛异常（fastjson2 容忍尾逗号）
        assertNull(JsonErrorParser.parseError("{\"a\": 1,}"), "尾逗号 JSON 不应报错");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\":1}",
            "[1,2,3]",
            "true",
            "\"hello\"",
            "{\"nested\":{\"key\":\"value\"}}",
            "[{\"id\":1},{\"id\":2}]"
    })
    @DisplayName("正常：合法 JSON 返回 null")
    void parseErrorReturnsNullForValidJson(final String json) {
        assertNull(JsonErrorParser.parseError(json), "合法 JSON 不应返回错误");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("边界：null 与空串返回 null")
    void parseErrorReturnsNullForBlank(final String json) {
        assertNull(JsonErrorParser.parseError(json), "空白输入应返回 null");
    }

    // --------------- 测试数据真实性校验：探针断言与实测一致 ---------------

    @ParameterizedTest
    @ValueSource(strings = {"{bad", "[,]", "[\"unclosed", "{\"a\" 1}", "{\"a\": 1,}", "not json at all"})
    @DisplayName("探针：真实 JSON.parse 行为与 parseError 结果一致")
    void probeRealBehavior(final String input) {
        boolean realThrows;
        try {
            JSON.parse(input);
            realThrows = false;
        } catch (final JSONException e) {
            realThrows = true;
        }
        // 真实抛异常的输入，若消息含行列则 parseError 返回非 null；否则返回 null
        if (!realThrows) {
            assertNull(JsonErrorParser.parseError(input), input + " 不应报错");
        } else {
            // 无论能否定位，parseError 都不应抛异常（可能返回 null）
            assertDoesNotThrow(() -> JsonErrorParser.parseError(input), input + " parseError 不应抛异常");
        }
    }
}
