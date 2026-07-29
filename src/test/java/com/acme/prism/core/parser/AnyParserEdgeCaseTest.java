package com.acme.prism.core.parser;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnyParser 补充测试：格式检测优先级、竞态场景、边界格式混淆。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class AnyParserEdgeCaseTest {

    // --------------- 检测优先级 ---------------

    @Test
    @DisplayName("优先级：XML（startsWith '<'）优先于所有其他格式")
    void xmlHasHighestPriority() {
        // XML 样本以 '<' 开头，DETECT_RULES 中 XML 排在第一位
        // 此文本同时匹配 XML（starts '<'）且不匹配 YAML/TOML 等，验证 XML 被正确识别
        final String input = "<book><title>Prism Guide</title></book>";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "XML 应被识别并转换"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON"),
                () -> assertTrue(result.contains("Prism Guide"), "结果应包含 XML 文本内容")
        );
    }

    @Test
    @DisplayName("优先级：YAML 的列表在 TOML 之前被检测")
    void yamlListDetectedBeforeToml() {
        // YAML 列表 `- item` 同时可能被 TOML 的 `[` 匹配（如果输入包含 `[`）
        // 单独的 YAML 列表没有 `=` 没有 `[`，不应被 TOML 误识别
        final String input = "- apple\n- banana\n- cherry";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "YAML 列表应被识别并转换"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON")
        );
    }

    // --------------- 格式混淆 ---------------

    @Test
    @DisplayName("边界：纯键无值的 'x=' 被 Properties 规则识别并转换为 {\"x\":\"\"}")
    void bareKeyWithEqualsIsValidProperties() {
        // "x=" 是合法的 Properties 格式（键非空，值为空）
        final String result = AnyParser.convert("x=");
        // Properties 验证通过，应返回包含 "x" 键的结果
        assertAll(
                () -> assertFalse(result.isEmpty(), "x= 应被 Properties 规则识别"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON"),
                () -> assertTrue(result.contains("\"x\""), "结果应包含键名 x")
        );
    }

    @Test
    @DisplayName("混淆：YAML scalar 以 '<' 开头不应被 XML 标识")
    void yamlScalarWithAngleBracketNotXml() {
        // `<key: value` 满足 startsWith('<')，但 isXml 会因 XML 解析失败返回 false
        // 此时应降级到 YAML 识别（如果有 YAML 特征）
        final String input = "<key: value";
        final String result = AnyParser.convert(input);
        // YAML 识别需要 YAML_LIST_PATTERN 或 YAML_MAPPING_PATTERN 或 YAML_DOCUMENT_PATTERN
        // "<key: value" 匹配 YAML_MAPPING_PATTERN，应走 YAML 转换
        assertAll(
                () -> assertFalse(result.isEmpty(), "YAML 标量不应被 XML 误吃"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON")
        );
    }

    @Test
    @DisplayName("混淆：类似 URL 参数的 YAML 文档优先按 YAML 识别（规则顺序）")
    void yamlLikeUrlParamsDetectedAsYaml() {
        // "a: 1\nb: 2" 同时有 ':'（YAML）和 '&'不存在但有换行，
        // YAML 在 DETECT_RULES 中排在 URL_PARAMS 前面
        final String input = "a: 1\nb: 2";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "YAML 格式应被优先识别"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON")
        );
    }

    @Test
    @DisplayName("混淆：类似 Base64 的有效 YAML 优先按 YAML 识别")
    void yamlNotMistakenForBase64() {
        // 纯英文字母+数组的 YAML mapping（如 "a: bcdefghijklmnop"）
        // 长度足够可能匹配 Base64 regex，但 YAML 排在 Base64 前面
        final String input = "label: ThisIsAValidBase64String==";
        final String result = AnyParser.convert(input);
        // YAML 先于 Base64 被检测，应走 YAML
        assertFalse(result.isEmpty(), "YAML 应优先于 Base64 被识别");
    }

    // --------------- 边界 ---------------

    @Test
    @DisplayName("边界：多行 Properties 格式（带注释）被正确识别")
    void multiLinePropertiesWithComments() {
        final String input = "# comment\nkey1=value1\nkey2=value2";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "含注释的多行 Properties 应被识别"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON"),
                () -> assertTrue(result.contains("key1"), "结果应包含键名")
        );
    }

    @Test
    @DisplayName("边界：带段标题的 TOML 被正确识别")
    void tomlWithSections() {
        final String input = "[database]\nhost = \"localhost\"\nport = 5432";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "带段标题的 TOML 应被识别"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON"),
                () -> assertTrue(result.contains("database"), "结果应包含段名")
        );
    }

    @Test
    @DisplayName("边界：URL 参数包含特殊字符时被正确编码/解码")
    void urlParamsWithSpecialCharacters() {
        final String input = "name=%E4%B8%AD%E6%96%87&flag";
        final String result = AnyParser.convert(input);
        assertAll(
                () -> assertFalse(result.isEmpty(), "URL 参数应被识别"),
                () -> assertTrue(JSON.isValid(result), "结果应为合法 JSON"),
                () -> assertTrue(result.contains("name"), "结果应包含参数名")
        );
    }

    @Test
    @DisplayName("边界：超长 Base64 输入不被误判为其他格式")
    void longBase64OnlyMatchesBase64() {
        final String input = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZGVmZw==";
        final String result = AnyParser.convert(input);
        assertFalse(result.isEmpty(), "超长 Base64 应被识别并解码");
    }

    @Test
    @DisplayName("边界：正斜杠开头的路径文本不被 Base64 正则误匹配")
    void pathTextNotMistakenForBase64() {
        // / 是合法 Base64 字符，但 looksLikeBase64 已增加首字符 '/' 排除
        assertTrue(AnyParser.convert("/api/data").isEmpty(),
                "正斜杠路径文本应返回空串，不匹配任何格式");
    }
}
