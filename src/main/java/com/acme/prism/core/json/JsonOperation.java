package com.acme.prism.core.json;

import cn.hutool.core.convert.Convert;
import com.alibaba.fastjson2.JSON;

/**
 * JSON操作
 * @author 拒绝者
 * @date 2025-01-18
 */
public sealed interface JsonOperation permits
        JsonCompressor, JsonEscaper, JsonFlattener, JsonFormatter, JsonKeyConverter, JsonMockGenerator,
        JsonRepairer, JsonSchemaGenerator, JsonSearchEngine, JsonSorter, JsonUnEscaper, JsonUnflattener {
    /**
     * JSON操作
     * @param input 输入
     * @return {@link String }
     */
    default String process(final Object input) {
        return Convert.toStr(input);
    }

    /**
     * JSON操作
     * @param input 输入
     * @return {@link String }
     */
    default String process(final String input) {
        return input;
    }

    /**
     * JSON操作
     * @param input      输入
     * @param expression 表达
     * @return {@link String }
     */
    default String process(final String input, final String expression) {
        return this.process(input);
    }

    /**
     * 有效
     * @param input 输入
     * @return boolean
     */
    default boolean isValid(final Object input) {
        return this.isValid(Convert.toStr(input));
    }

    default boolean isValid(final String input) {
        return JSON.isValid(input);
    }
}