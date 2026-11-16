package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.*

/** Both old and new locations matter for file moves and renames. */
object VfsEventPaths {
    fun paths(event: VFileEvent): Set<String> = when (event) {
        is VFileMoveEvent -> setOf(event.oldPath, event.newPath)
        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) setOf(event.oldPath, event.newPath) else emptySet()
        is VFileCopyEvent -> setOf("${event.newParent.path}/${event.newChildName}")
        else -> setOf(event.path)
    }
}
