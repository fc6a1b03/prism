package com.acme.prism.ui.editor;

import com.acme.prism.common.enums.SupportedLanguages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFileFactory;
import com.intellij.ui.EditorTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 编辑器工厂。
 *
 * <p>视口滚动偏移（{@code initialScrollOffset}）为可变字段，由 ScrollingListener 实时记录，
 * 覆盖滑动条/代码地图/滚轮等所有滚动方式。编辑器组件创建与页签切换重建时，
 * 滚动视口到目标位置：无记录时置顶（0），有记录时还原之前视口位置。
 * 滚动需布局完成后生效，故立即执行 + 延迟兜底（轻微动画，平台限制）。
 *
 * @author 拒绝者
 * @date 2025-04-22
 */
public final class CustomizeEditorFactory {

    /** 忽略创建初期 IntelliJ 初始化滚动的时间窗（毫秒），避免把自动滚到底部误记为用户位置 */
    private static final int INIT_SCROLL_IGNORE_MS = 500;

    private final SupportedLanguages language;
    private final String fileName;
    /** 最近一次视口滚动偏移（ScrollingListener 实时记录），null 表示置顶 */
    private volatile Integer initialScrollOffset;

    /**
     * 编辑器工厂
     *
     * @param language 语言
     * @param fileName 文件名
     */
    public CustomizeEditorFactory(final SupportedLanguages language, final String fileName) {
        this(language, fileName, null);
    }

    /**
     * 编辑器工厂（携带保存的视口滚动偏移）。
     *
     * @param language        语言
     * @param fileName        文件名
     * @param initialScrollOffset 初始视口滚动偏移（项目重开还原），null 表示置顶
     */
    public CustomizeEditorFactory(final SupportedLanguages language, final String fileName,
                                  @Nullable final Integer initialScrollOffset) {
        this.language = language;
        this.fileName = fileName;
        this.initialScrollOffset = initialScrollOffset;
    }

    /**
     * 读取当前记录的视口滚动偏移（编辑器释放后由 MainToolWindowFactory 保存时兜底读取）。
     *
     * @return 最近一次视口滚动偏移，null 表示置顶
     */
    @Nullable
    public Integer getCurrentScrollOffset() {
        return this.initialScrollOffset;
    }

    /**
     * 创建编辑器。
     *
     * @param project 项目
     * @return 编辑器
     */
    public EditorTextField create(final Project project) {
        return new EditorTextField(
                PsiDocumentManager.getInstance(project).getDocument(
                        PsiFileFactory.getInstance(project).createFileFromText(fileName, language.getFileType(), "")
                ),
                project, language.getFileType(), Boolean.FALSE, Boolean.FALSE
        ) {
            @Override
            protected @NotNull EditorEx createEditor() {
                final EditorEx editor = Editor.configureEditor(project, super.createEditor(), language.getFileType());
                // JSON 语言定制高亮：null 单独灰色，与 true/false 关键字色区分（仅影响工具窗口内编辑器）
                if (language == SupportedLanguages.JSON) {
                    editor.setHighlighter(EditorHighlighterFactory.getInstance()
                            .createEditorHighlighter(new PrismJsonSyntaxHighlighter(project), editor.getColorsScheme()));
                }
                // 光标置于文档开头（默认置顶）
                editor.getCaretModel().moveToOffset(0);
                // 实时记录视口滚动偏移（编辑器创建/页签重建时挂载）。
                // 忽略创建初期 IntelliJ 的初始化滚动（会自动滚到文档末尾，会被误记为用户位置）
                final long createTime = System.currentTimeMillis();
                final ScrollingModel scrollingModel = editor.getScrollingModel();
                scrollingModel.addVisibleAreaListener(new VisibleAreaListener() {
                    @Override
                    public void visibleAreaChanged(@NotNull final VisibleAreaEvent e) {
                        if (System.currentTimeMillis() - createTime > INIT_SCROLL_IGNORE_MS) {
                            CustomizeEditorFactory.this.initialScrollOffset = scrollingModel.getVerticalScrollOffset();
                        }
                    }
                });
                // 创建后立即执行：初始化置顶 / 还原（延迟兜底确保布局完成后生效）
                scrollToPosition(editor);
                return editor;
            }
        };
    }

    /**
     * 滚动视口到目标位置（记录的滚动偏移或 0 置顶）。
     *
     * <p>EditorTextField 的视口初始化在文档末尾且滚动操作需布局完成才生效，
     * 因此立即执行 + 延迟兜底（延迟必然成功，有轻微动画，平台限制）。
     *
     * @param editor 编辑器（IntelliJ Editor，与项目 Editor 接口同名冲突，此处全限定）
     */
    private void scrollToPosition(final com.intellij.openapi.editor.Editor editor) {
        try {
            final int target = Objects.requireNonNullElse(this.initialScrollOffset, 0);
            editor.getScrollingModel().scrollVertically(target);
            // 布局完成后延迟兜底，确保滚动生效
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    editor.getScrollingModel().scrollVertically(target);
                } catch (final Exception ignored) {
                }
            });
        } catch (final Exception ignored) {
            // 编辑器布局未就绪时忽略，延迟兜底不依赖首次执行成功
        }
    }
}
