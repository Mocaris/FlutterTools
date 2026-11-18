package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.*

/** Both old and new locations matter for file moves and renames. */
object VfsEventPaths {
    /** Refreshing a new directory can report its whole subtree in a single creation event. */
    fun affectsPackageDiscovery(event: VFileEvent): Boolean =
        (if (event is VFileContentChangeEvent) WorkspaceLayout.configurationContent(event.file)
         else paths(event).any { it.substringAfterLast('/') in WorkspaceLayout.configurationNames }) ||
        event !is VFileContentChangeEvent && (
            event is VFileCreateEvent && event.isDirectory || event.file?.isDirectory == true &&
            (event is VFileMoveEvent || event is VFileCopyEvent || event is VFileDeleteEvent ||
                event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME))

    fun paths(event: VFileEvent): Set<String> = when (event) {
        is VFileMoveEvent -> setOf(event.oldPath, event.newPath)
        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) setOf(event.oldPath, event.newPath) else emptySet()
        is VFileCopyEvent -> setOf("${event.newParent.path}/${event.newChildName}")
        else -> setOf(event.path)
    }
}
