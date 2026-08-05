package com.acme.prism.core.parser.converter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SQL 转换器：将 JSON 生成 MySQL 方言的 CREATE TABLE 与 INSERT 语句。
 *
 * <p>列类型按首个出现的值推断（string/number/integer/boolean/object/array），
 * 顶层数组每个对象元素生成一条 INSERT。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public class SqlConverter implements DataFormatConverter {
    /**
     * 目标表名
     */
    private static final String TABLE_NAME = "data";

    /**
     * JSON 转 SQL。
     *
     * @param json 数据
     * @return SQL 脚本；输入非法时原样返回
     */
    @Override
    public String convert(final String json) {
        final Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (final Exception ignored) {
            return json;
        }
        final List<JSONObject> rows = switch (parsed) {
            case final JSONObject obj -> List.of(obj);
            case final JSONArray arr -> arr.stream()
                    .filter(JSONObject.class::isInstance)
                    .map(JSONObject.class::cast)
                    .toList();
            default -> List.of();
        };
        if (rows.isEmpty()) {
            return "";
        }
        return buildSql(rows);
    }

    /**
     * 构建 CREATE TABLE 与 INSERT 脚本。
     *
     * @param rows 数据行
     * @return SQL 脚本
     */
    private static String buildSql(final List<JSONObject> rows) {
        // 列集合（保持首次出现顺序）
        final LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (final JSONObject row : rows) {
            columns.addAll(row.keySet());
        }
        final StringBuilder sb = new StringBuilder(256);
        // CREATE TABLE
        sb.append("CREATE TABLE IF NOT EXISTS `").append(TABLE_NAME).append("` (\n");
        for (final String column : columns) {
            sb.append("  `").append(escapeColumn(column)).append("` ").append(columnType(rows, column)).append(",\n");
        }
        sb.setLength(sb.length() - 2);
        sb.append("\n);\n\n");
        // INSERT（每行一条）
        for (final JSONObject row : rows) {
            sb.append("INSERT INTO `").append(TABLE_NAME).append("` (");
            sb.append(columns.stream().map(SqlConverter::escapeColumn).collect(Collectors.joining(", ")));
            sb.append(") VALUES (");
            sb.append(columns.stream().map(column -> sqlValue(row.get(column))).collect(Collectors.joining(", ")));
            sb.append(");\n");
        }
        return sb.toString();
    }

    /**
     * 推断列类型（取首个非 null 值的类型）。
     *
     * @param rows   数据行
     * @param column 列名
     * @return SQL 类型
     */
    private static String columnType(final List<JSONObject> rows, final String column) {
        for (final JSONObject row : rows) {
            final Object value = row.get(column);
            if (value instanceof String) {
                return "VARCHAR(255)";
            }
            if (value instanceof Boolean) {
                return "TINYINT(1)";
            }
            if (value instanceof Integer || value instanceof Long) {
                return "BIGINT";
            }
            if (value instanceof Number) {
                return "DECIMAL(20, 6)";
            }
            if (value instanceof JSONObject || value instanceof JSONArray) {
                return "TEXT";
            }
        }
        return "TEXT";
    }

    /**
     * 值转 SQL 字面量（字符串单引号转义，对象/数组序列化为 JSON 字符串）。
     *
     * @param value 值
     * @return SQL 字面量
     */
    private static String sqlValue(final Object value) {
        if (Objects.isNull(value)) {
            return "NULL";
        }
        if (value instanceof String text) {
            return "'%s'".formatted(text.replace("'", "''"));
        }
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return "'%s'".formatted(JSON.toJSONString(value).replace("'", "''"));
        }
        return String.valueOf(value);
    }

    /**
     * 列名转义（MySQL 反引号包裹时的内部反引号转义）。
     *
     * @param column 列名
     * @return 转义后的列名
     */
    private static String escapeColumn(final String column) {
        return column.replace("`", "``");
    }
}
