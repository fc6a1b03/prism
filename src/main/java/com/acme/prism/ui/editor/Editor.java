package com.acme.prism.ui.editor;

import com.acme.prism.core.editor.FileDropHandler;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.ui.EditorTextField;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.dnd.DropTarget;
import java.util.Objects;

/**
 * 编辑器
 * @author 拒绝者
 * @date 2025-01-26
 */
public interface Editor {
    /**
     * 创建编辑器<br/>
     * 单页签编辑器使用
     * @param project 项目
     * @return {@link EditorTextField }
     */
    default EditorTextField create(final Project project) {
        return new EditorTextField();
    }

    /**
     * 获取编辑器文本字段
     * @param languageType 语言类型
     * @param project      项目
     * @param document     文件
     * @return {@link EditorTextField }
     */
    default EditorTextField getEditorTextField(final LanguageFileType languageType, final Project project, final Document document) {
        return new EditorTextField(document, project, languageType, Boolean.FALSE, Boolean.FALSE) {
            @Override
            protected @NotNull EditorEx createEditor() {
                return Editor.configureEditor(project, super.createEditor(), languageType);
            }
        };
    }

    /**
     * 绑定拖放监听
     * @param editor 编辑器对象
     */
    static void bindDragAndDropListening(final EditorTextField editor) {
        editor.setDropTarget(new DropTarget(editor, new FileDropHandler(editor)));
    }

    /**
     * 重新格式化编辑器内容
     * @param editor 编辑器对象
     */
    static void reformat(final EditorTextField editor) {
        final Project project = editor.getProject();
        if (Objects.isNull(project)) return;
        // 确保在EDT线程执行
        ApplicationManager.getApplication().invokeLater(() -> {
            final Document document = editor.getDocument();
            final PsiDocumentManager psiMgr = PsiDocumentManager.getInstance(project);
            // 异步提交文档变更
            psiMgr.performLaterWhenAllCommitted(() ->
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        // 同步提交文档
                        psiMgr.commitDocument(document);
                        final PsiFile psiFile = psiMgr.getPsiFile(document);
                        if (Objects.nonNull(psiFile)) {
                            // 执行格式化
                            CodeStyleManager.getInstance(project).reformat(psiFile);
                        }
                    })
            );
        }, ModalityState.defaultModalityState());
    }

    /**
     * 通用编辑器设置
     * @param project 项目
     * @param editor  编辑
     */
    static EditorEx configureEditor(final Project project, final EditorEx editor, final LanguageFileType languageType) {
        // 基础编辑器配置
        editor.setCaretVisible(Boolean.TRUE);
        editor.setVerticalScrollbarVisible(Boolean.TRUE);
        editor.setHorizontalScrollbarVisible(Boolean.TRUE);
        editor.setBorder(BorderFactory.createEmptyBorder());
        // 折叠功能配置
        editor.getFoldingModel().setFoldingEnabled(Boolean.TRUE);
        // 设置菜单和交互
        editor.setContextMenuGroupId(IdeActions.GROUP_EDITOR_POPUP);
        // 编辑器设置统一配置
        final EditorSettings settings = editor.getSettings();
        final EditorSettingsExternalizable externalSettings = EditorSettingsExternalizable.getInstance();
        settings.setUseSoftWraps(Boolean.TRUE);
        settings.setVirtualSpace(Boolean.FALSE);
        settings.setLineNumbersShown(Boolean.TRUE);
        settings.setShowIntentionBulb(Boolean.TRUE);
        settings.setIndentGuidesShown(Boolean.TRUE);
        settings.setFoldingOutlineShown(Boolean.TRUE);
        settings.setLineMarkerAreaShown(Boolean.TRUE);
        settings.setAutoCodeFoldingEnabled(Boolean.TRUE);
        settings.setAllowSingleLogicalLineFolding(Boolean.TRUE);
        settings.setBlockCursor(externalSettings.isBlockCursor());
        settings.setCaretBlinkPeriod(externalSettings.getBlinkPeriod());
        // 高亮和配色方案
        editor.setColorsScheme(EditorColorsManager.getInstance().getGlobalScheme());
        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, languageType));
        return editor;
    }
}