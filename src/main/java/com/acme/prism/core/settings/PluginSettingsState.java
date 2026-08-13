package com.acme.prism.core.settings;

import cn.hutool.core.lang.Opt;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 设置状态
 *
 * @author 拒绝者
 * @date 2025-02-02
 */
@State(
        name = "com.acme.json.helper.core.settings.SettingsState",
        storages = @Storage("JsonHelperGlobal.xml")
)
public class PluginSettingsState implements PersistentStateComponent<PluginSettingsState> {
    /** 复制JSON默认配置 */
    public boolean copyJson = Boolean.TRUE;
    /** JSON助手默认配置 */
    public boolean jsonHelper = Boolean.TRUE;
    /** 端口搜索开关（默认开启） */
    public boolean portSearchEnabled = Boolean.TRUE;
    /** 项目搜索开关（默认开启） */
    public boolean projectSearchEnabled = Boolean.TRUE;
    /** HTTP 请求文件搜索开关（默认开启） */
    public boolean httpSearchEnabled = Boolean.TRUE;
    /** 项目树压缩包节点开关（默认开启） */
    public boolean archiveNodeEnabled = Boolean.TRUE;
    /** 项目树文件注释与修改时间开关（默认开启） */
    public boolean fileInfoNodeEnabled = Boolean.TRUE;
    /** 彩虹括号配对高亮开关（默认开启） */
    public boolean rainbowBracketPairEnabled = Boolean.TRUE;
    /** 颜色字面量 Gutter 色块高亮开关（默认开启） */
    public boolean colorHighlighterEnabled = Boolean.TRUE;
    /** 彩虹变量高亮开关（默认开启） */
    public boolean rainbowVariableEnabled = Boolean.TRUE;
    /** 代码地图开关（默认开启） */
    public boolean minimapEnabled = Boolean.TRUE;
    /** 代码地图宽度（像素，默认 55，正好 2:1 整数缩放采样最干净；可拖拽左缘在 40~200 间调节） */
    public int minimapWidth = 55;
    /** 代码地图错误线条整行高亮开关（默认开启） */
    public boolean minimapErrorStripeEnabled = Boolean.TRUE;
    /** 代码地图启用时隐藏编辑器原始滚动条（默认隐藏；关闭后恢复 AS_NEEDED） */
    public boolean minimapHideOriginalScrollBar = Boolean.TRUE;
    /** 密文解密 AES 密钥（默认空，用户按需在设置中填写；UTF-8 编码后须为 16/24/32 字节） */
    public String aesKey = "";

    @NotNull
    public static PluginSettingsState getInstance() {
        return Opt.ofNullable(ApplicationManager.getApplication().getService(PluginSettingsState.class)).orElseGet(PluginSettingsState::new);
    }

    @Nullable
    @Override
    public PluginSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull PluginSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}