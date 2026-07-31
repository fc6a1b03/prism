package com.acme.prism.ui.statusbar;

import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.Clipboard;
import com.acme.prism.core.notice.Notifier;
import com.alibaba.fastjson2.JSON;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * 状态栏 JSON 路径显示部件：实时显示光标所在 JsonPath，点击复制到剪贴板。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
public class JsonPathStatusBarWidget implements CustomStatusBarWidget, CaretListener {

    /** JSON 文件扩展名（小写） */
    private static final String JSON_EXTENSION = "json";
    /** 无有效路径时的占位显示 */
    private static final String EMPTY_PATH = "Prism";
    /** 路径解析内容缓存上限（字符），超过不缓存，避免大文件占用内存 */
    private static final int CACHE_MAX_CHARS = 500 * 1024;

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");

    /**
     * 单条目区间缓存：同一时刻只有一个活动编辑器，内容不变时复用区间表，避免每次光标移动全量重扫。
     * volatile 保证跨线程可见性（光标事件在 EDT，读取在 getText 也可能在 EDT，双保险）。
     */
    private static volatile String cachedText;
    private static volatile List<NodeRange> cachedRanges;

    private final Disposable disposable = Disposer.newDisposable("Prism.JsonPathWidget");
    private JLabel label;
    private Project project;
    private StatusBar statusBar;
    private Editor trackedEditor;

    @Override
    public @NotNull String ID() {
        return "Prism.JsonPath";
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return null;
    }

    @Override
    public JComponent getComponent() {
        if (Objects.isNull(label)) {
            label = new JLabel(EMPTY_PATH);
            label.setForeground(UIUtil.getLabelForeground());
            label.setFont(UIUtil.getLabelFont());
            label.setToolTipText(BUNDLE.getString("json.path.tooltip"));
            // 点击复制当前路径到剪贴板
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(final MouseEvent e) {
                    final String current = label.getText();
                    if (StrUtil.isEmpty(current) || EMPTY_PATH.equals(current)) {
                        return;
                    }
                    Clipboard.copy(current);
                    Notifier.notifyInfo(BUNDLE.getString("json.path.copied") + current, project);
                }
            });
        }
        return label;
    }

    @Override
    public void install(@NotNull final StatusBar bar) {
        this.statusBar = bar;
        this.project = bar.getProject();
        if (Objects.nonNull(project)) {
            // 订阅编辑器切换，切换时重新绑定 caret 监听（避免全局 addCaretListener 的 deprecated API）
            project.getMessageBus().connect(disposable)
                    .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                        @Override
                        public void selectionChanged(@NotNull final FileEditorManagerEvent event) {
                            bindEditor(FileEditorManager.getInstance(project).getSelectedTextEditor());
                        }
                    });
            bindEditor(FileEditorManager.getInstance(project).getSelectedTextEditor());
        }
    }

    @Override
    public void dispose() {
        unbindEditor();
        Disposer.dispose(disposable);
    }

    private void bindEditor(final Editor editor) {
        unbindEditor();
        if (Objects.nonNull(editor)) {
            editor.getCaretModel().addCaretListener(this);
            trackedEditor = editor;
        }
        refresh(editor);
    }

    private void unbindEditor() {
        if (Objects.nonNull(trackedEditor)) {
            trackedEditor.getCaretModel().removeCaretListener(this);
            trackedEditor = null;
        }
    }

    @Override
    public void caretPositionChanged(@NotNull final CaretEvent event) {
        refresh(event.getEditor());
    }

    private void refresh(final Editor editor) {
        if (Objects.isNull(label) || Objects.isNull(project) || Objects.isNull(editor)) {
            return;
        }
        final VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (Objects.isNull(file) || !JSON_EXTENSION.equalsIgnoreCase(file.getExtension())) {
            label.setText(EMPTY_PATH);
            return;
        }
        final String text = editor.getDocument().getText();
        if (text.isEmpty()) {
            label.setText(EMPTY_PATH);
            return;
        }
        final String path = resolveJsonPath(text, editor.getCaretModel().getOffset());
        label.setText(Objects.isNull(path) || path.isEmpty() ? EMPTY_PATH : path);
    }

    /**
     * 从文本和光标位置解析 JSON 路径。
     *
     * <p>算法：解析 JSON 树校验合法性后，用括号匹配扫描原文，为每个键/数组元素构建
     * [start, end) 文本区间与路径映射，找到包含光标 offset 的最深（最短）区间。
     *
     * <p>缓存：文本不变时复用上次构建的区间表，避免光标频繁移动导致重复全量扫描。
     */
    static String resolveJsonPath(final String text, final int offset) {
        if (offset <= 0 || offset > text.length()) {
            return "";
        }
        try {
            JSON.parse(text);
        } catch (final Exception ignored) {
            return "";
        }
        // 缓存命中：文本未变且未超上限，直接复用区间表
        List<NodeRange> ranges = text.length() <= CACHE_MAX_CHARS ? cachedRanges : null;
        if (Objects.isNull(ranges) || !text.equals(cachedText)) {
            ranges = new ArrayList<>();
            buildRanges(text, 0, text.length(), "$", ranges);
            if (text.length() <= CACHE_MAX_CHARS) {
                cachedText = text;
                cachedRanges = ranges;
            }
        }
        NodeRange best = null;
        for (final NodeRange range : ranges) {
            if (range.start <= offset && offset < range.end
                    && (Objects.isNull(best) || range.length() < best.length())) {
                best = range;
            }
        }
        return Objects.isNull(best) ? "" : best.path;
    }

    /**
     * 节点文本区间：start 起、end 止，path 为完整 JsonPath。
     */
    private record NodeRange(int start, int end, String path) {
        int length() {
            return this.end - this.start;
        }
    }

    private static void buildRanges(final String text, final int start, final int end,
                                    final String path, final List<NodeRange> ranges) {
        int pos = start;
        while (pos < end) {
            final char c = text.charAt(pos);
            if (c == '{') {
                final int matched = findMatching(text, pos, '}');
                if (matched < 0) {
                    return;
                }
                ranges.add(new NodeRange(pos, matched + 1, path));
                buildRanges(text, pos + 1, matched, path, ranges);
                pos = matched + 1;
            } else if (c == '[') {
                final int matched = findMatching(text, pos, ']');
                if (matched < 0) {
                    return;
                }
                ranges.add(new NodeRange(pos, matched + 1, path));
                // 逐个数组元素处理并附加 [index]
                int elemPos = pos + 1;
                int idx = 0;
                while (elemPos < matched) {
                    while (elemPos < matched
                            && (Character.isWhitespace(text.charAt(elemPos)) || text.charAt(elemPos) == ',')) {
                        elemPos++;
                    }
                    if (elemPos >= matched) {
                        break;
                    }
                    final char elemChar = text.charAt(elemPos);
                    if (elemChar == '{' || elemChar == '[') {
                        final int elemMatch = findMatching(text, elemPos, elemChar == '{' ? '}' : ']');
                        if (elemMatch < 0) {
                            return;
                        }
                        ranges.add(new NodeRange(elemPos, elemMatch + 1, path + "[" + idx + "]"));
                        buildRanges(text, elemPos, elemMatch, path + "[" + idx + "]", ranges);
                        elemPos = elemMatch + 1;
                    } else {
                        final int elemEnd = findValueEnd(text, elemPos, matched);
                        elemPos = Math.max(elemEnd, elemPos + 1);
                    }
                    idx++;
                }
                pos = matched + 1;
            } else if (c == '"') {
                final int keyEnd = text.indexOf('"', pos + 1);
                if (keyEnd < 0) {
                    return;
                }
                final String key = text.substring(pos + 1, keyEnd);
                // 跳过 ":" 定位值起点
                int valueStart = keyEnd + 1;
                while (valueStart < end && (text.charAt(valueStart) == ':' || Character.isWhitespace(text.charAt(valueStart)))) {
                    valueStart++;
                }
                if (valueStart >= end) {
                    return;
                }
                // 确定值的结束位置
                final int valueEnd = findValueEnd(text, valueStart, end);
                ranges.add(new NodeRange(pos, valueEnd, path + "." + key));
                // 值内部是对象/数组时递归（路径带 key）
                if (text.charAt(valueStart) == '{' || text.charAt(valueStart) == '[') {
                    buildRanges(text, valueStart, valueEnd - 1, path + "." + key, ranges);
                }
                pos = valueEnd;
            } else {
                pos++;
            }
        }
    }

    /**
     * 从 start 位置开始查找匹配的闭合括号（处理嵌套与字符串）。
     */
    private static int findMatching(final String text, final int start, final char close) {
        final char open = close == '}' ? '{' : '[';
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 查找值（可能是对象/数组/标量）的结束位置。
     */
    private static int findValueEnd(final String text, final int valueStart, final int end) {
        final char first = text.charAt(valueStart);
        if (first == '{' || first == '[') {
            final int matched = findMatching(text, valueStart, first == '{' ? '}' : ']');
            return matched < 0 ? end : matched + 1;
        }
        if (first == '"') {
            final int quoteEnd = text.indexOf('"', valueStart + 1);
            return quoteEnd < 0 ? end : quoteEnd + 1;
        }
        // 标量：到下一个逗号或闭合括号或空白
        int pos = valueStart;
        while (pos < end) {
            final char c = text.charAt(pos);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        return pos;
    }
}
