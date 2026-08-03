package com.acme.prism.ui.editor;

import com.acme.prism.core.editor.JsonFoldingCalculator;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * JSON 折叠支持：将 {@link JsonFoldingCalculator} 计算的折叠区域应用到编辑器。
 *
 * @author 拒绝者
 * @date 2026-07-31
 */
public final class JsonFoldingSupport {

    private JsonFoldingSupport() {
    }

    /**
     * 更新编辑器折叠区域：清除旧 JSON 折叠区，按当前文本重建跨行折叠。
     *
     * @param editor 目标编辑器
     * @param text   当前文档文本
     */
    public static void updateFolding(@NotNull final Editor editor, @NotNull final String text) {
        final List<JsonFoldingCalculator.FoldRegion> regions = JsonFoldingCalculator.calculate(text);
        final FoldingModel model = editor.getFoldingModel();
        model.runBatchFoldingOperation(() -> {
            // 清除上一次添加的折叠区域（按 placeholder 识别）
            for (final FoldRegion region : model.getAllFoldRegions()) {
                if (isJsonFoldRegion(region)) {
                    model.removeFoldRegion(region);
                }
            }
            // 重建折叠区域
            for (final JsonFoldingCalculator.FoldRegion region : regions) {
                model.addFoldRegion(region.startOffset(), region.endOffset(), region.placeholder());
            }
        });
    }

    private static boolean isJsonFoldRegion(final FoldRegion region) {
        final String placeholder = region.getPlaceholderText();
        return "{...}".equals(placeholder) || "[...]".equals(placeholder);
    }
}
