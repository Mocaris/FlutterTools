package com.mocaris.plugin.flutter.tools.dependencies.ui

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.ide.ui.AntialiasingType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.impl.FontFallbackIterator
import com.intellij.openapi.editor.impl.FontInfo
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import kotlin.math.ceil

/** Inline text uses the same font, baseline and line box as the surrounding editor text. */
@Suppress("UnstableApiUsage")
class EditorTextPresentation(private val editor: Editor, private val text: String) : BasePresentation() {
    override val width: Int get() = ceil(layout(FontInfo.getFontRenderContext(editor.contentComponent)).advance.toDouble()).toInt()
    override val height: Int get() = editor.lineHeight

    private fun layout(context: FontRenderContext): TextLayout {
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val attributed = AttributedString(text)
        val fonts = FontFallbackIterator().setPreferredFonts(editor.colorsScheme.fontPreferences)
            .setFontStyle(font.style).setFontRenderContext(context)
        fonts.start(text, 0, text.length)
        while (!fonts.atEnd()) {
            // Keep the editor's actual size, including fractional zoom, for fallback glyphs too.
            attributed.addAttribute(TextAttribute.FONT, fonts.font.deriveFont(font.size2D), fonts.start, fonts.end)
            fonts.advance()
        }
        return TextLayout(attributed.iterator, context)
    }

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.color = attributes.foregroundColor ?: editor.colorsScheme.defaultForeground
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, AntialiasingType.getKeyForCurrentScope(true))
            layout(graphics.fontRenderContext).draw(graphics, 0f, editor.ascent.toFloat())
        } finally {
            graphics.dispose()
        }
    }

    override fun toString(): String = text
}
