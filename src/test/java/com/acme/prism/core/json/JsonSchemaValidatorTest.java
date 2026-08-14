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

    @Test
    @DisplayName("正常：patternProperties 动态键名校验")
    void validatesPatternProperties() {
        // learnjsonschema 2020-12 案例：小写键必须为整数
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"foo\":1,\"bar\":2}",
                "{\"type\":\"object\",\"patternProperties\":{\"^[a-z]+$\":{\"type\":\"integer\"}}}"
        );
        assertTrue(ok.issues().isEmpty(), "匹配动态键名且类型正确应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"foo\":\"x\"}",
                "{\"type\":\"object\",\"patternProperties\":{\"^[a-z]+$\":{\"type\":\"integer\"}}}"
        );
        assertEquals(1, bad.issues().size());
        assertEquals("$.foo", bad.issues().getFirst().path());
    }

    @Test
    @DisplayName("正常：additionalProperties false 拒绝未知字段")
    void rejectsAdditionalProperties() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"name\":\"x\",\"extra\":1}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("additionalProperties")),
                "未声明字段应被拒绝");

        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"name\":\"x\"}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}"
        );
        assertTrue(ok.issues().isEmpty(), "仅声明字段应通过");
    }

    @Test
    @DisplayName("正常：const 单一值约束")
    void validatesConst() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":\"fixed\"}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"const\":\"fixed\"}}}"
        );
        assertTrue(ok.issues().isEmpty());

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"v\":\"other\"}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"const\":\"fixed\"}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("const")));
    }

    @Test
    @DisplayName("正常：prefixItems 数组元组校验（2020-12）")
    void validatesPrefixItems() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"pair\":[\"a\",1]}",
                "{\"type\":\"object\",\"properties\":{\"pair\":{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}"
        );
        assertTrue(ok.issues().isEmpty(), "元组位置类型匹配应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"pair\":[1,\"a\"]}",
                "{\"type\":\"object\",\"properties\":{\"pair\":{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}"
        );
        assertEquals(2, bad.issues().size(), "两个位置类型均不匹配应各报一项");
    }

    @Test
    @DisplayName("正常：const 数值相等（1 与 1.0 视为相等）")
    void validatesConstNumericEquality() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":1.0}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"const\":1}}}"
        );
        assertTrue(ok.issues().isEmpty(), "数值 const 应按数值相等比较");
    }

    @Test
    @DisplayName("正常：additionalProperties 为 schema 时校验未知字段")
    void validatesAdditionalPropertiesSchema() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"name\":\"x\",\"extra\":1}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":{\"type\":\"integer\"}}"
        );
        assertTrue(ok.issues().isEmpty(), "未知字段符合子 schema 应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"name\":\"x\",\"extra\":\"str\"}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":{\"type\":\"integer\"}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.path().equals("$.extra")),
                "未知字段不符合子 schema 应报错");
    }

    @Test
    @DisplayName("正常：not 否定约束")
    void validatesNot() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":\"a\"}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"not\":{\"type\":\"number\"}}}}"
        );
        assertTrue(ok.issues().isEmpty(), "不匹配否定约束应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"v\":5}",
                "{\"type\":\"object\",\"properties\":{\"v\":{\"not\":{\"type\":\"number\"}}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("not")));
    }

    @Test
    @DisplayName("正常：if 匹配时应用 then，通过")
    void appliesThenWhenIfMatches() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"country\":\"US\",\"postal\":\"12345\"}",
                "{\"type\":\"object\",\"if\":{\"properties\":{\"country\":{\"const\":\"US\"}}},\"then\":{\"required\":[\"postal\"]}}"
        );
        assertTrue(ok.issues().isEmpty(), "if 匹配且 then 满足应通过");
    }

    @Test
    @DisplayName("异常：if 匹配时应用 then，then 失败被检出")
    void reportsThenFailure() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"country\":\"US\"}",
                "{\"type\":\"object\",\"if\":{\"properties\":{\"country\":{\"const\":\"US\"}}},\"then\":{\"required\":[\"postal\"]}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("postal")),
                "if 匹配但 then 不满足应检出 postal 缺失");
    }

    @Test
    @DisplayName("异常：if 不匹配时应用 else，else 失败被检出")
    void reportsElseFailure() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"country\":\"CN\"}",
                "{\"type\":\"object\",\"if\":{\"properties\":{\"country\":{\"const\":\"US\"}}},\"else\":{\"required\":[\"province\"]}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("province")),
                "if 不匹配且 else 不满足应检出 province 缺失");
    }

    @Test
    @DisplayName("正常：if 不匹配且无 else 时通过")
    void passesWhenIfMismatchesWithoutElse() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"country\":\"CN\"}",
                "{\"type\":\"object\",\"if\":{\"properties\":{\"country\":{\"const\":\"US\"}}},\"then\":{\"required\":[\"postal\"]}}"
        );
        assertTrue(ok.issues().isEmpty(), "if 不匹配且无 else 应通过");
    }

    @Test
    @DisplayName("边界：无 if 时 then/else 被忽略")
    void ignoresThenWithoutIf() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":1}",
                "{\"type\":\"object\",\"then\":{\"required\":[\"postal\"]}}"
        );
        assertTrue(ok.issues().isEmpty(), "无 if 时 then 应被忽略（标准语义）");
    }

    @Test
    @DisplayName("正常：contains 至少一个元素匹配时通过")
    void passesContains() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"nums\":[1,2,\"x\"]}",
                "{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"}}}}"
        );
        assertTrue(ok.issues().isEmpty(), "数组含匹配元素应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"nums\":[1,2]}",
                "{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"}}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("contains")));
    }

    @Test
    @DisplayName("正常：minContains 与 maxContains 数量约束")
    void validatesContainsBounds() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"nums\":[\"a\",\"b\"]}",
                "{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"minContains\":2,\"maxContains\":3}}}"
        );
        assertTrue(ok.issues().isEmpty(), "2 个匹配在 [2,3] 区间内应通过");

        final ValidationOutcome tooFew = JsonSchemaValidator.validate(
                "{\"nums\":[\"a\",1]}",
                "{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"minContains\":2}}}"
        );
        assertTrue(tooFew.issues().stream().anyMatch(issue -> issue.message().contains("contains")));

        final ValidationOutcome tooMany = JsonSchemaValidator.validate(
                "{\"nums\":[\"a\",\"b\",\"c\"]}",
                "{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"maxContains\":2}}}"
        );
        assertTrue(tooMany.issues().stream().anyMatch(issue -> issue.message().contains("maxContains")));
    }

    @Test
    @DisplayName("正常：propertyNames 校验键名格式")
    void validatesPropertyNames() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"user_name\":1}",
                "{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^[a-z_]+$\"}}"
        );
        assertTrue(ok.issues().isEmpty(), "键名匹配正则应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"UserName\":1}",
                "{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^[a-z_]+$\"}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("propertyNames")));
    }

    @Test
    @DisplayName("正常：dependentRequired 字段联动必填")
    void validatesDependentRequired() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"creditCard\":\"1234\",\"billing\":\"addr\"}",
                "{\"type\":\"object\",\"dependentRequired\":{\"creditCard\":[\"billing\"]}}"
        );
        assertTrue(ok.issues().isEmpty(), "触发键存在且联动键齐全应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"creditCard\":\"1234\"}",
                "{\"type\":\"object\",\"dependentRequired\":{\"creditCard\":[\"billing\"]}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("dependentRequired")));

        final ValidationOutcome noTrigger = JsonSchemaValidator.validate(
                "{\"name\":\"x\"}",
                "{\"type\":\"object\",\"dependentRequired\":{\"creditCard\":[\"billing\"]}}"
        );
        assertTrue(noTrigger.issues().isEmpty(), "触发键不存在时联动约束不生效");
    }

    @Test
    @DisplayName("正常：dependentSchemas 存在触发键时校验依赖 schema")
    void validatesDependentSchemas() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"creditCard\":\"1234\",\"billing\":\"addr\"}",
                "{\"type\":\"object\",\"dependentSchemas\":{\"creditCard\":{\"required\":[\"billing\"]}}}"
        );
        assertTrue(ok.issues().isEmpty(), "依赖 schema 满足应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"creditCard\":\"1234\"}",
                "{\"type\":\"object\",\"dependentSchemas\":{\"creditCard\":{\"required\":[\"billing\"]}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("dependentSchemas")));
    }

    @Test
    @DisplayName("正常：$ref 引用 $defs 定义校验通过")
    void passesRefToDefs() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"user\":{\"name\":\"x\",\"age\":18}}",
                "{\"type\":\"object\",\"properties\":{\"user\":{\"$ref\":\"#/$defs/user\"}},\"$defs\":{\"user\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},\"required\":[\"name\"]}}}"
        );
        assertTrue(ok.issues().isEmpty(), "$ref 目标约束满足应通过");
    }

    @Test
    @DisplayName("异常：$ref 目标约束不满足被检出")
    void detectsRefConstraintViolation() {
        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"user\":{\"age\":18}}",
                "{\"type\":\"object\",\"properties\":{\"user\":{\"$ref\":\"#/$defs/user\"}},\"$defs\":{\"user\":{\"type\":\"object\",\"required\":[\"name\"]}}}"
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("name")),
                "$ref 目标 required 缺失应报错");
    }

    @Test
    @DisplayName("正常：$ref 与兄弟关键字共存（2020-12 语义）")
    void refCoexistsWithSiblingKeywords() {
        // 目标定义 minLength=3，兄弟 maxLength=5：两者合并生效
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":\"abc\"}",
                "{\"$defs\":{\"len\":{\"minLength\":3}},\"properties\":{\"v\":{\"$ref\":\"#/$defs/len\",\"maxLength\":5}}}"
        );
        assertTrue(ok.issues().isEmpty(), "长度 3 在 [3,5] 内应通过");

        final ValidationOutcome tooShort = JsonSchemaValidator.validate(
                "{\"v\":\"ab\"}",
                "{\"$defs\":{\"len\":{\"minLength\":3}},\"properties\":{\"v\":{\"$ref\":\"#/$defs/len\",\"maxLength\":5}}}"
        );
        assertTrue(tooShort.issues().stream().anyMatch(issue -> issue.message().contains("过短")));

        final ValidationOutcome tooLong = JsonSchemaValidator.validate(
                "{\"v\":\"abcdef\"}",
                "{\"$defs\":{\"len\":{\"minLength\":3}},\"properties\":{\"v\":{\"$ref\":\"#/$defs/len\",\"maxLength\":5}}}"
        );
        assertTrue(tooLong.issues().stream().anyMatch(issue -> issue.message().contains("过长")));
    }

    @Test
    @DisplayName("正常：自引用递归 Schema（树结构）")
    void supportsSelfReferentialSchema() {
        final String schema = "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"},\"next\":{\"$ref\":\"#\"}}}";
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"value\":1,\"next\":{\"value\":2,\"next\":{\"value\":3}}}",
                schema
        );
        assertTrue(ok.issues().isEmpty(), "自引用递归结构应通过");

        final ValidationOutcome bad = JsonSchemaValidator.validate(
                "{\"value\":1,\"next\":{\"value\":\"x\"}}",
                schema
        );
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("类型不匹配")));
    }

    @Test
    @DisplayName("边界：引用环被截断不死循环（A 引 B 且 B 引 A）")
    void truncatesRefCycle() {
        final String schema = "{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},\"b\":{\"$ref\":\"#/$defs/a\",\"type\":\"integer\"}},\"$ref\":\"#/$defs/a\"}";
        // 环解析 16 层截断，剩余 type=integer 生效；数字通过、字符串失败（不死循环）
        final ValidationOutcome ok = JsonSchemaValidator.validate("42", schema);
        assertTrue(ok.issues().isEmpty(), "环截断后整数应通过");
        final ValidationOutcome bad = JsonSchemaValidator.validate("\"x\"", schema);
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("类型不匹配")));
    }

    @Test
    @DisplayName("异常：非法 $ref 报失败项且不中断校验")
    void reportsUnresolvableRef() {
        final ValidationOutcome outcome = JsonSchemaValidator.validate(
                "{\"v\":1}",
                "{\"properties\":{\"v\":{\"$ref\":\"#/$defs/missing\"}}}"
        );
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("$ref")),
                "无法解析的 $ref 应报失败项");
    }

    @Test
    @DisplayName("正常：$ref 指针支持嵌套路径与数组索引")
    void refSupportsNestedPointer() {
        final String schema = "{\"properties\":{\"v\":{\"$ref\":\"#/$defs/deep/0\"}},\"$defs\":{\"deep\":[{\"type\":\"integer\"}]}}";
        final ValidationOutcome ok = JsonSchemaValidator.validate("{\"v\":1}", schema);
        assertTrue(ok.issues().isEmpty(), "数组索引指针目标应生效");
        final ValidationOutcome bad = JsonSchemaValidator.validate("{\"v\":\"x\"}", schema);
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("类型不匹配")));
    }

    @Test
    @DisplayName("正常：$ref 指针段转义（~1→/，~0→~）")
    void refDecodesEscapedSegments() {
        final String schema = "{\"properties\":{\"v\":{\"$ref\":\"#/$defs/a~1b\"}},\"$defs\":{\"a/b\":{\"type\":\"integer\"}}}";
        final ValidationOutcome ok = JsonSchemaValidator.validate("{\"v\":1}", schema);
        assertTrue(ok.issues().isEmpty(), "转义段应正确还原并解析");
    }

    @Test
    @DisplayName("正常：$ref 引用 definitions（Draft 兼容别名）")
    void refSupportsDefinitionsAlias() {
        final ValidationOutcome ok = JsonSchemaValidator.validate(
                "{\"v\":\"x\"}",
                "{\"properties\":{\"v\":{\"$ref\":\"#/definitions/name\"}},\"definitions\":{\"name\":{\"type\":\"string\"}}}"
        );
        assertTrue(ok.issues().isEmpty(), "definitions 别名应可解析");
    }

    @Test
    @DisplayName("异常：数组元素 $ref 递归校验")
    void refValidatesArrayElements() {
        final String schema = "{\"properties\":{\"items\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/item\"}}},\"$defs\":{\"item\":{\"type\":\"object\",\"required\":[\"id\"]}}}";
        final ValidationOutcome ok = JsonSchemaValidator.validate("{\"items\":[{\"id\":1},{\"id\":2}]}", schema);
        assertTrue(ok.issues().isEmpty(), "数组元素满足 $ref 目标应通过");
        final ValidationOutcome bad = JsonSchemaValidator.validate("{\"items\":[{\"id\":1},{\"name\":\"x\"}]}", schema);
        assertTrue(bad.issues().stream().anyMatch(issue -> issue.message().contains("id")),
                "缺失 id 的元素应报错");
    }

    @Test
    @DisplayName("异常：$ref 指针数组索引越界报失败项而非崩溃")
    void refOutOfBoundsIndexReportsIssue() {
        // 越界索引：$defs/a 仅 1 个元素，指针指向索引 5——应报无法解析失败项，不抛异常
        final String schema = "{\"properties\":{\"v\":{\"$ref\":\"#/$defs/a/5\"}},\"$defs\":{\"a\":[{\"type\":\"integer\"}]}}";
        final ValidationOutcome outcome = JsonSchemaValidator.validate("{\"v\":1}", schema);
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("$ref")),
                "越界索引应报无法解析 $ref 失败项");
    }

    @Test
    @DisplayName("异常：$ref 指针负索引报失败项而非崩溃")
    void refNegativeIndexReportsIssue() {
        final String schema = "{\"properties\":{\"v\":{\"$ref\":\"#/$defs/a/-1\"}},\"$defs\":{\"a\":[{\"type\":\"integer\"}]}}";
        final ValidationOutcome outcome = JsonSchemaValidator.validate("{\"v\":1}", schema);
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.message().contains("$ref")),
                "负索引应报无法解析 $ref 失败项");
    }
}
