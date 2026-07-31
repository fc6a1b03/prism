package com.acme.prism.ui.error;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * JSON 语法错误标注器。
 *
 * @author 拒绝者
 * @date 2026-07-29
 */
public class JsonSyntaxErrorAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull final PsiElement element, @NotNull final AnnotationHolder holder) {
        final PsiFile file = element.getContainingFile();
        if (file == null || element != file) {
            return;
        }
        final Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null || !"json".equalsIgnoreCase(file.getFileType().getDefaultExtension())) {
            return;
        }
        final JsonErrorParser.ErrorPosition error = JsonErrorParser.parseError(doc.getText());
        if (error == null) {
            return;
        }
        final int offset = logicalPositionToOffset(doc, error.line(), error.column());
        final TextRange range = calculateErrorRange(doc.getText(), offset);
        holder.newAnnotation(HighlightSeverity.ERROR, error.message())
                .range(range)
                .create();
    }

    private static int logicalPositionToOffset(final Document document, final int line, final int column) {
        final int lineIndex = Math.max(0, Math.min(line - 1, document.getLineCount() - 1));
        return Math.min(document.getLineStartOffset(lineIndex) + Math.max(0, column - 1), document.getTextLength());
    }

    private static TextRange calculateErrorRange(final String text, final int offset) {
        if (offset < 0 || offset >= text.length()) {
            return TextRange.EMPTY_RANGE;
        }
        int start = offset;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        int end = offset + 2;
        if (end < text.length()) {
            while (end < text.length() && text.charAt(end) != '\n') {
                end++;
            }
        }
        return new TextRange(start, Math.min(end, text.length()));
    }
}
