package com.acme.prism.core.settings;

import cn.hutool.core.util.StrUtil;
import com.acme.prism.core.minimap.MinimapEditorFactoryListener;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static com.acme.prism.ui.MainToolWindowFactory.PROJECT_NAME;

/**
 * 插件设置
 * @author 拒绝者
 * @date 2025-02-02
 */
public class PluginSettings implements Configurable {
    /**
     * 加载语言资源文件
     */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.PrismBundle");
    /**
     * 设置组件
     */
    private PluginSettingsComponent component;

    /**
     * 获取设置
     * @return {@link PluginSettingsState }
     */
    public static PluginSettingsState of() {
        return PluginSettingsState.getInstance();
    }

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return PROJECT_NAME;
    }

    @NotNull
    @Override
    public JComponent getPreferredFocusedComponent() {
        return component.getPreferredFocusedComponent();
    }

    @Override
    public @Nullable JComponent createComponent() {
        return new PluginSettingsComponent().getPanel(component -> this.component = component);
    }

    @Override
    public boolean isModified() {
        // 获取设置状态
        final PluginSettingsState settings = of();
        // 判断全局状态与设置组件状态
        return component.getCopyJson() != settings.copyJson
                || component.getJsonHelper() != settings.jsonHelper
                || component.getPortSearch() != settings.portSearchEnabled
                || component.getProjectSearch() != settings.projectSearchEnabled
                || component.getHttpSearch() != settings.httpSearchEnabled
                || component.getArchiveNode() != settings.archiveNodeEnabled
                || component.getFileInfoNode() != settings.fileInfoNodeEnabled
                || component.getRainbowBracketPair() != settings.rainbowBracketPairEnabled
                || component.getRainbowVariable() != settings.rainbowVariableEnabled
                || component.getColorHighlighter() != settings.colorHighlighterEnabled
                || component.getMinimap() != settings.minimapEnabled
                || !Objects.equals(component.getAesKey(), settings.aesKey);
    }

    @Override
    public void apply() {
        // 获取设置状态
        final PluginSettingsState settings = of();
        // 记录压缩包节点开关旧值（用于变更后刷新项目树）
        final boolean previousArchiveNodeEnabled = settings.archiveNodeEnabled;
        // 记录文件注释与修改时间开关旧值（用于变更后刷新项目树）
        final boolean previousFileInfoNodeEnabled = settings.fileInfoNodeEnabled;
        // 记录代码地图开关旧值（用于变更后同步编辑器挂载）
        final boolean previousMinimapEnabled = settings.minimapEnabled;
        // 将设置组件状态覆盖全局状态
        settings.copyJson = component.getCopyJson();
        settings.jsonHelper = component.getJsonHelper();
        settings.portSearchEnabled = component.getPortSearch();
        settings.projectSearchEnabled = component.getProjectSearch();
        settings.httpSearchEnabled = component.getHttpSearch();
        settings.archiveNodeEnabled = component.getArchiveNode();
        settings.fileInfoNodeEnabled = component.getFileInfoNode();
        settings.rainbowBracketPairEnabled = component.getRainbowBracketPair();
        settings.rainbowVariableEnabled = component.getRainbowVariable();
        settings.colorHighlighterEnabled = component.getColorHighlighter();
        settings.minimapEnabled = component.getMinimap();
        settings.aesKey = StrUtil.emptyIfNull(component.getAesKey());
        // 代码地图开关变更后同步全部已打开编辑器的挂载状态
        if (previousMinimapEnabled != settings.minimapEnabled) {
            MinimapEditorFactoryListener.syncAllEditors();
        }
        // 压缩包节点/文件信息开关变更后刷新全部打开项目的项目树（平台对树节点与装饰结果有缓存，需主动刷新）
        if (previousArchiveNodeEnabled != settings.archiveNodeEnabled
                || previousFileInfoNodeEnabled != settings.fileInfoNodeEnabled) {
            for (final Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                ProjectView.getInstance(openProject).refresh();
            }
        }
    }

    @Override
    public void reset() {
        final PluginSettingsState settings = of();
        component.setCopyJson(settings.copyJson);
        component.setJsonHelper(settings.jsonHelper);
        component.setPortSearch(settings.portSearchEnabled);
        component.setProjectSearch(settings.projectSearchEnabled);
        component.setHttpSearch(settings.httpSearchEnabled);
        component.setArchiveNode(settings.archiveNodeEnabled);
        component.setFileInfoNode(settings.fileInfoNodeEnabled);
        component.setRainbowBracketPair(settings.rainbowBracketPairEnabled);
        component.setRainbowVariable(settings.rainbowVariableEnabled);
        component.setColorHighlighter(settings.colorHighlighterEnabled);
        component.setMinimap(settings.minimapEnabled);
        component.setAesKey(settings.aesKey);
    }

    @Override
    public void disposeUIResources() {
        component = null;
    }


    /**
     * 设置组件详细
     * @author 拒绝者
     * @date 2025-02-02
     */
    @SuppressWarnings("unused")
    private static class PluginSettingsComponent {
        private final JPanel mainPanel;
        private final JBCheckBox copyJson = new JBCheckBox(BUNDLE.getString("plugin.setting.copy.json"));
        private final JBCheckBox jsonHelper = new JBCheckBox(BUNDLE.getString("plugin.setting.json.helper"));
        private final JBCheckBox projectSearch = new JBCheckBox(BUNDLE.getString("project.search.group.name"));
        private final JBCheckBox httpSearch = new JBCheckBox(BUNDLE.getString("http.search.group.name"));
        private final JBCheckBox portSearch = new JBCheckBox(BUNDLE.getString("port.search.group.name"));
        private final JBCheckBox archiveNode = new JBCheckBox(BUNDLE.getString("plugin.setting.archive.node"));
        private final JBCheckBox fileInfoNode = new JBCheckBox(BUNDLE.getString("plugin.setting.file.info.node"));
        private final JBCheckBox rainbowBracketPair = new JBCheckBox(BUNDLE.getString("plugin.setting.rainbow.bracket.pair"));
        private final JBCheckBox rainbowVariable = new JBCheckBox(BUNDLE.getString("plugin.setting.rainbow.variable"));
        private final JBCheckBox colorHighlighter = new JBCheckBox(BUNDLE.getString("plugin.setting.color.highlighter"));
        private final JBCheckBox minimap = new JBCheckBox(BUNDLE.getString("plugin.setting.minimap"));
        private final JBTextField aesKey = new JBTextField();

        public PluginSettingsComponent() {
            // 密钥为全局配置（application 级），配一次所有项目通用，附灰色小字提示
            final JPanel aesKeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            aesKey.setPreferredSize(new Dimension(300, aesKey.getPreferredSize().height));
            final JLabel aesKeyTip = new JLabel(BUNDLE.getString("plugin.setting.aes.key.tip"));
            aesKeyTip.setForeground(UIUtil.getContextHelpForeground());
            aesKeyRow.add(aesKey);
            aesKeyRow.add(aesKeyTip);
            mainPanel = FormBuilder.createFormBuilder()
                    .addComponent(of(BUNDLE.getString("plugin.setting.title1"), copyJson, jsonHelper), 1)
                    .addComponent(of(BUNDLE.getString("plugin.setting.title2"), projectSearch, httpSearch, portSearch), 1)
                    .addComponent(of(BUNDLE.getString("plugin.setting.title3"), archiveNode, fileInfoNode), 1)
                    .addComponent(of(BUNDLE.getString("plugin.setting.title4"), rainbowBracketPair, rainbowVariable, colorHighlighter, minimap), 1)
                    .addComponent(of(BUNDLE.getString("plugin.setting.title5"), aesKeyRow), 1)
                    .addComponentFillVertically(new JPanel(), 0)
                    .getPanel();
        }

        /**
         * 组合设置
         * @param title      标题
         * @param components 组件
         * @return {@link JPanel }
         */
        public static JPanel of(final String title, final Component... components) {
            final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            Arrays.stream(components).forEach(panel::add);
            panel.setBorder(IdeBorderFactory.createTitledBorder(title));
            return panel;
        }

        /**
         * 获取面板
         * @param callback 返回组件本体
         * @return {@link JPanel }
         */
        public JPanel getPanel(final Consumer<PluginSettingsComponent> callback) {
            if (Objects.nonNull(callback)) {
                callback.accept(this);
            }
            return mainPanel;
        }

        @NotNull
        public JComponent getPreferredFocusedComponent() {
            return jsonHelper;
        }

        public boolean getCopyJson() {
            return copyJson.isSelected();
        }

        public void setCopyJson(final boolean status) {
            copyJson.setSelected(status);
        }

        public boolean getJsonHelper() {
            return jsonHelper.isSelected();
        }

        public void setJsonHelper(final boolean status) {
            jsonHelper.setSelected(status);
        }

        public boolean getPortSearch() {
            return portSearch.isSelected();
        }

        public void setPortSearch(final boolean status) {
            portSearch.setSelected(status);
        }

        public boolean getProjectSearch() {
            return projectSearch.isSelected();
        }

        public void setProjectSearch(final boolean status) {
            projectSearch.setSelected(status);
        }

        public boolean getHttpSearch() {
            return httpSearch.isSelected();
        }

        public void setHttpSearch(final boolean status) {
            httpSearch.setSelected(status);
        }

        public boolean getArchiveNode() {
            return archiveNode.isSelected();
        }

        public void setArchiveNode(final boolean status) {
            archiveNode.setSelected(status);
        }

        public boolean getFileInfoNode() {
            return fileInfoNode.isSelected();
        }

        public void setFileInfoNode(final boolean status) {
            fileInfoNode.setSelected(status);
        }

        public boolean getRainbowBracketPair() {
            return rainbowBracketPair.isSelected();
        }

        public void setRainbowBracketPair(final boolean status) {
            rainbowBracketPair.setSelected(status);
        }

        public boolean getRainbowVariable() {
            return rainbowVariable.isSelected();
        }

        public void setRainbowVariable(final boolean status) {
            rainbowVariable.setSelected(status);
        }

        public boolean getColorHighlighter() {
            return colorHighlighter.isSelected();
        }

        public void setColorHighlighter(final boolean status) {
            colorHighlighter.setSelected(status);
        }

        public boolean getMinimap() {
            return minimap.isSelected();
        }

        public void setMinimap(final boolean status) {
            minimap.setSelected(status);
        }

        public String getAesKey() {
            return aesKey.getText();
        }

        public void setAesKey(final String key) {
            aesKey.setText(key);
        }
    }
}