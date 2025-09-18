package com.mocaris.plugin.flutter.tools.sync

import com.intellij.notification.*
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.*
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.messages.MessageBusConnection
import com.mocaris.plugin.flutter.tools.model.*
import com.mocaris.plugin.flutter.tools.utils.*
import kotlinx.coroutines.*
import java.io.*

private const val PUBSPEC_FILE_NAME = "pubspec.yaml"

@Service(Service.Level.PROJECT)
class AssetsFileWatcherService(private val project: Project) : BulkFileListener {

    private val projectBasePath = project.basePath!!
    private val connection: MessageBusConnection = project.messageBus.connect()
    private var config: AssetsSyncConfig? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val yamlFile by lazy { File(projectBasePath, PUBSPEC_FILE_NAME) }

    private val watchDirPath = mutableSetOf<String>()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, this)
        scope.launch {
            if (yamlFile.exists()) {
                handleYaml(isInit = true)
            }
        }
    }

    override fun after(events: List<VFileEvent>) {
        for (event in events) {
            when (event) {
                is VFileContentChangeEvent -> {
                    if (event.isFromSave && event.file.path == yamlFile.path) {
                        handleYaml()
                    }
                }

                is VFileCreateEvent -> checkEventHandle(event)

                is VFileDeleteEvent -> checkEventHandle(event)

                is VFileCopyEvent -> checkEventHandle(event)

                is VFileMoveEvent -> checkEventHandle(event)
            }
        }
    }

    private fun checkEventHandle(event: VFileEvent) {
        scope.launch {
            val filePath = event.file!!.path
            if (null == config || config?.watcher != true) {
                return@launch
            }
            if (watchDirPath.isEmpty()) {
                return@launch
            }
            var needSync = false
            for (syncDir in watchDirPath) {
                if (filePath.startsWith(syncDir)) {
                    needSync = true
                    break
                }
            }
            if (!needSync) {
                return@launch
            }
            handleGenerate(config!!)
        }
    }


    var handleYamlJob: Job? = null
    private fun handleYaml(isInit: Boolean = false) {
        if (handleYamlJob?.isActive == true) {
            return
        }
        handleYamlJob = scope.launch {
            try {
                if (!yamlFile.exists()) {
                    return@launch
                }
                val oldConfig = config?.copy()
                val newConfig = AssetsClassGenHelper.parseYaml(yamlFile).also {
                    config = it
                    watchDirPath.clear()
                    watchDirPath.addAll(it.sync_path.map { t -> File(projectBasePath, t).path })
                }
                val outClassFile = File(projectBasePath, newConfig.out_path)
                if (isInit && outClassFile.exists()) {
                    return@launch
                }
                if (!newConfig.watcher) {
                    return@launch
                }
                if (newConfig == oldConfig && outClassFile.exists()) {
                    return@launch
                }
                val syPaths = config?.sync_path ?: emptyList()
                if (syPaths.isEmpty()) {
                    return@launch
                }
                handleGenerate(newConfig)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var generateJob: Job? = null
    private fun handleGenerate(newConfig: AssetsSyncConfig) {
        if (generateJob?.isActive == true) {
            return
        }
        generateJob = scope.launch {
            try {
                AssetsClassGenHelper.startSyncGen(projectBasePath, newConfig)
            } catch (e: Exception) {
                Util.notificationSticky(
                    "AssetsSync",
                    "Sync Failed: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }


}