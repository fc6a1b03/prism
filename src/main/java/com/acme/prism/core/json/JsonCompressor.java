package com.acme.prism.core.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

/**
 * JSON压缩器
 * @author 拒绝者
 * @date 2025-01-18
 */
public final class JsonCompressor implements JsonOperation {
    @Override
    public String process(final String json) {
        try {
            return JSON.toJSONString(JSON.parse(json), JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (Exception ignored) {
            return json;
        }
    }
}