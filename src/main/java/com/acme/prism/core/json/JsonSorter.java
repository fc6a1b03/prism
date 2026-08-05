package com.acme.prism.core.json;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * JSON 键排序器：递归按键名升序排序对象键（数组内对象元素同样递归处理）。
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class JsonSorter implements JsonOperation {

    /**
     * JSON 操作契约：默认按键升序排序。
     *
     * @param json 输入
     * @return 排序后的 JSON；输入非法时原样返回
     */
    @Override
    public String process(final String json) {
        return sort(json, Boolean.TRUE);
    }

    /**
     * 排序 JSON 对象键。
     *
     * @param json     输入
     * @param ascending 是否升序
     * @return 排序后的 JSON；输入非法时原样返回
     */
    public static String sort(final String json, final boolean ascending) {
        if (StrUtil.isBlank(json)) {
            return json;
        }
        try {
            final Object sorted = sortValue(JSON.parse(json), ascending);
            return JSON.toJSONString(sorted, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue).trim();
        } catch (final Exception ignored) {
            return json;
        }
    }

    /**
     * 递归排序值。
     *
     * @param value     值
     * @param ascending 是否升序
     * @return 排序后的值
     */
    private static Object sortValue(final Object value, final boolean ascending) {
        if (value instanceof final JSONObject obj) {
            // TreeMap 按比较器迭代有序，JSONObject 保持插入序
            final TreeMap<String, Object> ordered = new TreeMap<>(
                    ascending ? Comparator.<String>naturalOrder() : Comparator.<String>reverseOrder());
            for (final String key : obj.keySet()) {
                ordered.put(key, sortValue(obj.get(key), ascending));
            }
            final JSONObject sorted = new JSONObject(obj.size());
            sorted.putAll(ordered);
            return sorted;
        }
        if (value instanceof final JSONArray arr) {
            final JSONArray sorted = new JSONArray(arr.size());
            for (final Object item : arr) {
                sorted.add(sortValue(item, ascending));
            }
            return sorted;
        }
        return value;
    }
}
