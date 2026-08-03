package com.acme.prism.core.editor.record;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编辑器状态
 * @author 拒绝者
 * @date 2025-11-04
 */
public record EditorState(Integer editorId, String content, Integer scrollOffset, Integer caretOffset) {
    /**
     * Base64 编码器
     */
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    /**
     * Base64 解码器
     */
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
    /**
     * ASCII Record Separator 分隔符（外）
     */
    private static final String SEP_OUTSIDE = "\u001E";
    /**
     * ASCII Record Separator 分隔符（内）
     */
    private static final String SEP_INTERNAL = "\u001F";
    /**
     * JsonHelper 状态密钥
     */
    public static final String JSON_HELPER_STATE_KEY = "json:helper:state";
    /**
     * 存储幂等
     */
    public static final Set<String> SAVED_MARK = ConcurrentHashMap.newKeySet();

    /**
     * 兼容构造：无滚动与光标位置（旧数据或首次创建），恢复时默认置顶。
     *
     * @param editorId 编辑器 ID
     * @param content  内容
     */
    public EditorState(final Integer editorId, final String content) {
        this(editorId, content, null, null);
    }

    /**
     * 兼容构造：带滚动位置（历史格式）。
     *
     * @param editorId     编辑器 ID
     * @param content      内容
     * @param scrollOffset 滚动位置
     */
    public EditorState(final Integer editorId, final String content, final Integer scrollOffset) {
        this(editorId, content, scrollOffset, null);
    }

    /**
     * 编码
     * @param stateList 列表
     * @return {@link String }
     */
    public static String encode(final List<EditorState> stateList) {
        final List<EditorState> states = CollUtil.emptyIfNull(stateList);
        final StringBuilder builder = new StringBuilder(states.size() * 32);
        boolean first = true;
        for (final EditorState state : states) {
            if (Objects.isNull(state)) {
                continue;
            }
            if (!first) {
                builder.append(SEP_OUTSIDE);
            }
            // content 为 null 时按空串处理，避免编码器 NPE；scrollOffset/caretOffset 为 null 时写空段
            builder.append(state.editorId)
                    .append(SEP_INTERNAL)
                    .append(BASE64_ENCODER.encodeToString(StrUtil.bytes(Objects.requireNonNullElse(state.content, ""), StandardCharsets.UTF_8)))
                    .append(SEP_INTERNAL)
                    .append(Objects.isNull(state.scrollOffset) ? "" : state.scrollOffset)
                    .append(SEP_INTERNAL)
                    .append(Objects.isNull(state.caretOffset) ? "" : state.caretOffset);
            first = false;
        }
        return builder.toString();
    }

    /**
     * 解码
     * @param raw 原始
     * @return {@link List }<{@link EditorState }>
     */
    public static List<EditorState> decode(final String raw) {
        final String[] entries = StrUtil.emptyIfNull(raw).split(SEP_OUTSIDE);
        final List<EditorState> states = new ArrayList<>(entries.length);
        for (final String entry : entries) {
            if (StrUtil.isBlank(entry)) {
                continue;
            }
            final String[] parts = entry.split(SEP_INTERNAL, -1);
            // 兼容旧数据：历史版本仅 2 段（id, content），滚动/光标位置为 null
            if (ArrayUtil.isEmpty(parts) || parts.length < 2) {
                continue;
            }
            states.add(new EditorState(
                    Convert.toInt(parts[0]),
                    StrUtil.utf8Str(BASE64_DECODER.decode(parts[1])),
                    parts.length >= 3 ? Convert.toInt(parts[2]) : null,
                    parts.length >= 4 ? Convert.toInt(parts[3]) : null
            ));
        }
        return states;
    }
}
