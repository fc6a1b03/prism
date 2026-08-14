package com.acme.prism.core.parser.converter;

import cn.hutool.core.util.StrUtil;

/**
 * TypeScript 转换器：将 JSON 转为 TypeScript interface 定义（嵌套对象/数组平铺为多个 interface）。
 *
 * <p>复用 {@link JavaStructure} 的结构提取（字段名/嵌套类），仅类型映射与渲染不同：
 * String → string、数值 → number、Boolean → boolean、List&lt;X&gt; → X[]、Object → any；
 * 嵌套类型平铺渲染为独立 {@code export interface}，缩进 2 空格（TS 惯例）。
 * 转换失败或输入为空返回空串。</p>
 *
 * @author 拒绝者
 * @date 2026-08-14
 */
public final class TypeScriptConverter extends JavaStructure {

    /**
     * 接口缩进（2 空格，TS 惯例）
     */
    private static final String INDENT = "  ";
    /**
     * 代码构建器初始容量
     */
    private static final int CODE_BUILDER_CAPACITY = 1024;
    /**
     * 泛型起始标记
     */
    private static final char GENERIC_OPEN = '<';
    /**
     * 泛型结束标记
     */
    private static final char GENERIC_CLOSE = '>';
    /**
     * 数组后缀
     */
    private static final String ARRAY_SUFFIX = "[]";

    /**
     * 转换 JSON 为 TypeScript interface 定义。
     *
     * @param json JSON 文本
     * @return TypeScript 代码；输入为空或转换失败时返回空串
     */
    @Override
    public String convert(final String json) {
        if (StrUtil.isBlank(json)) {
            return "";
        }
        try {
            final ClassStructure root = processObject(json, DEFAULT_CLASS_NAME, Boolean.FALSE);
            final StringBuilder sb = new StringBuilder(CODE_BUILDER_CAPACITY);
            renderInterface(root, sb);
            return sb.toString().stripTrailing();
        } catch (final Exception ignored) {
            // 非法 JSON 返回空串
            return "";
        }
    }

    /**
     * 渲染单个 interface（含字段与嵌套 interface，嵌套平铺在根接口之后）。
     *
     * @param clazz 类结构
     * @param sb    代码构建器
     */
    private static void renderInterface(final ClassStructure clazz, final StringBuilder sb) {
        sb.append("export interface ").append(clazz.getClassName()).append(" {\n");
        clazz.getFields().forEach(field ->
                sb.append(INDENT).append(field.name()).append(": ").append(toTsType(field.type())).append(";\n"));
        sb.append("}\n");
        clazz.getNestedClasses().forEach(nested -> renderInterface(nested, sb));
    }

    /**
     * Java 类型映射为 TypeScript 类型（含泛型递归）。
     *
     * @param javaType Java 类型字符串（如 {@code List<String>}）
     * @return TypeScript 类型
     */
    private static String toTsType(final String javaType) {
        final int genericIndex = javaType.indexOf(GENERIC_OPEN);
        if (genericIndex >= 0 && javaType.charAt(javaType.length() - 1) == GENERIC_CLOSE) {
            return toTsType(javaType.substring(genericIndex + 1, javaType.length() - 1)) + ARRAY_SUFFIX;
        }
        return switch (javaType) {
            case "String" -> "string";
            case "Integer", "Long", "Double", "Float", "Byte", "Short" -> "number";
            case "Boolean" -> "boolean";
            case "Character" -> "string";
            case "Object" -> "any";
            // 嵌套类名 / 未知类型保持原样
            default -> javaType;
        };
    }
}
