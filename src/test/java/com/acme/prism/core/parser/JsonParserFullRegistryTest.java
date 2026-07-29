package com.acme.prism.core.parser;

import com.acme.prism.common.enums.AnyFile;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonParser 注册表分发完整性测试，补充之前未覆盖的 XLSX/CSV/CLASS/RECORD 格式。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
class JsonParserFullRegistryTest {

    private static final String SAMPLE_JSON = "{\"name\":\"acme\",\"age\":18}";

    // --------------- convert 覆盖补充 ---------------

    @Test
    @DisplayName("convert：XLSX 格式 — 输出非空且为合法 JSON 文本（headers/data 结构）")
    void convertJsonToXlsx() {
        final String result = JsonParser.convert(SAMPLE_JSON, AnyFile.XLSX);
        assertAll(
                () -> assertFalse(result.isEmpty(), "XLSX 输出不应为空"),
                () -> assertTrue(JSON.isValid(result), "XLSX 输出应为合法 JSON 文本"),
                () -> assertTrue(result.contains("headers"), "XLSX 输出应包含 headers"),
                () -> assertTrue(result.contains("data"), "XLSX 输出应包含 data")
        );
    }

    @Test
    @DisplayName("convert：CSV 格式 — 输出含表头和内容行")
    void convertJsonToCsv() {
        final String result = JsonParser.convert(SAMPLE_JSON, AnyFile.CSV);
        assertAll(
                () -> assertFalse(result.isEmpty(), "CSV 输出不应为空"),
                () -> assertTrue(result.contains("name"), "CSV 表头应包含字段名"),
                () -> assertTrue(result.contains("acme"), "CSV 内容应包含字段值")
        );
    }

    @Test
    @DisplayName("convert：CLASS 格式 — 输出 Java 类源码")
    void convertJsonToClass() {
        final String result = JsonParser.convert(SAMPLE_JSON, AnyFile.CLASS);
        assertAll(
                () -> assertFalse(result.isEmpty(), "CLASS 输出不应为空"),
                () -> assertTrue(result.contains("class"), "类输出应包含 class 关键字"),
                () -> assertTrue(result.contains("String"), "类输出应包含字段类型"),
                () -> assertTrue(result.contains("name"), "类输出应包含字段名")
        );
    }

    @Test
    @DisplayName("convert：RECORD 格式 — 输出 Java record 源码")
    void convertJsonToRecord() {
        final String result = JsonParser.convert(SAMPLE_JSON, AnyFile.RECORD);
        assertAll(
                () -> assertFalse(result.isEmpty(), "RECORD 输出不应为空"),
                () -> assertTrue(result.contains("record"), "record 输出应包含 record 关键字"),
                () -> assertTrue(result.contains("String"), "record 输出应包含组件类型"),
                () -> assertTrue(result.contains("name"), "record 输出应包含组件名")
        );
    }
}
