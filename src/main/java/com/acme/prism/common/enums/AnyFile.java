package com.acme.prism.common.enums;

import java.util.Locale;

/**
 * 任何文件枚举
 *
 * @author 拒绝者
 * @date 2025-04-21
 */
public enum AnyFile {
    /**
     * 对象符号
     */
    JSON,
    /**
     * 实体类
     */
    CLASS,
    /**
     * 记录类
     */
    RECORD,
    /**
     * 可扩展置标语言
     */
    XML,
    /**
     * 格式
     */
    YAML,
    /**
     * 汤姆
     */
    TOML,
    /**
     * 特性
     */
    PROPERTIES,
    /**
     * URL参数
     */
    URL_PARAMS,
    /**
     * 逗号分隔值
     */
    CSV,
    /**
     * 工作簿
     */
    XLSX,
    /**
     * 结构化查询语言
     */
    SQL,
    /**
     * Base64编码
     */
    BASE64,
    /**
     * Markdown表格
     */
    MARKDOWN,
    /**
     * 单文本
     */
    TEXT;

    /**
     * 文件类型拓展名
     *
     * @return {@link String }
     */
    public String extension() {
        return switch (this) {
            case CLASS, RECORD -> "java";
            case SQL -> "sql";
            case MARKDOWN -> "md";
            case JSON, XML, YAML, TOML, CSV, XLSX, PROPERTIES -> this.name().toLowerCase(Locale.ROOT);
            default -> "txt";
        };
    }

    /**
     * 是编辑
     *
     * @return boolean
     */
    public boolean isEditor() {
        return this != AnyFile.TEXT && this != AnyFile.JSON && this != AnyFile.XLSX;
    }

    /**
     * 是表格
     *
     * @return boolean
     */
    public boolean isTable() {
        return this == AnyFile.XLSX;
    }

    /**
     * 是单选框
     *
     * @return boolean
     */
    public boolean isRadio() {
        return this != AnyFile.TEXT && this != AnyFile.JSON;
    }
}