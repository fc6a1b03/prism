package com.acme.prism.core.parser.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 转换器单元测试
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
class SqlConverterTest {

    private final SqlConverter converter = new SqlConverter();

    @Test
    @DisplayName("正常：单对象生成 CREATE TABLE 与 INSERT")
    void generatesCreateTableAndInsert() {
        final String sql = converter.convert("{\"id\":1,\"name\":\"张三\"}");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `data`"), "应生成建表语句");
        assertTrue(sql.contains("`id` BIGINT"), "整数列应推断为 BIGINT");
        assertTrue(sql.contains("`name` VARCHAR(255)"), "字符串列应推断为 VARCHAR");
        assertTrue(sql.contains("INSERT INTO `data`"), "应生成插入语句");
        assertTrue(sql.contains("'张三'"), "中文值应保留");
    }

    @Test
    @DisplayName("正常：数组生成多行 INSERT")
    void generatesMultipleInserts() {
        final String sql = converter.convert("[{\"a\":1},{\"a\":2}]");
        final long insertCount = sql.lines().filter(line -> line.startsWith("INSERT INTO")).count();
        assertEquals(2, insertCount, "每个对象元素应生成一条 INSERT");
    }

    @Test
    @DisplayName("正常：字符串单引号转义")
    void escapesSingleQuotes() {
        final String sql = converter.convert("{\"a\":\"it's\"}");
        assertTrue(sql.contains("'it''s'"), "字符串内单引号应双写转义");
    }

    @Test
    @DisplayName("正常：布尔与对象类型推断")
    void infersBooleanAndObjectTypes() {
        final String sql = converter.convert("{\"ok\":true,\"meta\":{\"x\":1}}");
        assertTrue(sql.contains("`ok` TINYINT(1)"), "布尔列应推断为 TINYINT");
        assertTrue(sql.contains("`meta` TEXT"), "对象列应推断为 TEXT");
    }

    @Test
    @DisplayName("边界：空数组返回空串")
    void returnsEmptyForEmptyArray() {
        assertEquals("", converter.convert("[]"));
    }

    @Test
    @DisplayName("异常：非法输入原样返回")
    void returnsInputOnInvalid() {
        final String garbage = "not json";
        assertEquals(garbage, converter.convert(garbage));
    }
}
