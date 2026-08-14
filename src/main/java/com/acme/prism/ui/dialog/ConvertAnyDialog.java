package com.acme.prism.ui.dialog;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.enums.AnyFile;
import com.acme.prism.common.enums.SupportedLanguages;
import com.acme.prism.core.notice.Notifier;
import com.acme.prism.core.parser.JsonParser;
import com.acme.prism.ui.editor.Editor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

/**
 * 转换各种文件对话框
 *
 * @author 拒绝者
 * @date 2025-01-26
 */
public class ConvertAnyDialog extends DialogWrapper {
    /**
     * 列宽采样行数上限（大表格只采样前 N 行计算列宽）
     */
    private static final int MAX_COLUMN_FIT_ROWS = 200;
    /**
     * 列宽像素上限
     */
    private static final int MAX_COLUMN_WIDTH = 200;
    /**
     * 对话框初始尺寸
     */
    private static final int DIALOG_SIZE = 800;
    /**
     * 导出按钮尺寸
     */
    private static final int EXPORT_BUTTON_SIZE = 35;
    /**
     * 导出文件的工作表名
     */
    private static final String EXPORT_SHEET_NAME = "Data";
    /**
     * 导出文件名模板
     */
    private static final String EXPORT_FILE_NAME_TEMPLATE = "export_%s.xlsx";
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 编辑器项目
     */
    private final Project project;
    /**
     * 原始JSON
     */
    private final String jsonText;
    /**
     * 卡片面板
     */
    private final JPanel cardPanel;
    /**
     * 表格集合
     */
    private final Map<AnyFile, JBTable> tableMap = new EnumMap<>(AnyFile.class);
    /**
     * 编辑器集合
     */
    private final Map<AnyFile, EditorTextField> editorMap = new EnumMap<>(AnyFile.class);

    public ConvertAnyDialog(final Project project, final String jsonText) {
        super(project, Boolean.TRUE);
        this.project = project;
        this.jsonText = jsonText;
        this.cardPanel = new JPanel(new CardLayout(0, 0));
        this.cardPanel.setBorder(BorderFactory.createEmptyBorder());
        this.init();
    }

    @Override
    protected void init() {
        super.init();
        this.setModal(Boolean.FALSE);
        this.setResizable(Boolean.TRUE);
        this.setSize(DIALOG_SIZE, DIALOG_SIZE);
        this.setTitle(BUNDLE.getString("dialog.convert.java.title"));
    }

    /**
     * 获取中心渲染器
     *
     * @return {@link DefaultTableCellRenderer }
     */
    private static DefaultTableCellRenderer getCenterRenderer() {
        final DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        // 垂直居中
        centerRenderer.setVerticalAlignment(JLabel.CENTER);
        // 水平居中
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        return centerRenderer;
    }

    /**
     * 删掉占位 JLabel
     */
    private void removePlaceholder() {
        Arrays.stream(this.cardPanel.getComponents()).filter(JLabel.class::isInstance).findFirst().ifPresent(this.cardPanel::remove);
    }

    /**
     * 懒加载
     *
     * @param fileType 文件类型
     */
    private void lazyLoad(final AnyFile fileType) {
        if (Objects.isNull(fileType)) {
            return;
        }
        // 加载 编辑器
        if (fileType.isEditor() && !this.editorMap.containsKey(fileType)) {
            new Task.Backgroundable(this.project, BUNDLE.getString("json.to.any.load.content.editor").formatted(fileType)) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                    final String converted = StrUtil.emptyIfNull(JsonParser.convert(ConvertAnyDialog.this.jsonText, fileType));
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 删掉旧占位
                        ConvertAnyDialog.this.removePlaceholder();
                        // 放置新组件：EditorTextField 自带滚动条，直接放入卡片
                        //（外层再包 JBScrollPane 会形成双层滚动条，超长单行如 Markdown 表格行出现水平滚动条时互相作用，滚动条异常缩小）
                        final EditorTextField editor = ConvertAnyDialog.this.buildEditor(fileType, converted);
                        ConvertAnyDialog.this.editorMap.put(fileType, editor);
                        ConvertAnyDialog.this.cardPanel.add(editor, fileType.name());
                        ((CardLayout) ConvertAnyDialog.this.cardPanel.getLayout()).show(ConvertAnyDialog.this.cardPanel, fileType.name());
                        // 重新加载
                        ConvertAnyDialog.this.cardPanel.revalidate();
                    }, ModalityState.stateForComponent(ConvertAnyDialog.this.cardPanel));
                }
            }.queue();
        }
        // 加载 表格
        else if (fileType.isTable() && !this.tableMap.containsKey(fileType)) {
            new Task.Backgroundable(this.project, BUNDLE.getString("json.to.any.load.content.table").formatted(fileType)) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                    final TableData tableData = ConvertAnyDialog.this.createTableData(fileType, ConvertAnyDialog.this.jsonText);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ConvertAnyDialog.this.removePlaceholder();
                        final JBTable table = ConvertAnyDialog.this.createTable(tableData);
                        ConvertAnyDialog.this.tableMap.put(fileType, table);
                        final JPanel panel = new JPanel(new BorderLayout());
                        panel.add(ConvertAnyDialog.this.createButton(table), BorderLayout.NORTH);
                        panel.add(new JBScrollPane(table), BorderLayout.CENTER);
                        ConvertAnyDialog.this.cardPanel.add(panel, fileType.name());
                        ((CardLayout) ConvertAnyDialog.this.cardPanel.getLayout()).show(ConvertAnyDialog.this.cardPanel, fileType.name());
                        ConvertAnyDialog.this.cardPanel.revalidate();
                    }, ModalityState.stateForComponent(ConvertAnyDialog.this.cardPanel));
                }
            }.queue();
        }
        // 已加载过，直接切换
        else {
            ((CardLayout) this.cardPanel.getLayout()).show(this.cardPanel, fileType.name());
        }
    }

    /**
     * 根据内容调整列
     *
     * @param table 表格
     */
    private void fitColumnsToContent(final JBTable table) {
        final JTableHeader header = table.getTableHeader();
        final int spacing = table.getIntercellSpacing().width;
        final int sampledRowCount = Math.min(table.getRowCount(), MAX_COLUMN_FIT_ROWS);
        Collections.list(table.getColumnModel().getColumns()).forEach(column -> {
            // 使用模型索引定位列（自动创建的 TableColumn identifier 为 null，getColumnIndex 会抛 IAE）
            final int colIdx = column.getModelIndex();
            // 计算表头宽度 (不超过上限)
            final int headerWidth = Math.min(
                    header.getDefaultRenderer()
                            .getTableCellRendererComponent(table, column.getHeaderValue(), Boolean.FALSE, Boolean.FALSE, -1, colIdx)
                            .getPreferredSize().width, MAX_COLUMN_WIDTH
            );
            // 计算数据最大宽度 (不超过上限)
            final int dataMaxWidth = IntStream.range(0, sampledRowCount)
                    .map(row -> Math.min(
                            table.getCellRenderer(row, colIdx)
                                    .getTableCellRendererComponent(table, table.getValueAt(row, colIdx), Boolean.FALSE, Boolean.FALSE, row, colIdx)
                                    .getPreferredSize().width, MAX_COLUMN_WIDTH
                    )).max().orElse(headerWidth);
            // 最终列宽 = max(表头, 数据) + 间距
            column.setPreferredWidth(Math.max(headerWidth, dataMaxWidth) + spacing);
        });
        // 配置表格渲染器
        table.setDefaultRenderer(Object.class, getCenterRenderer());
    }

    @Override
    protected JComponent createSouthPanel() {
        // 不显示底部按钮面板
        return null;
    }

    @Override
    protected Action @NotNull [] createActions() {
        // 移除所有默认按钮
        return new Action[0];
    }

    @Override
    public void dispose() {
        // 清空编辑器内容
        this.editorMap.values().forEach(Container::removeAll);
        super.dispose();
    }

    @Override
    protected @NotNull DialogStyle getStyle() {
        return DialogStyle.COMPACT;
    }

    @Override
    protected JComponent createCenterPanel() {
        final ButtonGroup group = new ButtonGroup();
        final JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        final JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        // 占位加载组件
        this.cardPanel.add(new JLabel(BUNDLE.getString("json.to.any.load"), SwingConstants.CENTER), AnyFile.CLASS.name());
        // 添加 按钮到面板
        Arrays.stream(AnyFile.values()).filter(AnyFile::isRadio)
                .forEach(fileType -> typePanel.add(this.createRadioButton(fileType, group)));
        // 设置默认显示
        ((CardLayout) this.cardPanel.getLayout()).show(this.cardPanel, AnyFile.CLASS.name());
        // 设置默认加载
        this.lazyLoad(AnyFile.CLASS);
        mainPanel.add(typePanel, BorderLayout.NORTH);
        mainPanel.add(this.cardPanel, BorderLayout.CENTER);
        return mainPanel;
    }

    /**
     * 创建标准化按钮
     *
     * @param table 表格
     * @return 配置好的JButton实例
     */
    private JButton createButton(final JBTable table) {
        final JButton button = new JButton();
        button.setEnabled(Boolean.TRUE);
        button.setIcon(AllIcons.General.Export);
        button.addActionListener(_ -> this.exportXlsx(table));
        button.setPreferredSize(new Dimension(EXPORT_BUTTON_SIZE, EXPORT_BUTTON_SIZE));
        return button;
    }

    /**
     * 创建单选按钮
     *
     * @param fileType 文件类型
     * @param group    按钮组
     * @return {@link JRadioButton }
     */
    private JRadioButton createRadioButton(final AnyFile fileType, final ButtonGroup group) {
        final JRadioButton radio = new JRadioButton(StrUtil.toCamelCase(fileType.name().toLowerCase(Locale.ROOT)));
        group.add(radio);
        if (fileType == AnyFile.CLASS) {
            radio.setSelected(Boolean.TRUE);
        }
        radio.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                this.lazyLoad(fileType);
            }
        });
        return radio;
    }

    /**
     * 构建编辑器
     *
     * @param anyFile   任何文件
     * @param converted 转换
     * @return {@link EditorTextField }
     */
    private EditorTextField buildEditor(final AnyFile anyFile, final String converted) {
        // 统一复用编辑器创建入口
        final EditorTextField field = Editor.createEditorField(
                this.project, SupportedLanguages.getByAnyFile(anyFile), "Dummy.%s".formatted(anyFile.extension()));
        field.setText(converted);
        Editor.reformat(field);
        return field;
    }

    /**
     * 为类型创建表格数据
     *
     * @param anyFile  任何文件
     * @param jsonText JSON文本
     * @return {@link TableData }
     */
    private TableData createTableData(final AnyFile anyFile, final String jsonText) {
        return Opt.ofBlankAble(JsonParser.convert(jsonText, anyFile)).map(JSON::parseObject)
                .map(item -> new TableData(
                        JSON.parseObject(item.getString("data"), new TypeReference<>() {
                        }),
                        JSON.parseObject(item.getString("headers"), new TypeReference<>() {
                        })
                )).orElseGet(TableData::empty);
    }

    /**
     * 创建表格
     *
     * @param tableData 表格数据
     * @return {@link JBTable }
     */
    private JBTable createTable(final TableData tableData) {
        final JBTable table = new JBTable();
        // 关闭自动调整
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        // 取消边框
        table.setBorder(BorderFactory.createEmptyBorder());
        table.setModel(new DefaultTableModel(tableData.data(), tableData.headers()));
        this.fitColumnsToContent(table);
        // 注册表格监听器
        table.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                ConvertAnyDialog.this.fitColumnsToContent(table);
            }
        });
        return table;
    }

    /**
     * 导出xlsx
     *
     * @param table 表格
     */
    private void exportXlsx(final JTable table) {
        // 目录选择器
        final VirtualFile selectedDirectory = FileChooser.chooseFile(
                new FileChooserDescriptor(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE),
                this.project, null
        );
        if (Objects.nonNull(selectedDirectory)) {
            final String[] headers = new String[table.getColumnCount()];
            final Object[][] rows = new Object[table.getRowCount()][table.getColumnCount()];
            for (int colIdx = 0; colIdx < table.getColumnCount(); colIdx++) {
                headers[colIdx] = table.getColumnName(colIdx);
            }
            for (int rowIdx = 0; rowIdx < table.getRowCount(); rowIdx++) {
                for (int colIdx = 0; colIdx < table.getColumnCount(); colIdx++) {
                    rows[rowIdx][colIdx] = table.getValueAt(rowIdx, colIdx);
                }
            }
            new Task.Backgroundable(this.project, BUNDLE.getString("export.xlsx.progress.msg"), Boolean.FALSE) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                    final String filePath = Convert.toStr(Paths.get(selectedDirectory.getPath(), EXPORT_FILE_NAME_TEMPLATE.formatted(DatePattern.PURE_DATETIME_FORMATTER.format(LocalDateTime.now()))));
                    try (final Workbook workbook = new XSSFWorkbook()) {
                        final Sheet sheet = workbook.createSheet(EXPORT_SHEET_NAME);
                        final CellStyle cellStyle = workbook.createCellStyle();
                        cellStyle.setAlignment(HorizontalAlignment.CENTER);
                        final Row headerRow = sheet.createRow(0);
                        for (int colIdx = 0; colIdx < headers.length; colIdx++) {
                            final Cell cell = headerRow.createCell(colIdx);
                            cell.setCellStyle(cellStyle);
                            cell.setCellValue(headers[colIdx]);
                        }
                        for (int rowIdx = 0; rowIdx < rows.length; rowIdx++) {
                            final Row row = sheet.createRow(rowIdx + 1);
                            for (int colIdx = 0; colIdx < headers.length; colIdx++) {
                                final Cell cell = row.createCell(colIdx);
                                final Object value = rows[rowIdx][colIdx];
                                if (value instanceof Number) {
                                    cell.setCellValue(Convert.toDouble(value));
                                } else {
                                    cell.setCellValue(Convert.toStr(value));
                                }
                            }
                        }
                        for (int colIdx = 0; colIdx < headers.length; colIdx++) {
                            sheet.autoSizeColumn(colIdx);
                        }
                        try (final FileOutputStream fileOut = new FileOutputStream(filePath)) {
                            workbook.write(fileOut);
                        }
                    } catch (final Exception e) {
                        Notifier.notifyError("%s: %s".formatted(BUNDLE.getString("export.xlsx.error.msg"), e.getMessage()), ConvertAnyDialog.this.project);
                    }
                }
            }.queue();
        }
    }

    private record TableData(Object[][] data, Object[] headers) {
        private static TableData empty() {
            return new TableData(new Object[0][0], new Object[0]);
        }
    }
}
