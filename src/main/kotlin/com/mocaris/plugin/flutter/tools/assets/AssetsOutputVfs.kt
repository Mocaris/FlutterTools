package com.mocaris.plugin.flutter.tools.assets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

/** Complete cleanup before refreshing loaded directory listings or reporting generation success. */
internal object AssetsOutputVfs {
    fun finish(prepared: Map<Path, Path>, outputs: Set<Path>) {
        try { AssetsOutputWriter.cleanup(prepared) }
        finally {
            val application = ApplicationManager.getApplication()
            val refresh = Runnable {
                val fileSystem = LocalFileSystem.getInstance()
                val paths = outputs + prepared.values
                val directories = paths.mapNotNull { it.parent }.distinct().mapNotNull {
                    fileSystem.refreshAndFindFileByNioFile(it)
                }
                val files = paths.mapNotNull { fileSystem.findFileByNioFile(it) }
                // Refresh directory children for creations/deletions and files for changed contents.
                // Keep this nonrecursive: only generated outputs and their immediate parents changed.
                VfsUtil.markDirtyAndRefresh(false, false, true, *(directories + files).distinct().toTypedArray())
            }
            if (application.isDispatchThread) refresh.run() else application.invokeAndWait(refresh)
        }
    }
}
