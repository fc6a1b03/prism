package com.acme.prism.ui.error;

import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 语法错误定位工具，从 fastjson2 异常消息中提取行号和列号。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
public class JsonErrorParser {

    private static final Pattern LINE_COL_PATTERN = Pattern.compile("line (\\d+), column (\\d+)");
    /** 跳过标注的 JSON 文本最大长度（字节），防止大文件解析阻塞标注线程 */
    private static final int MAX_LENGTH = 500 * 1024;

    /**
     * 错误位置。
     *
     * @param line    行号（1-based）
     * @param column  列号（1-based）
     * @param message 错误描述
     */
    public record ErrorPosition(int line, int column, String message) {
    }

    /**
     * 尝试解析 JSON 文本，返回首个语法错误的位置。
     *
     * @param json JSON 文本
     * @return 错误位置；合法 JSON 或空文本返回 {@code null}
     */
    public static ErrorPosition parseError(final String json) {
        if (StrUtil.isEmpty(json) || json.length() > MAX_LENGTH) {
            return null;
        }
        try {
            JSON.parse(json);
            return null;
        } catch (final JSONException e) {
            return extractPosition(e.getMessage());
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * 从异常消息中提取行列信息。
     */
    static ErrorPosition extractPosition(final String message) {
        return Opt.ofBlankAble(message)
                .map(LINE_COL_PATTERN::matcher)
                .filter(Matcher::find)
                .map(m -> new ErrorPosition(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        message))
                .orElse(null);
    }
}
