package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile

object ProjectDirectorySelection {
    fun selected(event: AnActionEvent): VirtualFile? {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files != null && files.size != 1) return null
        val file = files?.singleOrNull() ?: event.getData(CommonDataKeys.VIRTUAL_FILE)
        return file?.takeIf { it.isValid && it.isDirectory && it.isInLocalFileSystem }
    }
}
