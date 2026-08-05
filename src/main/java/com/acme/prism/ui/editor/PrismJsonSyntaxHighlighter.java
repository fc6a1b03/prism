package com.acme.prism.ui.editor;

import com.intellij.json.JsonElementTypes;
import com.intellij.json.JsonLanguage;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.JBColor;

/**
 * Prism JSON 语法高亮器：委托 IntelliJ JSON 高亮，仅将 {@code null} 关键字
 * 单独着色为中性灰，与 {@code true}/{@code false} 的关键字色区分。
 *
 * <p>IntelliJ 词法层 {@code TRUE}/{@code FALSE}/{@code NULL} 是独立 token，
 * 但默认高亮统一映射到 JSON_KEYWORD（橙色），本高亮器仅覆盖 NULL 分支。</p>
 *
 * @author 拒绝者
 * @date 2026-08-05
 */
public final class PrismJsonSyntaxHighlighter extends SyntaxHighlighterBase {

    /**
     * null 关键字专用颜色键（中性灰，亮/暗主题一致）
     */
    private static final TextAttributesKey JSON_NULL = TextAttributesKey.createTextAttributesKey(
            "PRISM.JSON_NULL", createNullAttributes()
    );

    /**
     * 委托的 IntelliJ JSON 高亮器
     */
    private final SyntaxHighlighter delegate;

    /**
     * 构造高亮器。
     *
     * @param project 项目（用于获取 JSON 语言默认高亮器）
     */
    public PrismJsonSyntaxHighlighter(final Project project) {
        this.delegate = SyntaxHighlighterFactory.getSyntaxHighlighter(JsonLanguage.INSTANCE, project, null);
    }

    /**
     * 创建 null 关键字的灰色文本属性。
     *
     * @return 文本属性
     */
    private static TextAttributes createNullAttributes() {
        final TextAttributes attributes = new TextAttributes();
        attributes.setForegroundColor(new JBColor(0x808080, 0x808080));
        return attributes;
    }

    @Override
    public Lexer getHighlightingLexer() {
        return this.delegate.getHighlightingLexer();
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(final IElementType tokenType) {
        // null 关键字单独灰色；true/false 及其他 token 委托默认高亮（关键字橙）
        if (tokenType == JsonElementTypes.NULL) {
            return SyntaxHighlighterBase.pack(JSON_NULL);
        }
        return this.delegate.getTokenHighlights(tokenType);
    }
}
