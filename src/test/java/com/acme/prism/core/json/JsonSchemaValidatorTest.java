package com.acme.prism.core.json;

import com.acme.prism.core.json.JsonSchemaValidator.ValidationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON Schema 校验器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class JsonSchemaValidatorTest {

    @Test
    @DisplayName("正常：类型匹配校验通过")
    void passesTypeMatch() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"name\":\"x\",\"age\":18}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}"
        );
        assertTrue(outcome.issues().isEmpty(), "类型匹配应校验通过");
        assertTrue(outcome.checkedCount() > 0);
    }

    @Test
    @DisplayName("异常：类型不匹配被检出")
    void detectsTypeMismatch() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"age\":\"18\"}",
                "{\"type\":\"object\",\"properties\":{\"age\":{\"type\":\"integer\"}}}"
        );
        assertEquals(1, outcome.issues().size());
        assertEquals("$.age", outcome.issues().getFirst().path());
    }

    @Test
    @DisplayName("异常：缺少必填字段被检出")
    void detectsMissingRequired() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"name\":\"x\"}",
                "{\"type\":\"object\",\"required\":[\"name\",\"age\"]}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("age")),
                "应检出缺失的必填字段 age");
    }

    @Test
    @DisplayName("正常：嵌套对象递归校验")
    void validatesNestedObjects() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"user\":{\"name\":\"x\"}}",
                "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"number\"}}}}}"
        );
        assertEquals(1, outcome.issues().size());
        assertEquals("$.user.name", outcome.issues().getFirst().path());
    }

    @Test
    @DisplayName("正常：数组元素校验")
    void validatesArrayItems() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"list\":[1,\"a\"]}",
                "{\"type\":\"object\",\"properties\":{\"list\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}}"
        );
        assertEquals(1, outcome.issues().size(), "数组中字符串元素应检出类型不匹配");
        assertEquals("$.list[1]", outcome.issues().getFirst().path());
    }

    @Test
    @DisplayName("正常：枚举校验")
    void validatesEnum() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"status\":\"unknown\"}",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"enum\":[\"active\",\"disabled\"]}}}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("枚举")),
                "不在枚举范围的值应被检出");
    }

    @Test
    @DisplayName("正常：字符串长度约束")
    void validatesStringLength() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"code\":\"abc\"}",
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"minLength\":5}}}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("过短")));
    }

    @Test
    @DisplayName("正常：数值范围约束")
    void validatesNumberRange() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"score\":150}",
                "{\"type\":\"object\",\"properties\":{\"score\":{\"type\":\"number\",\"maximum\":100}}}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("大于最大值")));
    }

    @Test
    @DisplayName("正常：正则匹配约束")
    void validatesPattern() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"email\":\"not-an-email\"}",
                "{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\",\"pattern\":\"^\\\\w+@\\\\w+\\\\.\\\\w+$\"}}}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("正则")));
    }

    @Test
    @DisplayName("正常：多类型声明任一匹配即通过")
    void supportsMultiType() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"value\":\"x\"}",
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":[\"string\",\"number\"]}}}"
        );
        assertTrue(outcome.issues().isEmpty(), "多类型中任一匹配应通过");
    }

    @Test
    @DisplayName("异常：JSON 或 Schema 解析失败返回解析失败项")
    void reportsParseFailure() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate("not json", "{}");
        assertFalse(outcome.issues().isEmpty());
        assertTrue(outcome.issues().getFirst().message().contains("解析"));
    }

    @Test
    @DisplayName("边界：空输入返回空结果")
    void handlesBlankInput() {
        assertTrue(JsonSchemaValidator.validate("", "{}").issues().isEmpty());
        assertTrue(JsonSchemaValidator.validate(null, "{}").issues().isEmpty());
    }

    @Test
    @DisplayName("异常：Schema 非对象被检出")
    void rejectsNonObjectSchema() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate("{\"a\":1}", "[]");
        assertFalse(outcome.issues().isEmpty());
        assertTrue(outcome.issues().getFirst().message().contains("Schema"));
    }
}
