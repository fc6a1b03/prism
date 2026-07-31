package com.acme.prism.ui.statusbar;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 状态栏 JSON 路径部件工厂。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
public class JsonPathStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return "Prism.JsonPath";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "JSON Path";
    }

    @Override
    public boolean isAvailable(@NotNull final Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull final Project project) {
        return new JsonPathStatusBarWidget();
    }

    @Override
    public void disposeWidget(@NotNull final StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull final StatusBar statusBar) {
        return true;
    }
}
