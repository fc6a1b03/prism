package com.acme.prism.ui.panel;

import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.Clipboard;
import com.acme.prism.core.json.*;
import com.acme.prism.core.notice.Notifier;
import com.acme.prism.core.parser.AnyParser;
import com.acme.prism.core.parser.JwtParser;
import com.acme.prism.core.parser.PathParser;
import com.acme.prism.ui.dialog.ConvertAnyDialog;
import com.acme.prism.ui.dialog.JsonAnalyzeDialog;
import com.acme.prism.ui.dialog.JsonValidateDialog;
import com.acme.prism.ui.editor.JsonFoldingSupport;
import com.alibaba.fastjson2.JSON;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.EditorTextField;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 主面板
 *
 * @author 拒绝者
 * @date 2025-01-19
 */
public class MainPanel {
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 搜索框宽度
     */
    private static final int SEARCH_BOX_WIDTH = 220;
    /**
     * 低置信度阈值：修复置信度低于该值时以警告样式提示人工核对
     */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5d;
    /**
     * 搜索框高度
     */
    private static final int SEARCH_BOX_HEIGHT = 35;
    /**
     * 工具按钮尺寸
     */
    private static final int BUTTON_SIZE = 35;
    /**
     * 撤销/重做历史上限（防止长期编辑导致历史无限膨胀）
     */
    private static final int MAX_HISTORY_SIZE = 100;
    /**
     * 搜索历史上限
     */
    private static final int MAX_SEARCH_HISTORY = 10;
    /**
     * 自动识别防抖延迟（毫秒）
     */
    private static final int AUTO_DETECT_DEBOUNCE_MS = 300;
    /**
     * JSON 文件扩展名
     */
    private static final String JSON_EXTENSION = "json";
    /**
     * 自动识别与树构建跳过的大 JSON 阈值（字符）。超过该长度的文本不做自动识别/树构建，
     * 避免大文件粘贴或加载时阻塞 UI（对齐 minimap 2MB / 彩虹变量 2 万行的护栏策略）
     */
    private static final int MAX_AUTO_PROCESS_CHARS = 1024 * 1024;
    /**
     * 编辑器弹出菜单名称
     */
    private static final String POPUP_MENU_NAME = "JsonEditorPopup";
    /**
     * IDE 代码格式化动作 ID（平台注册的标准动作，用于跟随当前键位映射的快捷键）
     */
    private static final String REFORMAT_CODE_ACTION_ID = "ReformatCode";
    /**
     * 重做历史堆栈
     */
    private final Deque<String> redoStack = new ArrayDeque<>();
    /**
     * 撤销历史堆栈
     */
    private final Deque<String> undoStack = new ArrayDeque<>();
    /**
     * JSONPath 搜索历史（最新在前），支持搜索框 ↑/↓ 浏览
     */
    private final List<String> searchHistory = new ArrayList<>(MAX_SEARCH_HISTORY);
    /**
     * 搜索历史浏览索引
     */
    private int searchHistoryIndex = -1;
    /**
     * 原始记录`用于JSON搜索`
     */
    private final AtomicReference<String> originalJson = new AtomicReference<>("");
    /**
     * 自动识别任务序号
     */
    private final AtomicLong autoDetectSequence = new AtomicLong();
    /**
     * 自动识别回写标记
     */
    private final AtomicBoolean autoDetectApplying = new AtomicBoolean(Boolean.FALSE);
    /**
     * 自动识别防抖队列（官方推荐的事件合并机制，避免每次击键都触发全量识别）
     */
    private MergingUpdateQueue autoDetectQueue;

    /**
     * 创建主面板
     *
     * @param editor 当前编辑
     * @return {@link JPanel }
     */
    public JPanel create(final EditorTextField editor, final Disposable parentDisposable) {
        // 创建主面板
        final JPanel searchPanel = new JPanel(new BorderLayout(0, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder());
        // 搜索框
        final JTextField searchBox = this.createSearchBox();
        // 按钮组`撤消按钮`、`重做按钮`、`清除按钮`
        final JButton undoButton = this.createButton(AllIcons.Actions.Undo, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
        final JButton redoButton = this.createButton(AllIcons.Actions.Redo, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
        final JButton clearButton = this.createButton(AllIcons.Actions.ClearCash, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
        // 添加按钮面板
        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder());
        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
        buttonPanel.add(clearButton);
        searchPanel.add(buttonPanel, BorderLayout.EAST);
        searchPanel.add(searchBox, BorderLayout.CENTER);
        // 编辑器动作
        this.editorAction(searchBox, redoButton, undoButton, editor);
        // 编辑器监听
        this.listener(editor, undoButton, redoButton, clearButton, parentDisposable);
        // 深度工具条（修复/排序/展开/还原/Schema/分析）
        final JPanel toolPanel = this.createToolPanel(redoButton, undoButton, editor);
        final JPanel container = new JPanel(new BorderLayout(0, 0));
        container.setBorder(BorderFactory.createEmptyBorder());
        container.add(searchPanel, BorderLayout.NORTH);
        container.add(toolPanel, BorderLayout.CENTER);
        return container;
    }

    /**
     * 创建深度工具条：修复 / 排序 / 展开 / 还原 / Schema / 分析。
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     * @return 工具条面板
     */
    private JPanel createToolPanel(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        panel.setBorder(BorderFactory.createEmptyBorder());
        panel.add(this.createToolButton(BUNDLE.getString("json.repair.json"), AllIcons.Actions.IntentionBulb,
                _ -> this.repairJson(redoButton, undoButton, editor)));
        panel.add(this.createToolButton(BUNDLE.getString("json.sort.keys"), AllIcons.ObjectBrowser.SortByType,
                _ -> this.optJson(redoButton, undoButton, editor.getEditor(), new JsonSorter())));
        panel.add(this.createToolButton(BUNDLE.getString("json.flatten.json"), AllIcons.Actions.Collapseall,
                _ -> this.optJson(redoButton, undoButton, editor.getEditor(), new JsonFlattener())));
        panel.add(this.createToolButton(BUNDLE.getString("json.unflatten.json"), AllIcons.Actions.Expandall,
                _ -> this.unflattenJson(redoButton, undoButton, editor)));
        panel.add(this.createToolButton(BUNDLE.getString("json.schema.generate"), AllIcons.FileTypes.JsonSchema,
                _ -> this.optJson(redoButton, undoButton, editor.getEditor(), new JsonSchemaGenerator())));
        panel.add(this.createToolButton(BUNDLE.getString("json.mock.generate"), AllIcons.Actions.Rerun,
                _ -> this.optJson(redoButton, undoButton, editor.getEditor(), new JsonMockGenerator())));
        panel.add(this.createKeyCaseButton(redoButton, undoButton, editor));
        panel.add(this.createToolButton(BUNDLE.getString("json.validate"), AllIcons.General.GreenCheckmark,
                _ -> this.showValidateDialog(editor)));
        panel.add(this.createToolButton(BUNDLE.getString("json.analyze"), AllIcons.Actions.Find,
                _ -> this.showAnalyzeDialog(editor)));
        return panel;
    }

    /**
     * 创建键名风格转换按钮：点击弹出四种命名风格菜单，选择后即时转换并写回编辑器。
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     * @return 按钮
     */
    private JButton createKeyCaseButton(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        final JButton button = this.createToolButton(BUNDLE.getString("json.key.case.convert"), AllIcons.Json.Object, null);
        button.addActionListener(_ -> this.showKeyCaseMenu(button, redoButton, undoButton, editor));
        return button;
    }

    /**
     * 弹出键名风格选择菜单（四种命名风格）。
     *
     * @param invoker    触发按钮
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     */
    private void showKeyCaseMenu(final JButton invoker, final JButton redoButton, final JButton undoButton,
                                 final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final JPopupMenu menu = new JPopupMenu();
        for (final JsonKeyConverter.KeyCase keyCase : JsonKeyConverter.KeyCase.values()) {
            final JMenuItem item = new JMenuItem(BUNDLE.getString(keyCase.i18nKey()));
            item.addActionListener(_ -> this.optJson(redoButton, undoButton, editor.getEditor(), new JsonKeyConverter(keyCase)));
            menu.add(item);
        }
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * 创建工具条按钮（图标 + 提示文本）。
     *
     * @param tooltip 提示文本
     * @param icon    图标
     * @param action  点击动作；为 {@code null} 时不绑定动作（由调用方另行绑定）
     * @return 按钮
     */
    private JButton createToolButton(final String tooltip, final Icon icon, final ActionListener action) {
        final JButton button = new JButton();
        button.setIcon(icon);
        button.setToolTipText(tooltip);
        button.setMargin(JBUI.emptyInsets());
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        if (Objects.nonNull(action)) {
            button.addActionListener(action);
        }
        return button;
    }

    /**
     * 修复 JSON：修复成功写回编辑器并通知置信度与修复点；无法修复时提示。
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     */
    private void repairJson(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final Document document = editor.getDocument();
        final String snapshot = document.getText();
        if (StrUtil.isBlank(snapshot)) return;
        CompletableFuture
                .supplyAsync(() -> JsonRepairer.repair(snapshot), AppExecutorUtil.getAppExecutorService())
                .thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (Objects.isNull(result)) {
                        Notifier.notifyError(BUNDLE.getString("json.repair.failed"), editor.getProject());
                        return;
                    }
                    if (result.fixes().isEmpty()) {
                        Notifier.notifyInfo(BUNDLE.getString("json.repair.valid"), editor.getProject());
                        return;
                    }
                    if (!snapshot.equals(document.getText())) {
                        return;
                    }
                    WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                        this.pushHistory(this.undoStack, snapshot);
                        document.setText(result.json());
                        this.updateButtons(undoButton, redoButton);
                    });
                    // 修复摘要：置信度 + 修复点列表
                    final String detail = result.fixes().stream()
                            .map(fix -> BUNDLE.getString(fix.i18nKey()))
                            .collect(Collectors.joining(", "));
                    final String summary = BUNDLE.getString("json.repair.success")
                            .formatted((int) (result.confidence() * 100), detail);
                    // 低置信度（结构性改动多）时以警告样式提示人工核对
                    if (result.confidence() < LOW_CONFIDENCE_THRESHOLD) {
                        Notifier.notifyWarn("%s %s".formatted(summary, BUNDLE.getString("json.repair.low.confidence")), editor.getProject());
                    } else {
                        Notifier.notifyInfo(summary, editor.getProject());
                    }
                }))
                .exceptionally(error -> {
                    Notifier.notifyError(error.getMessage(), editor.getProject());
                    return null;
                });
    }

    /**
     * 反扁平化 JSON：键路径冲突时提示，不写回。
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     */
    private void unflattenJson(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final Document document = editor.getDocument();
        final String snapshot = document.getText();
        if (StrUtil.isBlank(snapshot)) return;
        CompletableFuture
                .supplyAsync(() -> JsonUnflattener.unflatten(snapshot), AppExecutorUtil.getAppExecutorService())
                .thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (Objects.isNull(result)) {
                        Notifier.notifyError(BUNDLE.getString("json.unflatten.conflict"), editor.getProject());
                        return;
                    }
                    if (!snapshot.equals(document.getText())) {
                        return;
                    }
                    WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                        this.pushHistory(this.undoStack, snapshot);
                        document.setText(result);
                        this.updateButtons(undoButton, redoButton);
                    });
                }))
                .exceptionally(error -> {
                    Notifier.notifyError(error.getMessage(), editor.getProject());
                    return null;
                });
    }

    /**
     * 展示 JSON 结构分析弹窗。
     *
     * @param editor 当前编辑
     */
    private void showAnalyzeDialog(final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final String text = editor.getText();
        if (StrUtil.isBlank(text)) {
            Notifier.notifyWarn(BUNDLE.getString("json.tool.empty.editor"), editor.getProject());
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> new JsonAnalyzeDialog(editor.getProject(), text).show());
    }

    /**
     * 展示 JSON Schema 校验弹窗。
     *
     * @param editor 当前编辑
     */
    private void showValidateDialog(final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final String text = editor.getText();
        if (StrUtil.isBlank(text)) {
            Notifier.notifyWarn(BUNDLE.getString("json.tool.empty.editor"), editor.getProject());
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> new JsonValidateDialog(editor.getProject(), text).show());
    }

    /**
     * 创建搜索框
     *
     * @return {@link JTextField }
     */
    private JTextField createSearchBox() {
        final JTextField searchBox = new JTextField();
        searchBox.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent e) {
                if (e.getSource() instanceof final JTextField field) {
                    field.setBorder(MainPanel.this.createBorderByDefaultColor());
                }
            }

            @Override
            public void focusLost(final FocusEvent e) {
                if (e.getSource() instanceof final JTextField field) {
                    field.setBorder(MainPanel.this.createBorderByDefaultColor());
                }
            }
        });
        searchBox.setMargin(JBUI.emptyInsets());
        searchBox.setBorder(MainPanel.this.createBorderByDefaultColor());
        searchBox.setPreferredSize(new Dimension(SEARCH_BOX_WIDTH, SEARCH_BOX_HEIGHT));
        searchBox.setToolTipText(BUNDLE.getString("json.tool.tip.text"));
        // 搜索历史浏览：↑/↓ 切换最近查询
        searchBox.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    MainPanel.this.moveSearchHistory(-1, searchBox);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    MainPanel.this.moveSearchHistory(1, searchBox);
                }
            }
        });
        return searchBox;
    }

    /**
     * 浏览搜索历史（↑/↓ 循环切换）。
     *
     * @param direction 方向（-1 上一条，1 下一条）
     * @param searchBox 搜索框
     */
    private void moveSearchHistory(final int direction, final JTextField searchBox) {
        if (this.searchHistory.isEmpty()) {
            return;
        }
        this.searchHistoryIndex = Math.floorMod(this.searchHistoryIndex + direction, this.searchHistory.size());
        searchBox.setText(this.searchHistory.get(this.searchHistoryIndex));
    }

    /**
     * 记录搜索历史（去重，最新在前，超上限淘汰最旧）。
     *
     * @param expression 查询表达式
     */
    private void addSearchHistory(final String expression) {
        this.searchHistory.remove(expression);
        this.searchHistory.add(0, expression);
        while (this.searchHistory.size() > MAX_SEARCH_HISTORY) {
            this.searchHistory.remove(this.searchHistory.size() - 1);
        }
    }

    /**
     * 创建标准化按钮
     *
     * @param icon 按钮图标
     * @return 配置好的JButton实例
     */
    @SuppressWarnings("SameParameterValue")
    private JButton createButton(final Icon icon, final boolean top, final boolean left, final boolean bottom, final boolean right) {
        final JButton toolButton = new JButton();
        toolButton.setIcon(icon);
        toolButton.setEnabled(Boolean.FALSE);
        toolButton.setMargin(JBUI.emptyInsets());
        toolButton.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        toolButton.setBorder(MainPanel.this.createBorderByDefaultColor(top, left, bottom, right));
        toolButton.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent e) {
                if (e.getSource() instanceof final JButton button) {
                    button.setBorder(MainPanel.this.createBorderByDefaultColor(top, left, bottom, right));
                }
            }

            @Override
            public void focusLost(final FocusEvent e) {
                if (e.getSource() instanceof final JButton button) {
                    button.setBorder(MainPanel.this.createBorderByDefaultColor(top, left, bottom, right));
                }
            }
        });
        return toolButton;
    }

    /**
     * 按默认颜色创建边框`四周边框`
     *
     * @return {@link Border }
     */
    private Border createBorderByDefaultColor() {
        return BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1);
    }

    /**
     * 按默认颜色创建边框`自定义边框`
     *
     * @param top    顶端
     * @param left   左边
     * @param bottom 底部
     * @param right  正确
     * @return {@link Border }
     */
    private Border createBorderByDefaultColor(final boolean top, final boolean left, final boolean bottom, final boolean right) {
        return BorderFactory.createMatteBorder(
                top ? 1 : 0,
                left ? 1 : 0,
                bottom ? 1 : 0,
                right ? 1 : 0,
                UIManager.getColor("Component.borderColor")
        );
    }

    /**
     * 更新按钮可用状态
     *
     * @param undoButton 撤销按钮
     * @param redoButton 重做按钮
     */
    private void updateButtons(final JButton undoButton, final JButton redoButton) {
        undoButton.setEnabled(!this.undoStack.isEmpty());
        redoButton.setEnabled(!this.redoStack.isEmpty());
    }

    /**
     * 有界压入历史堆栈（超出上限时淘汰最旧记录）
     *
     * @param stack 目标堆栈
     * @param text  文本
     */
    private void pushHistory(final Deque<String> stack, final String text) {
        while (stack.size() >= MAX_HISTORY_SIZE) {
            stack.removeLast();
        }
        stack.push(text);
    }

    /**
     * 重做
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     */
    private void redoLastSearch(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor) || this.redoStack.isEmpty()) return;
        // 储存撤销历史
        this.pushHistory(this.undoStack, editor.getDocument().getText());
        // 将重做历史写回编辑器
        editor.setText(this.redoStack.pop());
        // 更新按钮可用状态
        this.updateButtons(undoButton, redoButton);
    }

    /**
     * 撤消
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     当前编辑
     */
    private void undoLastSearch(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor) || this.undoStack.isEmpty()) {
            this.originalJson.set("");
            return;
        }
        // 储存重做历史
        this.pushHistory(this.redoStack, editor.getDocument().getText());
        // 将撤消历史写回编辑器
        editor.setText(this.undoStack.pop());
        // 更新按钮可用状态
        this.updateButtons(undoButton, redoButton);
    }

    /**
     * 清空内容
     *
     * @param editor 当前编辑
     */
    private void clearContent(final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor)) return;
        // 储存撤消历史
        this.pushHistory(this.undoStack, editor.getDocument().getText());
        // 清空原始记录
        this.originalJson.set("");
        // 清空重做历史
        this.redoStack.clear();
        // 清空编辑器
        editor.setText("");
        // 更新按钮可用状态
        this.updateButtons(undoButton, redoButton);
    }

    /**
     * 执行搜索
     *
     * @param searchField 搜索字段
     * @param editor      当前编辑
     */
    private void performSearch(final JTextField searchField, final JButton redoButton,
                               final JButton undoButton, final EditorTextField editor) {
        if (Objects.isNull(editor)) return;
        final String searchExpression = searchField.getText();
        if (searchExpression.isEmpty()) return;
        final Document document = editor.getDocument();
        final String snapshot = document.getText();
        if (snapshot.isEmpty()) return;
        // 记录搜索历史（供 ↑/↓ 浏览）
        this.addSearchHistory(searchExpression);
        final String original = this.originalJson.updateAndGet(current -> StrUtil.isEmpty(current) ? snapshot : current);
        CompletableFuture
                .supplyAsync(() -> new JsonSearchEngine().process(original, searchExpression), AppExecutorUtil.getAppExecutorService())
                .thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (!snapshot.equals(document.getText())) {
                        return;
                    }
                    this.pushHistory(this.undoStack, snapshot);
                    editor.setText(result);
                    this.updateButtons(undoButton, redoButton);
                }))
                .exceptionally(error -> {
                    Notifier.notifyError(error.getMessage(), editor.getProject());
                    return null;
                });
    }

    /**
     * 显示编辑器上下文菜单
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     编辑器
     * @param e          鼠标事件
     */
    private void showEditorPopupMenu(final JButton redoButton, final JButton undoButton,
                                     final EditorTextField editor, final MouseEvent e) {
        final DefaultActionGroup group = new DefaultActionGroup();
        // 格式化菜单
        this.addJsonAction(group, "json.format.json", "json.format.json.desc",
                AllIcons.Actions.Refresh, new JsonFormatter(), redoButton, undoButton, editor);
        // 压缩菜单
        this.addJsonAction(group, "json.compress.json", "json.compress.json.desc",
                AllIcons.Actions.Collapseall, new JsonCompressor(), redoButton, undoButton, editor);
        // 转义菜单
        this.addJsonAction(group, "json.escaping.json", "json.escaping.json.desc",
                AllIcons.Javaee.UpdateRunningApplication, new JsonEscaper(), redoButton, undoButton, editor);
        // 去转义菜单
        this.addJsonAction(group, "json.un.escaping.json", "json.un.escaping.json.desc",
                AllIcons.Actions.SearchNewLine, new JsonUnEscaper(), redoButton, undoButton, editor);
        // 分隔符
        group.addSeparator();
        // 差异对比菜单
        this.addDiffAction(group, editor);
        // 转为任何
        this.addJsonToAnyAction(group, editor);
        // 添加打开文件菜单
        this.addOpenFileAction(group, editor);
        // 分隔符
        group.addSeparator();
        // 其他可适配的菜单
        group.add(ActionManager.getInstance().getAction(IdeActions.GROUP_EDITOR_POPUP));
        // 添加菜单触发事件及出现位置
        ActionManager.getInstance()
                .createActionPopupMenu(POPUP_MENU_NAME, group)
                .getComponent()
                .show(editor.getComponent(), e.getX(), e.getY());
    }

    /**
     * 添加JSON处理操作
     *
     * @param group     Action组
     * @param nameKey   名称键
     * @param descKey   描述键
     * @param icon      图标
     * @param operation JSON操作
     */
    private void addJsonAction(final DefaultActionGroup group, final String nameKey, final String descKey,
                               final Icon icon, final JsonOperation operation, final JButton redoButton,
                               final JButton undoButton, final EditorTextField editor) {
        group.add(new AnAction(BUNDLE.getString(nameKey), BUNDLE.getString(descKey), icon) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(final @NotNull AnActionEvent e) {
                MainPanel.this.optJson(redoButton, undoButton, editor.getEditor(), operation);
            }
        });
    }

    /**
     * 添加JSONToAny操作
     *
     * @param group Action组
     */
    private void addJsonToAnyAction(final DefaultActionGroup group, final EditorTextField editor) {
        group.add(new AnAction(
                BUNDLE.getString("json.to.any"),
                BUNDLE.getString("json.to.any.desc"),
                AllIcons.Debugger.Db_muted_dep_line_breakpoint
        ) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(final @NotNull AnActionEvent e) {
                if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
                final Document document = editor.getDocument();
                if (!JSON.isValid(document.getText())) return;
                // 激活弹窗
                ApplicationManager.getApplication().invokeLater(() -> new ConvertAnyDialog(editor.getProject(), document.getText()).show());
            }
        });
    }

    /**
     * 编辑器监听
     *
     * @param editor      编辑器
     * @param undoButton  撤消按钮
     * @param redoButton  重做按钮
     * @param clearButton 清除按钮
     */
    private void listener(final EditorTextField editor, final JButton undoButton, final JButton redoButton, final JButton clearButton,
                          final Disposable parentDisposable) {
        // 编辑器事件
        clearButton.addActionListener(_ -> this.clearContent(redoButton, undoButton, editor));
        undoButton.addActionListener(_ -> this.undoLastSearch(redoButton, undoButton, editor));
        redoButton.addActionListener(_ -> this.redoLastSearch(redoButton, undoButton, editor));
        if (Objects.nonNull(editor.getProject())) {
            // 自动识别防抖队列：合并连续输入事件，仅在停顿后执行一次全量识别
            this.autoDetectQueue = new MergingUpdateQueue(
                    "JsonHelper.AutoDetect", AUTO_DETECT_DEBOUNCE_MS, Boolean.TRUE, null, parentDisposable, null, Alarm.ThreadToUse.POOLED_THREAD
            );
            EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
                /** 上次处理的文本，内容去重避免多页签/重复事件重复触发 */
                private String lastProcessed = "";

                @Override
                public void documentChanged(final @NotNull DocumentEvent e) {
                    // EditorTextField 内部存在两个 document 实例（内部 editor 与外部 PSI），
                    // 引用比较在打字场景不可靠，改为按文本内容去重
                    final String text = editor.getText();
                    if (text.equals(this.lastProcessed)) {
                        return;
                    }
                    this.lastProcessed = text;
                    // 获取新旧片段并预处理（用事件片段判断是否纯空白差异）
                    final CharSequence oldText = e.getOldFragment();
                    final CharSequence newText = e.getNewFragment();
                    if (CharSequence.compare(oldText, newText) == 0) {
                        return;
                    }
                    if (StrUtil.emptyIfNull(oldText).strip().equals(StrUtil.emptyIfNull(newText).strip())) {
                        return;
                    }
                    // 根据文档内容调整清空按钮的状态
                    clearButton.setEnabled(!StrUtil.isEmpty(text));
                    // 性能护栏：超大 JSON 跳过自动识别，避免阻塞后台线程与 EDT
                    if (text.length() > MAX_AUTO_PROCESS_CHARS) {
                        return;
                    }
                    if (MainPanel.this.autoDetectApplying.get()) {
                        return;
                    }
                    // 防抖调度：自动识别路径类型（Web或本地路径）、Jwt、Any并将其转换为格式化JSON，回写到编辑器；
                    // 折叠更新并入同一队列（POOLED 线程执行 JSON 校验，避免大文本全量解析阻塞 EDT）
                    // identity 固定为编辑器实例，连续输入事件互相合并，仅执行最后一次
                    MainPanel.this.autoDetectQueue.queue(Update.create(editor, () -> {
                        MainPanel.this.updateFolding(editor, text);
                        MainPanel.this.optPath(text, editor);
                    }));
                }
            }, parentDisposable);
        }
        // 上下文菜单
        editor.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isPopupTrigger()) MainPanel.this.showEditorPopupMenu(redoButton, undoButton, editor, e);
            }
        });
    }

    /**
     * 编辑器动作
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     编辑器
     */
    private void editorAction(final JTextField searchField, final JButton redoButton, final JButton undoButton, final EditorTextField editor) {
        // 右键菜单事件
        new AnAction() {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(final @NotNull AnActionEvent e) {
                MainPanel.this.optJson(redoButton, undoButton, editor.getEditor(), new JsonFormatter());
            }
        }.registerCustomShortcutSet(
                new CustomShortcutSet(KeymapManager.getInstance().getActiveKeymap().getShortcuts(REFORMAT_CODE_ACTION_ID)),
                editor.getComponent()
        );
        // 搜索框快捷事件
        new AnAction() {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(final @NotNull AnActionEvent e) {
                MainPanel.this.performSearch(searchField, redoButton, undoButton, editor);
            }
        }.registerCustomShortcutSet(
                new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
                searchField
        );
    }

    /**
     * 自动识别路径类型 (Web 或本地路径),Jwt,Any 并将其转换为格式化 JSON, 回写到编辑器
     * <p> 处理过程采用异步方式以避免阻塞 UI 线程, 包含完整的异常处理和用户反馈
     *
     * @param text   当前编辑器中的原始文本内容
     * @param editor 目标编辑器组件, 用于回写处理结果
     */
    private void optPath(final String text, final EditorTextField editor) {
        // 性能护栏：超大文本不做自动识别（与 documentChanged 双保险）
        if (text.length() > MAX_AUTO_PROCESS_CHARS) {
            return;
        }
        final long sequence = this.autoDetectSequence.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
                    final String result = this.resolveJson(text);
                    // JSON 合法性校验放后台线程，避免大文本校验阻塞 EDT
                    return JSON.isValid(result) ? result : null;
                }, AppExecutorUtil.getAppExecutorService())
                .thenAccept(processedText -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (Objects.isNull(processedText) || sequence != this.autoDetectSequence.get()) {
                        return;
                    }
                    if (processedText.equals(editor.getText())) {
                        return;
                    }
                    this.originalJson.set("");
                    this.autoDetectApplying.set(Boolean.TRUE);
                    try {
                        editor.setText(processedText);
                    } finally {
                        this.autoDetectApplying.set(Boolean.FALSE);
                    }
                }));
    }

    private String resolveJson(final String text) {
        final String pathResult = PathParser.convert(text);
        if (JSON.isValid(pathResult)) {
            return pathResult;
        }
        final String jwtResult = JwtParser.convert(text);
        if (JSON.isValid(jwtResult)) {
            return jwtResult;
        }
        // MongoDB 扩展 JSON 包装（如 NumberLong(123)）会被 YAML 检测抢先消费成带引号字符串，
        // 需在 AnyParser 之前短路跳过，交给修复器剥离包装
        if (JsonRepairer.containsMongoWrapper(text)) {
            return "";
        }
        return AnyParser.convert(text);
    }

    /**
     * 更新编辑器 JSON 折叠区域：仅当文本为合法 JSON 且含换行时生效（压缩单行不折叠）。
     * 由防抖队列在后台线程调用（JSON 校验不阻塞 EDT），折叠模型应用切回 EDT。
     *
     * @param editor 编辑器
     * @param text   当前文本
     */
    private void updateFolding(final EditorTextField editor, final String text) {
        if (text.indexOf('\n') < 0 || !JSON.isValid(text)) {
            return;
        }
        // EditorTextField 的内部 Editor 可能尚未创建，延迟到 EDT 获取
        ApplicationManager.getApplication().invokeLater(() -> {
            if (Objects.isNull(editor.getEditor())) {
                return;
            }
            JsonFoldingSupport.updateFolding(editor.getEditor(), editor.getText());
        });
    }

    /**
     * 操作JSON
     *
     * @param redoButton 重做按钮
     * @param undoButton 撤消按钮
     * @param editor     编辑器
     * @param operation  操作
     */
    private void optJson(final JButton redoButton, final JButton undoButton,
                         final Editor editor, final JsonOperation operation) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final Document document = editor.getDocument();
        final String snapshot = document.getText();
        CompletableFuture
                // 合法性校验与处理均在后台线程，避免大 JSON 校验阻塞 EDT
                .supplyAsync(() -> operation.isValid(snapshot) ? StringUtil.convertLineSeparators(operation.process(snapshot)) : null, AppExecutorUtil.getAppExecutorService())
                .thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (Objects.isNull(result) || !snapshot.equals(document.getText())) {
                        return;
                    }
                    WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                        this.pushHistory(this.undoStack, snapshot);
                        document.setText(result);
                        this.updateButtons(undoButton, redoButton);
                    });
                }))
                .exceptionally(error -> {
                    Notifier.notifyError(error.getMessage(), editor.getProject());
                    return null;
                });
    }

    /**
     * 添加打开JSON文件操作
     *
     * @param group  默认操作组
     * @param editor 编辑器
     */
    private void addOpenFileAction(final DefaultActionGroup group, final EditorTextField editor) {
        group.add(new AnAction(
                BUNDLE.getString("menu.open.json.file"),
                BUNDLE.getString("menu.open.json.file.desc"),
                AllIcons.Actions.MenuOpen
        ) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull final AnActionEvent e) {
                MainPanel.this.handleFileOpen(editor);
            }
        });
    }

    /**
     * 添加差异操作
     *
     * @param group  组
     * @param editor 编辑
     */
    private void addDiffAction(final DefaultActionGroup group, final EditorTextField editor) {
        group.add(new AnAction(
                BUNDLE.getString("menu.diff.viewer"),
                BUNDLE.getString("menu.diff.viewer.desc"),
                AllIcons.Actions.Diff
        ) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull final AnActionEvent e) {
                MainPanel.this.showDiffViewer(editor);
            }
        });
    }

    /**
     * 处理文件打开操作
     *
     * @param editor 编辑器
     */
    private void handleFileOpen(final EditorTextField editor) {
        FileChooser.chooseFile(
                FileChooserDescriptorFactory
                        .createSingleFileDescriptor(editor.getFileType())
                        .withFileFilter(virtualFile -> JSON_EXTENSION.equalsIgnoreCase(virtualFile.getExtension())),
                editor.getProject(), null, virtualFile -> {
                    if (Objects.isNull(virtualFile)) return;
                    CompletableFuture
                            .supplyAsync(() -> {
                                try {
                                    final String content = new String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8);
                                    return JSON.isValid(content) ? new JsonFormatter().process(content) : null;
                                } catch (final Exception ignored) {
                                    return null;
                                }
                            }, AppExecutorUtil.getAppExecutorService())
                            .thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
                                if (StrUtil.isEmpty(result)) {
                                    Notifier.notifyError(BUNDLE.getString("file.load.failed"), editor.getProject());
                                    return;
                                }
                                WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                                    editor.getDocument().setText(result);
                                    this.originalJson.set(result);
                                    this.undoStack.clear();
                                    this.redoStack.clear();
                                });
                                Notifier.notifyInfo("%s%s".formatted(BUNDLE.getString("file.load.success"), virtualFile.getPath()), editor.getProject());
                            }))
                            .exceptionally(error -> {
                                Notifier.notifyError(BUNDLE.getString("file.load.failed"), editor.getProject());
                                return null;
                            });
                });
    }

    /**
     * 显示差异查看器
     *
     * @param editor 编辑
     */
    private void showDiffViewer(final EditorTextField editor) {
        if (Objects.isNull(editor) || Objects.isNull(editor.getProject())) return;
        final DiffContentFactory factory = DiffContentFactory.getInstance();
        // 差异查看器是纯 UI 展示，不属于写操作，直接调度到 EDT 即可
        ApplicationManager.getApplication().invokeLater(() -> DiffManager.getInstance().showDiff(editor.getProject(),
                new SimpleDiffRequest(
                        BUNDLE.getString("menu.diff.viewer"),
                        factory.createEditable(editor.getProject(), editor.getDocument().getText(), null),
                        factory.createEditable(editor.getProject(), Clipboard.get(), null), "", ""
                )
        ));
    }
}
