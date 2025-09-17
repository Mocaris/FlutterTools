package com.mocaris.plugin.flutter.tools.sync

import com.intellij.notification.*
import com.intellij.openapi.components.*
import com.intellij.openapi.project.*
import com.intellij.openapi.vfs.*
import com.mocaris.plugin.flutter.tools.model.*
import com.mocaris.plugin.flutter.tools.utils.*
import kotlinx.coroutines.*
import java.io.*

private const val PUBSPEC_FILE_NAME = "pubspec.yaml"

@Service(Service.Level.PROJECT)
class AssetsFileWatcherService(private val project: Project) : VirtualFileListener {

    private val projectBasePath = project.basePath
    private var config: AssetsSyncConfig? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val yamlFile by lazy { File(projectBasePath, PUBSPEC_FILE_NAME) }

    init {
        VirtualFileManager.getInstance().addVirtualFileListener(this, project)
        val pubFile = File(projectBasePath, PUBSPEC_FILE_NAME)
        if (pubFile.exists()) {
            config = AssetsClassGenHelper.parseYaml(pubFile)
        }
    }

    override fun contentsChanged(event: VirtualFileEvent) {
        if (event.file.path == yamlFile.path) {
            handleChange(event)
        }
    }

    override fun fileCreated(event: VirtualFileEvent) = handleChange(event)
    override fun fileDeleted(event: VirtualFileEvent) = handleChange(event)
    override fun fileMoved(event: VirtualFileMoveEvent) = handleChange(event)

    private fun handleChange(event: VirtualFileEvent) {
        scope.launch {
            try {
                val changeFile = event.file
                val filePath = changeFile.path
                if (projectBasePath == null) return@launch
                var needSync = false
                if (this@AssetsFileWatcherService.config == null) {
                    config = AssetsClassGenHelper.parseYaml(File(filePath))
                    needSync = true
                }

                if (filePath == yamlFile.path) {
                    val config = AssetsClassGenHelper.parseYaml(yamlFile)
                    if (this@AssetsFileWatcherService.config == null
                        || this@AssetsFileWatcherService.config != config
                    ) {
                        this@AssetsFileWatcherService.config = config
                        needSync = true
                    }
                }
                val syPaths = config?.sync_path ?: emptyList()
                for (syncPath in syPaths) {
                    val syncFile = File(projectBasePath, syncPath)
                    if (filePath.startsWith(syncFile.path)) {
                        needSync = true
                        break
                    }
                }

                if (needSync) {
                    scope.launch {
                        try {
                            AssetsClassGenHelper.startSyncGen(projectBasePath, config!!)
                        } catch (e: Exception) {
                            Util.notificationSticky(
                                "AssetsSync", "Sync Failed: ${e.message}", NotificationType.ERROR
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Util.notificationSticky(
                    "AssetsSync", "Sync Failed: ${e.message}", NotificationType.ERROR
                )
            }
        }
    }


}