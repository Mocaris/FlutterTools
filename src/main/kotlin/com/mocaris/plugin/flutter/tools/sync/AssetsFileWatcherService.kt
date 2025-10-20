package com.mocaris.plugin.flutter.tools.sync

import com.intellij.notification.*
import com.intellij.openapi.components.*
import com.intellij.openapi.project.*
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.*
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.util.messages.*
import com.mocaris.plugin.flutter.tools.model.*
import com.mocaris.plugin.flutter.tools.utils.*
import kotlinx.coroutines.*
import java.io.*


@Service(Service.Level.PROJECT)
class AssetsFileWatcherService(private val project: Project) : BulkFileListener {

    private val projectBasePath = project.basePath!!
    private val connection: MessageBusConnection = project.messageBus.connect()
    private var config: AssetsSyncConfig? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val toolsYamlFile by lazy { File(projectBasePath, TOOLS_FILE_NAME) }
    private val pubYamlFile by lazy { File(projectBasePath, PUBSPEC_FILE_NAME) }

    private val watchDirPath = mutableSetOf<String>()

    private val isWatcher get() = config?.watcher == true

    var handleYamlJob: Job? = null
    var generateJob: Job? = null
    var handleFileJob: Job? = null

    private val changeFileList = mutableSetOf<String>()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, this)
        handleYaml(isInit = true)
    }

    override fun after(events: List<VFileEvent>) {
        for (event in events) {
            when (event) {
                is VFileContentChangeEvent -> {
                    if (event.isFromSave) {
                        handleFileSave(event)
                    }
                }

                is VFileCreateEvent -> handleChangeFile(event)

                is VFileDeleteEvent -> handleChangeFile(event)

                is VFileCopyEvent -> handleChangeFile(event)

                is VFileMoveEvent -> handleChangeFile(event)
            }
        }
    }

    private fun handleFileSave(event: VFileEvent) {
        val isConfigPath = event.file?.path?.let { File(it).path }?.let {
            it == toolsYamlFile.path || it == pubYamlFile.path
        }
        if (isConfigPath != true) {
            return
        }
        handleYaml()
    }

    private fun handleChangeFile(event: VFileEvent) {
        if (!isWatcher) {
            return
        }
        event.file?.path?.let {
            changeFileList.add(File(it).path)
        }
        handleWatch()
    }

    private fun handleWatch() {
        handleFileJob?.cancel()
        handleFileJob = scope.launch {
            delay(2000)
            if (!isActive) {
                return@launch
            }
            if (watchDirPath.isEmpty()) {
                return@launch
            }
            if (changeFileList.isEmpty()) {
                return@launch
            }
            try {
                var needSync = false
                for (changeFile in changeFileList) {
                    needSync = watchDirPath.any { changeFile.startsWith(it) }
                    if (needSync) {
                        break
                    }
                }
                if (!needSync) {
                    return@launch
                }
                handleGenerate()
            } finally {
                changeFileList.clear()
            }
        }
    }

    private fun handleYaml(isInit: Boolean = false) {
        handleYamlJob?.cancel()
        handleYamlJob = scope.launch {
            delay(2000)
            try {
                if (!isActive) {
                    return@launch
                }
                if (!toolsYamlFile.exists() && !pubYamlFile.exists()) {
                    return@launch
                }
                val oldConfig = config?.copy()
                val newConfig = AssetsClassGenHelper.parseYaml(toolsYamlFile, pubYamlFile).also {
                    config = it
                    watchDirPath.clear()
                    watchDirPath.addAll(it.syncPath.map { t -> File(projectBasePath, t).path })
                }
                val outClassFile = File(projectBasePath, newConfig.outPath)
                if (isInit && outClassFile.exists()) {
                    return@launch
                }
                if (!newConfig.watcher) {
                    return@launch
                }
                if (newConfig == oldConfig && outClassFile.exists()) {
                    return@launch
                }
                val syPaths = config?.syncPath ?: emptyList()
                if (syPaths.isEmpty()) {
                    return@launch
                }

                handleGenerate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleGenerate() {
        if (generateJob?.isActive == true) {
            return
        }
        generateJob = scope.launch {
            try {
                AssetsClassGenHelper.startSyncGen(projectBasePath, config!!)
                Util.notificationSticky(
                    "Flutter Tools",
                    "Assets Sync Tools Run Successful",
                    NotificationType.INFORMATION
                )
            } catch (e: Exception) {
                Util.notificationSticky(
                    "Flutter Tools",
                    "Sync Failed: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }
    }


}