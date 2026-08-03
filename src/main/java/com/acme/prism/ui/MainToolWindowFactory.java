package com.acme.prism.ui;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Opt;
import com.acme.prism.common.enums.SupportedLanguages;
import com.acme.prism.core.editor.JsonEditorPushProvider;
import com.acme.prism.core.editor.record.EditorState;
import com.acme.prism.core.settings.ProjectDisposableService;
import com.acme.prism.ui.editor.CustomizeEditorFactory;
import com.acme.prism.ui.editor.Editor;
import com.acme.prism.ui.panel.JsonTreePanel;
import com.acme.prism.ui.panel.MainPanel;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.acme.prism.core.editor.record.EditorState.JSON_HELPER_STATE_KEY;

/**
 * Prism 工具窗口
 *
 * @author 拒绝者
 * @date 2025-01-18
 */
public class MainToolWindowFactory implements ToolWindowFactory, DumbAware {
    /**
     * 项目名称
     */
    public static final String PROJECT_NAME = "Prism";
    /**
     * 分隔条尺寸（像素）
     */
    private static final int DIVIDER_SIZE = 8;
    /**
     * 分割窗格上部组件的缩放权重（编辑器区域优先拉伸）
     */
    private static final double EDITOR_RESIZE_WEIGHT = 1.0d;
    /**
     * 标签计数器
     */
    private static final AtomicInteger tabCounter = new AtomicInteger(0);
    /**
     * 加载资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");

    /**
     * 全局窗口监视器
     *
     * @param project 项目
     */
    private static void globalWindowMonitor(@NotNull final Project project) {
        // 清理幂等
        EditorState.SAVED_MARK.remove(project.getLocationHash());
        // 激活监听（ProjectManager.TOPIC 为应用级广播，会收到所有项目的关闭事件，需过滤本项目）
        project.getMessageBus().connect(ProjectDisposableService.getInstance(project)).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
            /**
             * 项目关闭时进行历史存储（仅响应本项目事件）
             * @param p 项目对象（非工具窗口项目对象）
             */
            @Override
            public void projectClosingBeforeSave(@NotNull final Project p) {
                if (p == project) {
                    editorStore(project);
                }
            }
        });
    }

    /**
     * 编辑器储存
     *
     * @param project 项目
     */
    private static void editorStore(@NotNull final Project project) {
        Opt.ofNullable(project).filter(p -> EditorState.SAVED_MARK.add(p.getLocationHash())).map(ToolWindowManager::getInstance)
                .map(manager -> manager.getToolWindow(PROJECT_NAME)).filter(Objects::nonNull)
                .ifPresent(window -> {
                    final Content[] contents = window.getContentManager().getContents();
                    PropertiesComponent.getInstance(project).setValue(
                            JSON_HELPER_STATE_KEY,
                            EditorState.encode(
                                    IntStream.range(0, contents.length).boxed()
                                            .flatMap(number ->
                                                    Opt.ofNullable(JsonEditorPushProvider.deepFindEditor(contents[number].getComponent()))
                                                            .filter(Objects::nonNull)
                                                            .map(field -> {
                                                                // 优先读编辑器实时滚动偏移；编辑器已释放（IDE 关闭流程）则回退到 factory 记录值
                                                                final Integer scroll = Opt.ofNullable(field.getEditor())
                                                                        .map(editor -> editor.getScrollingModel().getVerticalScrollOffset())
                                                                        .orElseGet(() -> {
                                                                            final Object f = field.getClientProperty(CUSTOMIZE_FACTORY_KEY);
                                                                            return f instanceof final CustomizeEditorFactory cf ? cf.getCurrentScrollOffset() : null;
                                                                        });
                                                                return Stream.of(new EditorState(
                                                                        Convert.toInt(contents[number].getTabName()),
                                                                        field.getText(),
                                                                        scroll
                                                                ));
                                                            }).orElseGet(Stream::empty)
                                            ).toList()
                            )
                    );
                });
    }

    /**
     * 创建工具窗口内容
     *
     * @param project    项目
     * @param toolWindow 工具窗口
     */
    @Override
    public void createToolWindowContent(@NotNull final Project project, @NotNull final ToolWindow toolWindow) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // 加载编辑器历史
            Opt.ofEmptyAble(
                    Opt.ofNullable(PropertiesComponent.getInstance(project).getValue(JSON_HELPER_STATE_KEY))
                            .map(EditorState::decode).filter(CollUtil::isNotEmpty).orElseGet(List::of)
            ).ifPresentOrElse(
                    // 填充编辑器历史
                    item -> item.forEach(state -> this.createNewTab(project, toolWindow, state)),
                    // 创建初始页签
                    () -> this.createNewTab(project, toolWindow, null)
            );
            // 全局窗口监视器
            globalWindowMonitor(project);
            // 绑定活动分组到窗口
            toolWindow.setTitleActions(List.of(this.createActionGroup(project, toolWindow)));
        });
    }

    /**
     * 创建新的页签
     *
     * @param project    项目
     * @param toolWindow 工具窗口
     * @param restore    恢复内容
     */
    public void createNewTab(@NotNull final Project project, @NotNull final ToolWindow toolWindow, final EditorState restore) {
        final Disposable tabDisposable = Disposer.newDisposable("JsonHelperTab");
        Disposer.register(ProjectDisposableService.getInstance(project), tabDisposable);
        // 增加页签号数
        final int number = Opt.ofNullable(restore).map(EditorState::editorId).peek(tabCounter::set).orElseGet(tabCounter::incrementAndGet);
        // 创建页签内容面板（携带保存的视口滚动偏移；无保存值时默认置顶）
        final JPanel contentPanel = this.createWindowContent(
                project, number,
                Opt.ofNullable(restore).map(EditorState::content).orElse(null),
                Opt.ofNullable(restore).map(EditorState::scrollOffset).orElse(null),
                tabDisposable);
        // 创建页签内容
        final Content content = ContentFactory.getInstance().createContent(contentPanel, String.valueOf(number), Boolean.FALSE);
        // 可关闭设置
        content.setCloseable(Boolean.TRUE);
        // 页签关闭时释放资源
        content.setDisposer(() -> {
            Disposer.dispose(tabDisposable);
            // 销毁所有窗口组件
            Arrays.stream(contentPanel.getComponents()).filter(Objects::nonNull)
                    .filter(EditorTextField.class::isInstance).map(EditorTextField.class::cast)
                    .map(EditorTextField::getEditor).filter(Objects::nonNull)
                    .forEach(editor -> EditorFactory.getInstance().releaseEditor(editor));
            // 所有页签关闭后重置计数器
            ApplicationManager.getApplication().invokeLater(() ->
                    Opt.of(toolWindow.getContentManager().getContentCount() == 0)
                            .filter(i -> i).ifPresent(_ -> tabCounter.set(0))
            );
        });
        // 将页签内容添加到工具窗口
        toolWindow.getContentManager().addContent(content);
        // 切换焦点到新页签
        ApplicationManager.getApplication().invokeLater(() -> toolWindow.getContentManager().setSelectedContent(content, Boolean.TRUE));
    }

    /**
     * 创建活动分组
     *
     * @param project    项目
     * @param toolWindow 工具窗口
     * @return {@link DefaultActionGroup }
     */
    private DefaultActionGroup createActionGroup(@NotNull final Project project, @NotNull final ToolWindow toolWindow) {
        final DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new AnAction(BUNDLE.getString("json.new.tab"), BUNDLE.getString("json.new.tab.desc"), AllIcons.General.Add) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull final AnActionEvent e) {
                // 检查项目和窗口是否有效
                if (project.isDisposed() || toolWindow.isDisposed()) return;
                // 创建新页签
                MainToolWindowFactory.this.createNewTab(project, toolWindow, null);
            }
        });
        return actionGroup;
    }

    /**
     * 页签编辑器与其 CustomizeEditorFactory 的关联键（EditorTextField client property）
     */
    private static final String CUSTOMIZE_FACTORY_KEY = "prism.json.factory";

    /**
     * 创建窗口内容
     *
     * @param project      项目
     * @param number       页签号数
     * @param content      内容
     * @param scrollOffset 保存的视口滚动偏移（项目重开还原），null 表示置顶
     * @return {@link JPanel }
     */
    private JPanel createWindowContent(@NotNull final Project project, final int number, final String content,
                                       final Integer scrollOffset, final Disposable tabDisposable) {
        // 窗口工具
        final JPanel toolWindow = new JPanel(new BorderLayout(0, 0));
        // 创建JSON编辑器（初始视口偏移：有保存值则还原，无则置顶；页签切换由 VisibleAreaListener 实时记录）
        final CustomizeEditorFactory factory = new CustomizeEditorFactory(SupportedLanguages.JSON, "Dummy_%d.json".formatted(number), scrollOffset);
        final EditorTextField editor = factory.create(project);
        // 关联 factory：IDE 关闭保存时若 editor 已释放，从 factory 读取实时滚动偏移兜底
        editor.putClientProperty(CUSTOMIZE_FACTORY_KEY, factory);
        // 填充内容
        Opt.ofBlankAble(content).ifPresent(editor::setText);
        // 等待编辑器初始化后，挂载面板功能
        ApplicationManager.getApplication().invokeLater(() -> {
            // JSON编辑框绑定拖放监听
            Editor.bindDragAndDropListening(editor);
            // 组合布局
            toolWindow.setBorder(BorderFactory.createEmptyBorder());
            toolWindow.add(this.createSynthesisPanel(editor, tabDisposable), BorderLayout.CENTER);
            // 重新绘制窗口
            toolWindow.revalidate();
            toolWindow.repaint();
        });
        return toolWindow;
    }

    /**
     * 创建合成面板
     *
     * @param editor 编辑器
     * @return {@link JPanel }
     */
    private JPanel createSynthesisPanel(final EditorTextField editor, final Disposable tabDisposable) {
        // 创建合成面板
        final JPanel panel = new JPanel(new BorderLayout(0, 0));
        // 主面板
        panel.setBorder(BorderFactory.createEmptyBorder());
        panel.add(new MainPanel().create(editor, tabDisposable), BorderLayout.NORTH);
        // 创建滑动分区区块
        final JSplitPane editorTreeSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                editor,
                // 树面板
                new JsonTreePanel().create(editor, tabDisposable)
        );
        // 自定义分隔条样式
        editorTreeSplit.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(final Graphics g) {
                        if (g instanceof final Graphics2D g2d) {
                            g2d.setColor(UIManager.getColor("Component.borderColor"));
                            g2d.fillRect(0, 0, this.getWidth(), this.getHeight());
                        }
                    }

                    @Override
                    public Dimension getPreferredSize() {
                        return new Dimension(DIVIDER_SIZE, DIVIDER_SIZE);
                    }
                };
            }
        });
        // 初始比例
        editorTreeSplit.setDividerSize(DIVIDER_SIZE);
        editorTreeSplit.setResizeWeight(EDITOR_RESIZE_WEIGHT);
        // 拖动时实时更新
        editorTreeSplit.setContinuousLayout(Boolean.TRUE);
        editorTreeSplit.setBorder(BorderFactory.createEmptyBorder());
        // 初始化分割窗格布局
        editorTreeSplit.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                MainToolWindowFactory.this.initSplitPaneLayout(editorTreeSplit);
            }
        });
        panel.add(editorTreeSplit, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 初始化分割窗格布局
     *
     * @param splitPane 拆分窗格
     */
    private void initSplitPaneLayout(final JSplitPane splitPane) {
        // 延迟计算布局（确保父容器尺寸已确定）
        ApplicationManager.getApplication().invokeLater(() ->
                // 设置分隔条初始位置（底部组件显示最小高度）
                splitPane.setDividerLocation(
                        splitPane.getHeight() - splitPane.getDividerSize() - splitPane.getBottomComponent().getMinimumSize().height
                )
        );
    }
}
