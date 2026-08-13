package com.acme.prism.core.minimap;

import com.acme.prism.core.settings.PluginSettingsState;
import com.acme.prism.core.settings.ProjectDisposableService;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编辑器工厂监听器：编辑器创建时在右侧挂载代码缩略图面板，释放时移除并 dispose。
 *
 * @author 拒绝者
 * @date 2026-07-20
 */
public final class MinimapEditorFactoryListener implements EditorFactoryListener {
    /**
     * 维护编辑器与缩略图面板的映射，确保释放时可被正确移除与 dispose
     */
    private static final Map<Editor, MinimapPanel> PANELS = new ConcurrentHashMap<>();
    /**
     * 维护编辑器与原始滚动条宽度的映射（隐藏时记录，恢复与卸载时使用）
     */
    private static final Map<Editor, Integer> ORIGINAL_SCROLLBAR_WIDTHS = new ConcurrentHashMap<>();

    @Override
    public void editorCreated(@NotNull final EditorFactoryEvent event) {
        final var editor = event.getEditor();
        final var project = editor.getProject();
        if (Objects.isNull(project) || !PluginSettingsState.getInstance().minimapEnabled) {
            return;
        }
        // 弹窗（校验/转换对话框）内编辑器不挂 minimap：高度受限且无导航价值；
        // 创建时组件可能尚未挂入窗口，漏判场景由 createPanel 的 EDT 事件裁决兜底移除
        if (isInDialog(editor)) {
            return;
        }
        PANELS.computeIfAbsent(editor, e -> createPanel(e, project));
    }

    @Override
    public void editorReleased(@NotNull final EditorFactoryEvent event) {
        removePanel(event.getEditor());
    }

    /**
     * 设置开关变更后同步全部已打开编辑器的挂载状态（开启补挂、关闭卸载）。
     * <p>由设置面板 apply 时调用；对没有项目的编辑器直接跳过。</p>
     */
    public static void syncAllEditors() {
        final var enabled = PluginSettingsState.getInstance().minimapEnabled;
        for (final var editor : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
            final var project = editor.getProject();
            // 弹窗内编辑器跳过：不补挂 minimap（Prism 工具窗口主编辑器属主窗口，不受影响）
            if (Objects.isNull(project) || isInDialog(editor)) {
                continue;
            }
            if (enabled) {
                PANELS.computeIfAbsent(editor, e -> createPanel(e, project));
            } else {
                removePanel(editor);
            }
        }
    }

    /**
     * 按开关状态同步全部已打开编辑器的原始滚动条可见性（右键菜单切换时调用）。
     */
    public static void syncScrollBarVisibility() {
        final var hide = PluginSettingsState.getInstance().minimapHideOriginalScrollBar;
        for (final var editor : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
            // 弹窗内编辑器无 minimap，不隐藏其滚动条
            if (Objects.nonNull(editor.getProject()) && !isInDialog(editor)) {
                applyScrollBarVisibility(editor, hide);
            }
        }
    }

    /**
     * 编辑器是否位于弹窗（{@link Dialog}）内，如校验/转换对话框的 EditorTextField。
     * <p>按顶层窗口类型判定：弹窗顶层为 JDialog，Prism 工具窗口与 IDE 编辑区顶层为
     * 主窗口（JFrame），可精确区分。弹窗编辑器不挂 minimap、不隐藏其原始滚动条。</p>
     *
     * @param editor 编辑器
     * @return 是否位于弹窗内
     */
    private static boolean isInDialog(@NotNull final Editor editor) {
        return SwingUtilities.getWindowAncestor(editor.getComponent()) instanceof Dialog;
    }

    /**
     * 应用编辑器原始滚动条可见性（滚动条组件保留但宽度归零：
     * 滚轮处理链完整保留、零占位；不用 setVisible(false)（占位残留）也不用 policy NEVER（滚轮失效））。
     *
     * @param editor 编辑器
     * @param hide   true 隐藏原始滚动条（宽度归零）；false 恢复记录的原始宽度
     */
    private static void applyScrollBarVisibility(@NotNull final Editor editor, final boolean hide) {
        final var scrollPane = ((EditorEx) editor).getScrollPane();
        final var scrollBar = scrollPane.getVerticalScrollBar();
        if (hide) {
            // 记录原始宽度（仅首次），供恢复使用
            ORIGINAL_SCROLLBAR_WIDTHS.computeIfAbsent(editor, e -> scrollBar.getPreferredSize().width);
            scrollBar.setPreferredSize(new Dimension(0, scrollBar.getPreferredSize().height));
        } else {
            final var originalWidth = ORIGINAL_SCROLLBAR_WIDTHS.get(editor);
            if (Objects.nonNull(originalWidth)) {
                scrollBar.setPreferredSize(new Dimension(originalWidth, scrollBar.getPreferredSize().height));
            }
        }
        // 强制重算布局：宽度变化必须立即反映到占位区域
        scrollPane.revalidate();
        scrollPane.repaint();
    }

    /**
     * 从编辑器卸载缩略图面板并 dispose（释放渲染器行数据缓存与图片），并恢复原始滚动条。
     *
     * @param editor 编辑器
     */
    private static void removePanel(@NotNull final Editor editor) {
        final var panel = PANELS.remove(editor);
        if (Objects.nonNull(panel)) {
            editor.getComponent().remove(panel);
            Disposer.dispose(panel);
        }
        applyScrollBarVisibility(editor, false);
        ORIGINAL_SCROLLBAR_WIDTHS.remove(editor);
    }

    /**
     * 创建并挂载缩略图面板：渲染器充当 {@link MinimapView} 传入面板；按开关隐藏原始滚动条。
     *
     * @param editor  编辑器
     * @param project 所属项目
     * @return 已挂载的缩略图面板
     */
    @NotNull
    private static MinimapPanel createPanel(@NotNull final Editor editor, @NotNull final Project project) {
        // 渲染器需先于面板构造，其重绘回调经引用转发：面板就绪后调 panel.repaint()（repaint 线程安全，渲染线程可直接触发）
        final var panelRef = new AtomicReference<MinimapPanel>();
        final var renderer = new MinimapRenderer(editor, project, () -> {
            final var panel = panelRef.get();
            if (Objects.nonNull(panel)) {
                panel.repaint();
            }
        });
        final var panel = new MinimapPanel(editor, project, renderer, ProjectDisposableService.getInstance(project));
        panelRef.set(panel);
        editor.getComponent().add(panel, BorderLayout.LINE_END);
        applyScrollBarVisibility(editor, PluginSettingsState.getInstance().minimapHideOriginalScrollBar);
        // ① EditorTextField（Prism 面板）、diff 视图等在 editorCreated 之后才完成自身滚动条配置
        //（策略与尺寸初始化会覆盖宽度归零），EDT 队尾补应用一次，保证默认隐藏不依赖创建时机；
        // ② 弹窗编辑器在 editorCreated 时组件可能尚未挂入窗口（isInDialog 漏判），已挂载 minimap——
        // 组件未显示时挂 SHOWING_CHANGED 监听，窗口显示瞬间裁决：在弹窗内则移除 minimap（含恢复滚动条）
        SwingUtilities.invokeLater(() -> {
            if (editor.isDisposed() || !PANELS.containsKey(editor)) {
                return;
            }
            if (editor.getComponent().isShowing()) {
                if (isInDialog(editor)) {
                    removePanel(editor);
                } else {
                    applyScrollBarVisibility(editor, PluginSettingsState.getInstance().minimapHideOriginalScrollBar);
                }
                return;
            }
            editor.getComponent().addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(final HierarchyEvent e) {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                            && editor.getComponent().isShowing()
                            && !editor.isDisposed()
                            && PANELS.containsKey(editor)) {
                        if (isInDialog(editor)) {
                            removePanel(editor);
                        } else {
                            applyScrollBarVisibility(editor, PluginSettingsState.getInstance().minimapHideOriginalScrollBar);
                        }
                        editor.getComponent().removeHierarchyListener(this);
                    }
                }
            });
        });
        return panel;
    }
}
