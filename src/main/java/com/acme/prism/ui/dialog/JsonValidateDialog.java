package com.acme.prism.ui.dialog;

import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.enums.SupportedLanguages;
import com.acme.prism.core.json.JsonSchemaGenerator;
import com.acme.prism.core.json.JsonSchemaValidator;
import com.acme.prism.core.json.JsonSchemaValidator.ValidationIssue;
import com.acme.prism.core.json.JsonSchemaValidator.ValidationOutcome;
import com.acme.prism.ui.editor.Editor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSON Schema 校验对话框：JSON 数据对照 Schema 逐字段校验。
 *
 * <p>易用交互：目标 JSON 自动带入（只读预览）；Schema 自动识别（当前内容为 Schema 时直接填入），
 * 或点「从当前 JSON 生成」一键生成；校验结果以表格展示失败项与汇总。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public class JsonValidateDialog extends DialogWrapper {
    /**
     * 对话框初始宽度
     */
    private static final int DIALOG_WIDTH = 720;
    /**
     * 对话框初始高度
     */
    private static final int DIALOG_HEIGHT = 600;
    /**
     * 结果表格区高度（像素）
     */
    private static final int RESULT_TABLE_HEIGHT = 180;
    /**
     * 结果表格区最小高度（像素）
     */
    private static final int RESULT_TABLE_MIN_HEIGHT = 140;
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 编辑器项目
     */
    private final Project project;
    /**
     * 校验目标 JSON
     */
    private final String jsonText;
    /**
     * Schema 编辑器
     */
    private EditorTextField schemaEditor;
    /**
     * 结果汇总标签
     */
    private JLabel summaryLabel;
    /**
     * 结果表格
     */
    private JBTable resultTable;

    public JsonValidateDialog(final Project project, final String jsonText) {
        super(project, Boolean.TRUE);
        this.project = project;
        this.jsonText = jsonText;
        this.init();
    }

    @Override
    protected void init() {
        super.init();
        this.setModal(Boolean.FALSE);
        this.setResizable(Boolean.TRUE);
        this.setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        this.setTitle(BUNDLE.getString("json.validate.title"));
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(this.createTargetPanel(), BorderLayout.NORTH);
        panel.add(this.createSchemaPanel(), BorderLayout.CENTER);
        panel.add(this.createResultPanel(), BorderLayout.SOUTH);
        return panel;
    }

    /**
     * 创建校验目标 JSON 面板（只读预览）。
     *
     * @return 面板
     */
    private JComponent createTargetPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(BUNDLE.getString("json.validate.target")), BorderLayout.NORTH);
        final EditorTextField viewer = this.createEditor(Boolean.FALSE);
        viewer.setText(this.jsonText);
        panel.add(new JBScrollPane(viewer), BorderLayout.CENTER);
        return panel;
    }

    /**
     * 创建 Schema 面板（可编辑 + 一键生成按钮）。
     *
     * @return 面板
     */
    private JComponent createSchemaPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, 4));
        final JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel(BUNDLE.getString("json.validate.schema")), BorderLayout.WEST);
        final JButton generateButton = new JButton(BUNDLE.getString("json.validate.generate.schema"));
        generateButton.addActionListener(_ -> this.fillGeneratedSchema());
        header.add(generateButton, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        this.schemaEditor = this.createEditor(Boolean.TRUE);
        // Schema 自动识别：当前内容本身为 Schema 时直接填入
        this.schemaEditor.setText(this.detectSchema());
        panel.add(new JBScrollPane(this.schemaEditor), BorderLayout.CENTER);
        return panel;
    }

    /**
     * 创建结果面板（汇总 + 校验按钮 + 结果表格）。
     *
     * @return 面板
     */
    private JComponent createResultPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, 4));
        this.summaryLabel = new JLabel(" ");
        final JButton validateButton = new JButton(BUNDLE.getString("json.validate.run"));
        validateButton.addActionListener(_ -> this.runValidation());
        final JPanel header = new JPanel(new BorderLayout());
        header.add(this.summaryLabel, BorderLayout.CENTER);
        header.add(validateButton, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        this.resultTable = new JBTable(new DefaultTableModel(
                new Object[0][0],
                new Object[]{BUNDLE.getString("json.validate.path"), BUNDLE.getString("json.validate.expected"),
                        BUNDLE.getString("json.validate.actual"), BUNDLE.getString("json.validate.message")}
        ));
        this.resultTable.setFillsViewportHeight(Boolean.TRUE);
        // 结果区固定最小高度，避免被 Schema 编辑器挤压
        final JBScrollPane resultScroll = new JBScrollPane(this.resultTable);
        resultScroll.setPreferredSize(new Dimension(0, RESULT_TABLE_HEIGHT));
        resultScroll.setMinimumSize(new Dimension(0, RESULT_TABLE_MIN_HEIGHT));
        panel.add(resultScroll, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 创建 JSON 编辑器。
     *
     * @param editable 是否可编辑
     * @return 编辑器
     */
    private EditorTextField createEditor(final boolean editable) {
        // 统一复用编辑器创建入口
        final EditorTextField field = Editor.createEditorField(this.project, SupportedLanguages.JSON, "Dummy.json");
        // viewer 模式为只读（校验目标 JSON 预览）；非 viewer 可编辑（Schema 输入）
        field.setViewer(!editable);
        return field;
    }

    /**
     * 识别当前内容是否为 Schema（顶层含 {@code $schema} 或 {@code properties}）。
     *
     * @return Schema 文本；非 Schema 返回空串
     */
    private String detectSchema() {
        try {
            final Object parsed = JSON.parse(this.jsonText);
            if (parsed instanceof final JSONObject obj && (obj.containsKey("$schema") || obj.containsKey("properties"))) {
                return this.jsonText;
            }
        } catch (final Exception ignored) {
            // 非 Schema 内容，返回空串
        }
        return "";
    }

    /**
     * 从当前 JSON 生成 Schema 并填入编辑器。
     */
    private void fillGeneratedSchema() {
        final AtomicReference<String> generated = new AtomicReference<>();
        CompletableFuture
                .supplyAsync(() -> new JsonSchemaGenerator().process(this.jsonText), AppExecutorUtil.getAppExecutorService())
                .thenAccept(schema -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (StrUtil.isBlank(schema)) {
                        return;
                    }
                    this.schemaEditor.setText(schema);
                }));
    }

    /**
     * 执行校验并更新结果（后台线程计算，避免大 JSON 阻塞 EDT）。
     */
    private void runValidation() {
        final String schemaText = this.schemaEditor.getText();
        if (StrUtil.isBlank(schemaText)) {
            this.summaryLabel.setText(BUNDLE.getString("json.validate.schema.empty"));
            return;
        }
        CompletableFuture
                .supplyAsync(() -> JsonSchemaValidator.validate(this.jsonText, schemaText), AppExecutorUtil.getAppExecutorService())
                .thenAccept(outcome -> ApplicationManager.getApplication().invokeLater(() -> this.renderResult(outcome)))
                .exceptionally(error -> {
                    ApplicationManager.getApplication().invokeLater(() ->
                            this.summaryLabel.setText(BUNDLE.getString("json.validate.failed").formatted(error.getMessage())));
                    return null;
                });
    }

    /**
     * 渲染校验结果。
     *
     * @param outcome 校验结果
     */
    private void renderResult(final ValidationOutcome outcome) {
        if (outcome.issues().isEmpty()) {
            this.summaryLabel.setText(BUNDLE.getString("json.validate.pass").formatted(outcome.checkedCount()));
            // 通过时表格空状态提示（替换默认 Nothing to show）
            this.resultTable.getEmptyText().setText(BUNDLE.getString("json.validate.result.empty"));
        } else {
            this.summaryLabel.setText(BUNDLE.getString("json.validate.fail.summary")
                    .formatted(outcome.checkedCount(), outcome.issues().size()));
            this.resultTable.getEmptyText().setText("");
        }
        this.resultTable.setModel(this.createResultModel(outcome.issues()));
    }

    /**
     * 构建结果表格模型。
     *
     * @param issues 失败项
     * @return 表格模型
     */
    private DefaultTableModel createResultModel(final List<ValidationIssue> issues) {
        final Object[][] rows = issues.stream()
                .map(issue -> new Object[]{issue.path(), issue.expected(), issue.actual(), issue.message()})
                .toArray(Object[][]::new);
        return new DefaultTableModel(rows, new Object[]{
                BUNDLE.getString("json.validate.path"), BUNDLE.getString("json.validate.expected"),
                BUNDLE.getString("json.validate.actual"), BUNDLE.getString("json.validate.message")
        });
    }

    @Override
    protected Action @NotNull [] createActions() {
        // 移除默认按钮，校验按钮在面板内
        return new Action[0];
    }

    @Override
    protected JComponent createSouthPanel() {
        return null;
    }
}
