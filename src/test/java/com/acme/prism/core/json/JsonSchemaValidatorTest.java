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

    @Test
    @DisplayName("正常：format 校验 email 与 date-time")
    void validatesFormat() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"email\":\"a@b.com\",\"time\":\"2026-01-01T10:00:00Z\"}",
                "{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\",\"format\":\"email\"}," +
                        "\"time\":{\"type\":\"string\",\"format\":\"date-time\"}}}"
        );
        assertTrue(ok.issues().isEmpty(), "合法 email 与日期应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"email\":\"not-an-email\",\"time\":\"yesterday\"}",
                "{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\",\"format\":\"email\"}," +
                        "\"time\":{\"type\":\"string\",\"format\":\"date-time\"}}}"
        );
        assertEquals(2, bad.issues().size(), "非法 email 与日期应各报一项");
    }

    @Test
    @DisplayName("正常：format 校验 uri 与 ipv4")
    void validatesUriAndIpv4() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"url\":\"https://example.com\",\"ip\":\"192.168.1.1\"}",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"format\":\"uri\"}," +
                        "\"ip\":{\"type\":\"string\",\"format\":\"ipv4\"}}}"
        );
        assertTrue(ok.issues().isEmpty());
    }

    @Test
    @DisplayName("正常：oneOf 恰好匹配一个")
    void validatesOneOf() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":\"x\"}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}}"
        );
        assertTrue(ok.issues().isEmpty(), "恰好匹配一个子约束应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"v\":true}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("oneOf")),
                "匹配 0 个应报 oneOf 失败");
    }

    @Test
    @DisplayName("正常：anyOf 与 allOf 组合约束")
    void validatesAnyOfAndAllOf() {
        final ValidationOutcome anyOk = JsonSchemaValidator.validate(
                "{\"v\":\"x\"}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"string\"}]}}}"
        );
        assertTrue(anyOk.issues().isEmpty(), "anyOf 至少一个匹配应通过");

        final ValidationOutcome allBad = JsonSchemaValidator.validate(
                "{\"v\":5}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"allOf\":[{\"type\":\"number\",\"minimum\":10},{\"maximum\":1}]}}}"
        );
        assertTrue(allBad.issues().stream().anyMatch(issue -> issue.message().contains("allOf")),
                "allOf 部分不满足应报失败");
    }

    @Test
    @DisplayName("正常：数组长度与元素唯一约束")
    void validatesArrayConstraints() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"list\":[1,2,3]}",
                "{\"type\":\"object\",\"properties\":{\"list\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":5,\"uniqueItems\":true}}}"
        );
        assertTrue(ok.issues().isEmpty());

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"list\":[1,1]}",
                "{\"type\":\"object\",\"properties\":{\"list\":{\"type\":\"array\",\"minItems\":3,\"uniqueItems\":true}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("重复")),
                "重复元素应报 uniqueItems 失败");
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("过少")),
                "元素过少应报 minItems 失败");
    }

    @Test
    @DisplayName("正常：对象属性数量约束")
    void validatesObjectProperties() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"a\":1,\"b\":2,\"c\":3}",
                "{\"type\":\"object\",\"maxProperties\":2}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("属性过多")));
    }

    @Test
    @DisplayName("正常：排他边界与倍数约束")
    void validatesExclusiveAndMultipleOf() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"n\":5}",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\",\"exclusiveMinimum\":5,\"multipleOf\":3}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("排他最小值")));
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("倍数")));
    }
}
