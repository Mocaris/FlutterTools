package com.mocaris.plugin.flutter.json

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * json2dart
 * 创建 dart 文件
 */
class Json2DartAction : AnAction() {
    override fun actionPerformed(actionEvent: AnActionEvent) {
        val jsonActionDialog = JsonActionDialog()
        jsonActionDialog.isVisible = true
    }

    private fun createByjson() {}
}