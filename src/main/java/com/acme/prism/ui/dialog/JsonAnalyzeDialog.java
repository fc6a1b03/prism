package com.acme.prism.ui.dialog;

import com.acme.prism.core.json.JsonAnalyzer;
import com.acme.prism.core.json.JsonAnalyzer.Stats;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * JSON 结构分析对话框：展示统计指标（键数/对象数/数组数/最大深度/大小）
 * 与重复键检测结果。
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public class JsonAnalyzeDialog extends DialogWrapper {
    /**
     * 对话框初始尺寸
     */
    private static final int DIALOG_WIDTH = 480;
    /**
     * 对话框初始高度
     */
    private static final int DIALOG_HEIGHT = 360;
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 编辑器项目
     */
    private final Project project;
    /**
     * 原始 JSON
     */
    private final String jsonText;
    /**
     * 中心面板
     */
    private final JPanel centerPanel;

    public JsonAnalyzeDialog(final Project project, final String jsonText) {
        super(project, Boolean.TRUE);
        this.project = project;
        this.jsonText = jsonText;
        this.centerPanel = new JPanel(new BorderLayout(0, 0));
        this.centerPanel.setBorder(BorderFactory.createEmptyBorder());
        this.centerPanel.add(new JLabel(BUNDLE.getString("json.to.any.load"), SwingConstants.CENTER), BorderLayout.CENTER);
        this.init();
    }

    @Override
    protected void init() {
        super.init();
        this.setModal(Boolean.FALSE);
        this.setResizable(Boolean.TRUE);
        this.setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        this.setTitle(BUNDLE.getString("json.analyze.title"));
    }

    @Override
    protected JComponent createCenterPanel() {
        // 后台线程计算统计与重复键，避免大 JSON 阻塞 EDT
        new Task.Backgroundable(this.project, BUNDLE.getString("json.analyze.progress"), Boolean.FALSE) {
            @Override
            public void run(@NotNull final ProgressIndicator indicator) {
                final Stats stats = JsonAnalyzer.analyze(JsonAnalyzeDialog.this.jsonText);
                final Map<String, Integer> duplicates = JsonAnalyzer.duplicateKeys(JsonAnalyzeDialog.this.jsonText);
                ApplicationManager.getApplication().invokeLater(
                        () -> JsonAnalyzeDialog.this.render(stats, duplicates),
                        ModalityState.stateForComponent(JsonAnalyzeDialog.this.centerPanel)
                );
            }
        }.queue();
        return this.centerPanel;
    }

    /**
     * 渲染统计与重复键内容。
     *
     * @param stats      统计结果
     * @param duplicates 重复键映射
     */
    private void render(final Stats stats, final Map<String, Integer> duplicates) {
        this.centerPanel.removeAll();
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(this.createStatsTable(stats), BorderLayout.NORTH);
        panel.add(this.createDuplicatePanel(duplicates), BorderLayout.CENTER);
        this.centerPanel.add(panel, BorderLayout.CENTER);
        this.centerPanel.revalidate();
        this.centerPanel.repaint();
    }

    /**
     * 创建统计表格。
     *
     * @param stats 统计结果
     * @return 表格
     */
    private JBTable createStatsTable(final Stats stats) {
        final Object[][] rows = {
                {BUNDLE.getString("json.analyze.keys"), stats.keys()},
                {BUNDLE.getString("json.analyze.objects"), stats.objects()},
                {BUNDLE.getString("json.analyze.arrays"), stats.arrays()},
                {BUNDLE.getString("json.analyze.depth"), stats.maxDepth()},
                {BUNDLE.getString("json.analyze.size"), formatSize(stats.sizeBytes())}
        };
        return new JBTable(new DefaultTableModel(rows, new Object[]{BUNDLE.getString("json.analyze.metric"), BUNDLE.getString("json.analyze.value")}));
    }

    /**
     * 创建重复键面板。
     *
     * @param duplicates 重复键映射
     * @return 面板
     */
    private JComponent createDuplicatePanel(final Map<String, Integer> duplicates) {
        final JPanel panel = new JPanel(new BorderLayout(0, 4));
        final JLabel title = new JLabel(BUNDLE.getString("json.analyze.duplicate"));
        panel.add(title, BorderLayout.NORTH);
        if (duplicates.isEmpty()) {
            final JLabel empty = new JLabel(BUNDLE.getString("json.analyze.duplicate.none"));
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            panel.add(empty, BorderLayout.CENTER);
            return panel;
        }
        final List<Object[]> rows = new ArrayList<>(duplicates.size());
        duplicates.forEach((key, count) -> rows.add(new Object[]{key, count}));
        final JBTable table = new JBTable(new DefaultTableModel(
                rows.toArray(Object[][]::new),
                new Object[]{BUNDLE.getString("json.analyze.duplicate.key"), BUNDLE.getString("json.analyze.duplicate.count")}
        ));
        table.setFillsViewportHeight(Boolean.TRUE);
        panel.add(new JBScrollPane(table), BorderLayout.CENTER);
        return panel;
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
}
