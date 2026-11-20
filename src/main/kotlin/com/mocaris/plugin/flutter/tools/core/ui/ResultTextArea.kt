package com.mocaris.plugin.flutter.tools.core.ui

import com.intellij.ui.components.JBTextArea
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** A readable result area whose selection is preserved when the status is unchanged. */
class ResultTextArea : JBTextArea(6, 24) {
    init {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem().localized("button.copy").apply { addActionListener { this@ResultTextArea.copy() } })
            add(JMenuItem().localized("button.selectAll").apply {
                addActionListener { this@ResultTextArea.requestFocusInWindow(); this@ResultTextArea.selectAll() }
            })
        }
    }
}
