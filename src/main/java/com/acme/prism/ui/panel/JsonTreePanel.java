package com.acme.prism.ui.panel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.Clipboard;
import com.acme.prism.core.json.JsonAnalyzer;
import com.acme.prism.core.json.JsonAnalyzer.Stats;
import com.acme.prism.core.parser.JsonNodeParser;
import com.acme.prism.core.parser.JsonNodeParser.JsonNode;
import com.alibaba.fastjson2.JSON;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.tree.ui.DefaultTreeUI;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * JSON树面板
 *
 * @author 拒绝者
 * @date 2025-01-28
 */
public class JsonTreePanel extends JPanel {
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 数组索引模式
     */
    private static final Pattern ARRAY_INDEX_PATTERN = Pattern.compile("^\\[(\\d+)]$");
    /**
     * 搜索防抖延迟（毫秒）
     */
    private static final int SEARCH_DEBOUNCE_MS = 300;
    /**
     * 树行高
     */
    private static final int TREE_ROW_HEIGHT = 28;
    /**
     * 搜索框高度
     */
    private static final int SEARCH_FIELD_HEIGHT = 20;
    /**
     * 树更新防抖延迟（毫秒）
     */
    private static final int TREE_UPDATE_DEBOUNCE_MS = 300;
    /**
     * 树构建跳过的大 JSON 阈值（字符），超过不做树构建，避免大文件阻塞（对齐 MainPanel 护栏）
     */
    private static final int MAX_TREE_CHARS = 1024 * 1024;
    /**
     * 树面板最小高度（像素），保证树主体区域可见，不被分割窗格压缩
     */
    private static final int TREE_MIN_HEIGHT = 160;
    /**
     * 树节点值展示长度上限（字符），超过截断显示
     */
    private static final int MAX_VALUE_CHARS = 60;
    /**
     * JSON树
     */
    private final Tree jsonTree;
    /**
     * 统计摘要标签
     */
    private JLabel statsLabel;
    /**
     * 选中节点路径与值详情标签
     */
    private JLabel footerLabel;
    /**
     * 匹配集合
     */
    private final List<TreePath> matches = new ArrayList<>();
    /**
     * 搜索输入展示框
     */
    private JTextField searchField;
    /**
     * 搜索计时器
     */
    private Timer searchTimer;
    /**
     * 树更新防抖队列（合并连续文档变更，避免每次击键全量重建树模型）
     */
    private MergingUpdateQueue treeUpdateQueue;
    /**
     * 搜索文本
     */
    private String searchText = "";
    /**
     * 当前匹配指数
     */
    private int currentMatchIndex = -1;
    /**
     * 正在搜索
     */
    private volatile boolean isSearching = Boolean.FALSE;
    /**
     * 树模型更新序号
     */
    private final AtomicLong treeSequence = new AtomicLong();
    /**
     * 搜索任务序号
     */
    private final AtomicLong searchSequence = new AtomicLong();

    public JsonTreePanel() {
        super(new BorderLayout());
        this.jsonTree = new Tree();
        // 保证树主体区域最小可见高度，避免被分割窗格压缩成一条
        this.setMinimumSize(new Dimension(0, TREE_MIN_HEIGHT));
        // 配置`JsonTree`树外观
        this.configureTreeAppearance();
    }

    /**
     * 节点文本提取类型
     */
    private enum NodeTextType {
        /**
         * 整个对象
         */
        OBJECT,
        /**
         * 键
         */
        KEY,
        /**
         * 值
         */
        VALUE
    }

    private static String extractFirstObjectKey(final String item) {
        if (!JSON.isValidObject(item)) {
            return null;
        }
        for (final String key : JSON.parseObject(item).keySet()) {
            if (StrUtil.isNotEmpty(key)) {
                return key;
            }
        }
        return null;
    }

    private static Object extractFirstObjectValue(final String item) {
        if (!JSON.isValidObject(item)) {
            return null;
        }
        for (final Object value : JSON.parseObject(item).values()) {
            if (Objects.nonNull(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 加载JSON
     *
     * @param txt JSON文本
     */
    public void loadJson(final String txt) {
        // 一次解析同时供树构建与统计复用（JsonAnalyzer.analyze(JsonNode) 消除二次解析）
        final JsonNode root = JsonNodeParser.parse("root", txt);
        final Stats stats = JsonAnalyzer.analyze(root, txt.getBytes(StandardCharsets.UTF_8).length);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (Objects.isNull(this.statsLabel)) {
                return;
            }
            if (stats.isEmpty()) {
                this.statsLabel.setText(BUNDLE.getString("json.tree.stats.empty"));
            } else {
                this.statsLabel.setText(BUNDLE.getString("json.tree.stats")
                        .formatted(stats.keys(), stats.objects(), stats.arrays(), stats.maxDepth(), formatSize(stats.sizeBytes())));
            }
        });
        // 性能护栏：超大 JSON 跳过树构建，避免阻塞后台线程（对齐 minimap 2MB 护栏）
        if (txt.length() > MAX_TREE_CHARS) {
            return;
        }
        final long sequence = this.treeSequence.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> new DefaultTreeModel(this.buildTreeModel(root)),
                        AppExecutorUtil.getAppExecutorService())
                .thenAccept(model -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (sequence != this.treeSequence.get()) {
                        return;
                    }
                    this.matches.clear();
                    this.currentMatchIndex = -1;
                    this.jsonTree.setModel(model);
                    this.jsonTree.repaint();
                }));
    }

    /**
     * 创建JSON树面板
     *
     * @param editor 编辑
     * @return {@link JPanel }
     */
    public JPanel create(final EditorTextField editor, final Disposable parentDisposable) {
        if (Objects.nonNull(editor.getProject())) {
            // 树更新防抖队列：合并连续文档变更，仅在停顿后重建一次树模型
            this.treeUpdateQueue = new MergingUpdateQueue(
                    "JsonHelper.TreeUpdate", TREE_UPDATE_DEBOUNCE_MS, Boolean.TRUE, null, parentDisposable, null, Alarm.ThreadToUse.POOLED_THREAD
            );
            // 随页签释放停止定时器与队列，防止泄漏
            Disposer.register(parentDisposable, () -> this.searchTimer.stop());
            EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
                /** 上次处理的文本，内容去重避免多页签/重复事件重复构建树 */
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
                    // identity 固定为编辑器实例，连续变更互相合并，仅执行最后一次
                    JsonTreePanel.this.treeUpdateQueue.queue(Update.create(editor, () -> JsonTreePanel.this.loadJson(text)));
                }
            }, parentDisposable);
            // 历史回显在面板创建前已 setText，监听器未注册收不到事件，此处主动加载一次
            final String initial = editor.getText();
            if (!initial.isEmpty()) {
                this.treeUpdateQueue.queue(Update.create(editor, () -> this.loadJson(initial)));
            }
        }
        // 创建树面板
        final JPanel panel = new JPanel(new BorderLayout(0, 0));
        // 添加面板内容
        final JBScrollPane pane = new JBScrollPane(
                this.jsonTree,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        pane.setBorder(BorderFactory.createEmptyBorder());
        panel.add(pane, BorderLayout.CENTER);
        // 顶部：搜索框 + 展开/折叠按钮 + 信息栏（统计 + 选中节点详情）
        final JPanel header = new JPanel(new BorderLayout(0, 0));
        header.setBorder(BorderFactory.createEmptyBorder());
        final JPanel searchRow = new JPanel(new BorderLayout(0, 0));
        searchRow.setBorder(BorderFactory.createEmptyBorder());
        searchRow.add(this.createSearchField(), BorderLayout.CENTER);
        searchRow.add(this.createTreeActions(), BorderLayout.EAST);
        header.add(searchRow, BorderLayout.NORTH);
        header.add(this.createInfoBar(), BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);
        panel.setBorder(BorderFactory.createEmptyBorder());
        return panel;
    }

    /**
     * 创建树操作按钮组（展开全部/折叠全部）。
     *
     * @return 按钮组面板
     */
    private JComponent createTreeActions() {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        panel.setBorder(BorderFactory.createEmptyBorder());
        final JButton expandButton = new JButton();
        expandButton.setIcon(AllIcons.Actions.Expandall);
        expandButton.setToolTipText(BUNDLE.getString("json.tree.expand.all"));
        expandButton.setMargin(JBUI.emptyInsets());
        expandButton.setPreferredSize(new Dimension(SEARCH_FIELD_HEIGHT, SEARCH_FIELD_HEIGHT));
        expandButton.addActionListener(_ -> this.expandAll());
        final JButton collapseButton = new JButton();
        collapseButton.setIcon(AllIcons.Actions.Collapseall);
        collapseButton.setToolTipText(BUNDLE.getString("json.tree.collapse.all"));
        collapseButton.setMargin(JBUI.emptyInsets());
        collapseButton.setPreferredSize(new Dimension(SEARCH_FIELD_HEIGHT, SEARCH_FIELD_HEIGHT));
        collapseButton.addActionListener(_ -> this.collapseAll());
        panel.add(expandButton);
        panel.add(collapseButton);
        return panel;
    }

    /**
     * 创建信息栏：左侧统计摘要 + 右侧选中节点路径与值详情（合并为一行，避免挤压树区域）。
     *
     * @return 信息栏面板
     */
    private JComponent createInfoBar() {
        this.statsLabel = new JLabel();
        this.statsLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 4));
        this.statsLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN));
        this.statsLabel.setForeground(JBColor.GRAY);
        this.footerLabel = new JLabel(" ");
        this.footerLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 8));
        this.footerLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN));
        this.footerLabel.setForeground(JBColor.GRAY);
        this.jsonTree.addTreeSelectionListener(event -> {
            final TreePath path = event.getPath();
            if (Objects.isNull(path)) {
                this.footerLabel.setText(" ");
                return;
            }
            final String jsonPath = this.buildJsonPath(path);
            final String value = truncate(this.getNodeText(path.getLastPathComponent(), NodeTextType.OBJECT), MAX_VALUE_CHARS);
            // 根节点路径为空时回退为 $
            this.footerLabel.setText("%s = %s".formatted(jsonPath.isEmpty() ? "$" : jsonPath, value));
        });
        final JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(this.statsLabel, BorderLayout.WEST);
        panel.add(this.footerLabel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 展开全部节点（基于模型递归，不依赖行号动态变化）。
     */
    private void expandAll() {
        final TreeModel model = this.jsonTree.getModel();
        if (Objects.isNull(model) || Objects.isNull(model.getRoot())) {
            return;
        }
        this.expandPath(model, new TreePath(model.getRoot()));
    }

    /**
     * 折叠全部节点。
     *
     * <p>不折叠隐藏的根节点（根被折叠会导致整棵树收起、显示 Nothing to show），
     * 从根的子节点开始递归折叠。</p>
     */
    private void collapseAll() {
        final TreeModel model = this.jsonTree.getModel();
        if (Objects.isNull(model) || Objects.isNull(model.getRoot())) {
            return;
        }
        this.collapseChildren(model, new TreePath(model.getRoot()));
    }

    /**
     * 递归折叠子路径（不含根节点本身）。
     *
     * @param model 树模型
     * @param path  当前路径
     */
    private void collapseChildren(final TreeModel model, final TreePath path) {
        final Object node = path.getLastPathComponent();
        for (int i = 0; i < model.getChildCount(node); i++) {
            final TreePath child = path.pathByAddingChild(model.getChild(node, i));
            this.collapseChildren(model, child);
            this.jsonTree.collapsePath(child);
        }
    }

    /**
     * 递归展开路径及其所有子路径。
     *
     * @param model 树模型
     * @param path  当前路径
     */
    private void expandPath(final TreeModel model, final TreePath path) {
        this.jsonTree.expandPath(path);
        final Object node = path.getLastPathComponent();
        for (int i = 0; i < model.getChildCount(node); i++) {
            this.expandPath(model, path.pathByAddingChild(model.getChild(node, i)));
        }
    }

    /**
     * 格式化字节大小。
     *
     * @param bytes 字节数
     * @return 人类可读大小
     */
    private static String formatSize(final long bytes) {
        return bytes >= 1024 ? "%.1f KB".formatted(bytes / 1024.0d) : "%d B".formatted(bytes);
    }

    /**
     * 截断文本超长部分。
     *
     * @param text 文本
     * @param max  上限
     * @return 截断后的文本
     */
    private static String truncate(final String text, final int max) {
        return StrUtil.isBlank(text) || text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 配置树外观
     */
    private void configureTreeAppearance() {
        // 单击展开/折叠
        this.jsonTree.setToggleClickCount(1);
        // 设置可聚焦
        this.jsonTree.setFocusable(Boolean.TRUE);
        // 隐藏默认根节点
        this.jsonTree.setRootVisible(Boolean.FALSE);
        this.jsonTree.setShowsRootHandles(Boolean.TRUE);
        // 配置默认UI
        this.jsonTree.setUI(new DefaultTreeUI());
        // 行高与间距调整
        this.jsonTree.setRowHeight(JBUI.scale(TREE_ROW_HEIGHT));
        // 添加右键菜单
        this.addRightClickMenuToTree(this.jsonTree);
        // 新增键盘监听和定时器初始化
        this.initSearchFeature();
        // 添加JSON树速度搜索的突出显示树单元渲染器
        this.jsonTree.setCellRenderer(new TreeCellRenderer() {
            private final SimpleColoredComponent renderer = new SimpleColoredComponent();

            @Override
            public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean selected,
                                                          final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
                if (value instanceof final DefaultMutableTreeNode node) {
                    // 清空渲染器
                    this.renderer.clear();
                    // 背景色设置
                    this.renderer.setBackground(selected ?
                            UIUtil.getTreeSelectionBackground(Boolean.TRUE) :
                            UIUtil.getTreeBackground()
                    );
                    // 处理不同节点类型
                    if (Objects.requireNonNull(node.getUserObject()) instanceof final JsonNodeParser.JsonNode data) {
                        this.renderJsonNode(data);
                    } else {
                        this.renderer.append(Convert.toStr(node));
                    }
                }
                return this.renderer;
            }

            /**
             * 渲染JSON节点
             *
             * @param data 数据
             */
            private void renderJsonNode(final JsonNodeParser.JsonNode data) {
                // 设置节点图标
                this.renderer.setIcon(switch (data.type()) {
                    case "Object" -> AllIcons.Json.Object;
                    case "Array" -> AllIcons.Json.Array;
                    default -> AllIcons.Debugger.WatchLastReturnValue;
                });
                // 构建显示文本
                final boolean isMatched = StrUtil.isNotEmpty(JsonTreePanel.this.searchText) && "%s:%s".formatted(data.key(), data.value()).toLowerCase(Locale.ROOT).contains(JsonTreePanel.this.searchText);
                // 分割渲染键值部分
                this.renderer.append("%s:".formatted(data.key()),
                        isMatched ?
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE) :
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY)
                );
                // 值（长值截断显示，完整内容在底部详情栏查看）；按值类型着色，一眼识别结构。
                // null 的 value() 为空串，需显式渲染灰色 "null" 文本，避免节点值空白难辨
                final String valueText = Objects.isNull(data.rawValue())
                        ? "null"
                        : truncate(String.valueOf(data.value()), MAX_VALUE_CHARS);
                this.renderer.append(valueText,
                        isMatched ?
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE) :
                                typeColor(data.type())
                );
            }

            /**
             * 按值类型返回展示颜色（对齐 IntelliJ 语法着色习惯）。
             *
             * @param type 节点类型
             * @return 文本属性
             */
            private SimpleTextAttributes typeColor(final String type) {
                return switch (type) {
                    case "String" -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, new JBColor(0x6A8759, 0x6A8759));
                    case "Integer", "Long", "Double", "Float", "BigDecimal", "Short", "Byte" ->
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, new JBColor(0x6897BB, 0x6897BB));
                    case "Boolean" -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, new JBColor(0xCC7832, 0xCC7832));
                    case "Object", "Array" -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getTreeForeground());
                    // null 及未知类型：固定中性灰，避免主题自适应色（如 Darcula 暖灰）与布尔橙棕混淆
                    default -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, new JBColor(0x808080, 0x808080));
                };
            }
        });
    }

    /**
     * 创建搜索框
     *
     * @return {@link JTextField }
     */
    private JTextField createSearchField() {
        this.searchField = new JTextField();
        // 搜索框只读
        this.searchField.setEditable(Boolean.FALSE);
        this.searchField.setFocusable(Boolean.FALSE);
        // 搜索框样式
        this.searchField.setForeground(JBColor.GRAY);
        this.searchField.setBackground(UIUtil.getPanelBackground());
        this.searchField.setBorder(BorderFactory.createCompoundBorder(
                // 边框
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                // 内边距
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));
        this.searchField.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN));
        this.searchField.setPreferredSize(new Dimension(Short.MAX_VALUE, SEARCH_FIELD_HEIGHT));
        return this.searchField;
    }

    /**
     * 构建树模型
     *
     * @param node 节点
     * @return {@link DefaultMutableTreeNode }
     */
    private DefaultMutableTreeNode buildTreeModel(final JsonNodeParser.JsonNode node) {
        final DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        for (final JsonNodeParser.JsonNode child : node.children()) {
            treeNode.add(this.buildTreeModel(child));
        }
        return treeNode;
    }

    /**
     * 为JSON树添加右键复制菜单
     *
     * @param tree 树
     */
    @SuppressWarnings("DataFlowIssue")
    private void addRightClickMenuToTree(final Tree tree) {
        // 创建右键菜单
        final JPopupMenu popupMenu = new JPopupMenu();
        // 复制对象
        final JMenuItem copyItem = new JMenuItem(BUNDLE.getString("json.tree.copy"));
        copyItem.addActionListener(_ ->
                Opt.ofNullable(tree.getSelectionPath())
                        .ifPresent(path -> Clipboard.copy(this.getNodeText(path.getLastPathComponent(), NodeTextType.OBJECT)))
        );
        // 复制路径
        final JMenuItem copyPath = new JMenuItem(BUNDLE.getString("json.tree.copy.path"));
        copyPath.addActionListener(_ ->
                Opt.ofNullable(tree.getSelectionPath())
                        .ifPresent(path -> Clipboard.copy(this.buildJsonPath(path)))
        );
        // 复制键
        final JMenuItem copyKey = new JMenuItem(BUNDLE.getString("json.tree.copy.key"));
        copyKey.addActionListener(_ ->
                Opt.ofNullable(tree.getSelectionPath())
                        .ifPresent(path -> Clipboard.copy(this.getNodeText(path.getLastPathComponent(), NodeTextType.KEY)))
        );
        // 复制值
        final JMenuItem copyVal = new JMenuItem(BUNDLE.getString("json.tree.copy.val"));
        copyVal.addActionListener(_ ->
                Opt.ofNullable(tree.getSelectionPath())
                        .ifPresent(path -> Clipboard.copy(this.getNodeText(path.getLastPathComponent(), NodeTextType.VALUE)))
        );
        popupMenu.add(copyKey);
        popupMenu.add(copyVal);
        popupMenu.add(copyPath);
        popupMenu.add(copyItem);
        // 添加鼠标监听器
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    // 选中右键点击的节点
                    final TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    tree.setSelectionPath(path);
                    // 显示菜单
                    if (Objects.nonNull(path)) {
                        popupMenu.show(tree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * 从树节点提取可复制的文本
     *
     * @param node 选择的节点
     * @param type 提取类型
     * @return {@link String }
     */
    private String getNodeText(final Object node, final NodeTextType type) {
        return switch (node) {
            // 确认是`DefaultMutableTreeNode`才会处理节点
            case final DefaultMutableTreeNode treeNode -> Opt.ofNullable(treeNode.getUserObject())
                    .map(Object::toString)
                    .filter(StrUtil::isNotEmpty)
                    .map(item -> switch (type) {
                        // 对象
                        case OBJECT -> item;
                        // 键
                        case KEY -> extractFirstObjectKey(item);
                        // 值
                        case VALUE -> extractFirstObjectValue(item);
                    }).filter(Objects::nonNull).map(Object::toString).filter(StrUtil::isNotEmpty).orElseGet(() -> Convert.toStr(node));
            case null, default -> Convert.toStr(node);
        };
    }

    /**
     * 构建JSON路径
     *
     * @param selectionPath 选择的路径
     * @return {@link String }
     */
    private String buildJsonPath(final TreePath selectionPath) {
        // 从根节点之后开始遍历
        return IntStream.range(1, selectionPath.getPathCount())
                // 获取每个路径组件
                .mapToObj(selectionPath::getPathComponent).filter(Objects::nonNull)
                // 确保是`DefaultMutableTreeNode`类型
                .filter(DefaultMutableTreeNode.class::isInstance)
                // 将对象转换为`DefaultMutableTreeNode`类型
                .map(DefaultMutableTreeNode.class::cast)
                // 获取用户选择对象
                .map(DefaultMutableTreeNode::getUserObject).filter(Objects::nonNull)
                // 确保是`JsonNodeParser.JsonNode`类型
                .filter(JsonNodeParser.JsonNode.class::isInstance)
                // 将对象转换为`JsonNodeParser.JsonNode`类型
                .map(JsonNodeParser.JsonNode.class::cast)
                // 获取键
                .map(JsonNodeParser.JsonNode::key).filter(StrUtil::isNotEmpty)
                // 组合各节点的路径表达式
                .map(key ->
                        // 匹配数组索引模式
                        Opt.ofNullable(ARRAY_INDEX_PATTERN.matcher(key))
                                // 检查是否匹配成功
                                .filter(Matcher::matches)
                                // 格式化数组索引
                                .map(item -> "[%d]".formatted(Convert.toInt(item.group(1))))
                                // 格式化普通键值
                                .orElseGet(() -> ".%s".formatted(key))
                )
                // 将所有部分连接成最终的 JSON 路径字符串
                .collect(Collectors.joining("", "$", ""));
    }

    /**
     * 初始化搜索功能相关组件
     */
    private void initSearchFeature() {
        // 初始化搜索定时器（延迟执行搜索，合并连续输入）
        this.searchTimer = new Timer(SEARCH_DEBOUNCE_MS, _ -> {
            // 如正在搜索则停止当前搜索
            if (this.isSearching) return;
            // 重置搜索相关变量
            this.currentMatchIndex = -1;
            this.isSearching = Boolean.TRUE;
            final long sequence = this.searchSequence.get();
            final String keyword = this.searchText;
            // 在 EDT 捕获树模型根节点（Swing 组件模型不允许在后台线程访问）
            final TreeModel model = this.jsonTree.getModel();
            if (Objects.isNull(model) || Objects.isNull(model.getRoot())) {
                this.isSearching = Boolean.FALSE;
                return;
            }
            final DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
            // 执行搜索
            CompletableFuture.supplyAsync(() -> {
                final List<TreePath> result = new ArrayList<>();
                this.collectMatches(root, new TreePath(root), keyword, result);
                return result;
            }, AppExecutorUtil.getAppExecutorService()).thenAccept(result ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (sequence != this.searchSequence.get()) {
                            this.isSearching = Boolean.FALSE;
                            return;
                        }
                        this.matches.clear();
                        this.matches.addAll(result);
                        if (CollUtil.isNotEmpty(this.matches)) {
                            this.currentMatchIndex = 0;
                            this.scrollToMatch(this.currentMatchIndex);
                        }
                        this.jsonTree.repaint();
                        this.isSearching = Boolean.FALSE;
                    })
            ).exceptionally(_ -> {
                ApplicationManager.getApplication().invokeLater(() -> this.isSearching = Boolean.FALSE);
                return null;
            });
        });
        this.searchTimer.setRepeats(Boolean.FALSE);
        // 添加键盘监听器
        this.jsonTree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(final KeyEvent e) {
                final char keyChar = e.getKeyChar();
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 退格键
                    if (keyChar == KeyEvent.VK_BACK_SPACE && StrUtil.isNotEmpty(JsonTreePanel.this.searchText)) {
                        JsonTreePanel.this.searchText = StrUtil.sub(JsonTreePanel.this.searchText, 0, JsonTreePanel.this.searchText.length() - 1);
                        this.restartSearchTimer();
                    }
                    // 退出键
                    else if (keyChar == KeyEvent.VK_ESCAPE) {
                        JsonTreePanel.this.searchSequence.incrementAndGet();
                        JsonTreePanel.this.isSearching = Boolean.FALSE;
                        JsonTreePanel.this.searchText = "";
                        JsonTreePanel.this.matches.clear();
                        JsonTreePanel.this.jsonTree.repaint();
                        JsonTreePanel.this.currentMatchIndex = -1;
                    }
                    // 其他键
                    else if (!Character.isISOControl(keyChar)) {
                        JsonTreePanel.this.searchText += Character.toLowerCase(keyChar);
                        this.restartSearchTimer();
                    }
                    this.updateSearchField();
                });
            }

            @Override
            public void keyPressed(final KeyEvent e) {
                final int keyCode = e.getKeyCode();
                if (keyCode == KeyEvent.VK_ENTER && CollUtil.isNotEmpty(JsonTreePanel.this.matches)) {
                    JsonTreePanel.this.currentMatchIndex = (JsonTreePanel.this.currentMatchIndex + 1) % JsonTreePanel.this.matches.size();
                    JsonTreePanel.this.scrollToMatch(JsonTreePanel.this.currentMatchIndex);
                } else if (keyCode == KeyEvent.VK_UP && CollUtil.isNotEmpty(JsonTreePanel.this.matches)) {
                    JsonTreePanel.this.currentMatchIndex = (JsonTreePanel.this.currentMatchIndex - 1 + JsonTreePanel.this.matches.size()) % JsonTreePanel.this.matches.size();
                    JsonTreePanel.this.scrollToMatch(JsonTreePanel.this.currentMatchIndex);
                }
            }

            /**
             * 重启搜索定时器
             */
            private void restartSearchTimer() {
                JsonTreePanel.this.searchSequence.incrementAndGet();
                if (JsonTreePanel.this.searchTimer.isRunning()) {
                    JsonTreePanel.this.searchTimer.restart();
                } else {
                    JsonTreePanel.this.searchTimer.start();
                }
            }

            /**
             * 更新搜索字段
             */
            private void updateSearchField() {
                if (StrUtil.isNotEmpty(JsonTreePanel.this.searchText)) {
                    JsonTreePanel.this.searchField.setText(JsonTreePanel.this.searchText);
                } else {
                    JsonTreePanel.this.searchField.setText("");
                }
                JsonTreePanel.this.searchField.setForeground(UIUtil.getTreeForeground());
            }
        });
    }

    /**
     * 收集匹配的节点路径
     *
     * @param node node
     * @param path 路径
     */
    private void collectMatches(final DefaultMutableTreeNode node,
                                final TreePath path,
                                final String keyword,
                                final List<TreePath> target) {
        // 判断节点是否匹配搜索条件
        if (switch (node.getUserObject()) {
            case final JsonNodeParser.JsonNode jsonNode -> ("%s:%s".formatted(jsonNode.key(), jsonNode.value()))
                    .toLowerCase(Locale.ROOT).contains(keyword);
            default -> Convert.toStr(node).toLowerCase(Locale.ROOT).contains(keyword);
        }) {
            target.add(path);
        }
        // 判断节点子项是否匹配搜索条件
        final Enumeration<?> children = node.children();
        while (CollUtil.isNotEmpty(children) && children.hasMoreElements()) {
            final DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
            this.collectMatches(child, path.pathByAddingChild(child), keyword, target);
        }
    }

    /**
     * 滚动到指定索引的匹配项
     *
     * @param index 指数
     */
    private void scrollToMatch(final int index) {
        final TreePath path = this.matches.get(index);
        this.jsonTree.expandPath(path.getParentPath());
        this.jsonTree.setSelectionPath(path);
        this.jsonTree.scrollPathToVisible(path);
    }
}
