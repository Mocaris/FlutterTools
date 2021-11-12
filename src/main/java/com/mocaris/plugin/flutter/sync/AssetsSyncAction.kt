package com.mocaris.plugin.flutter.sync

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.mocaris.plugin.flutter.sync.models.SyncLines
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * 同步 assets 到 pubspec 创建 lib/r.dart 文件
 * example:
 * flutter:
 * assets:
 * # sync-assets-start
 * # sync-assets-end
 * # sync-assets/images-start
 * # sync-assets/images-end
 * # sync-images-start
 * # sync-images-end
 */
class AssetsSyncAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val basePath = e.project!!.basePath
        val pubYamlFile = File(basePath, "pubspec.yaml")
        if (!pubYamlFile.exists()) {
            Messages.showMessageDialog(
                "The 'pubspec.yaml' does not exist.Please make sure this is a Flutter project",
                "Not a Flutter Project",
                Messages.getWarningIcon()
            )
            return
        }
        try {
            readPubspec(basePath!!, pubYamlFile)
            Messages.showMessageDialog(
                "Success",
                "Assets Sync Tools Run Successful",
                Messages.getInformationIcon()
            )
        } catch (ioException: IOException) {
            Messages.showMessageDialog(
                ioException.message,
                "Assets Sync Tools Run Failed",
                Messages.getErrorIcon()
            )
        }
    }

    /*
     * 读取 yaml 内容
     */
    @Throws(IOException::class)
    private fun readPubspec(basePath: String, pubYamlFile: File) {
        val patternStart = Pattern.compile(syncRegStart)
        val patternEnd = Pattern.compile(syncRegEnd)
        var beforeEnd = false
        val syncNodeBefore = ArrayList<String>()
        val syncLines = ArrayList<SyncLines>()
        val syncNodeAfter = ArrayList<String>()
        val pubLines = ArrayList(FileUtil.loadLines(pubYamlFile))
        //查询配置 在哪一行
        var tempLine: SyncLines? = null
        for (i in pubLines.indices) {
            val line = pubLines[i]
            if (beforeEnd) {
                syncNodeAfter.add(line)
            }
            if (!beforeEnd) {
                syncNodeBefore.add(line)
            }
            if (!beforeEnd && line.trim { it <= ' ' }.startsWith("assets:")) {
                beforeEnd = true
            }
            var matcher = patternStart.matcher(line)
            if (matcher.find()) {
                //查询同步文件夹开始 提取需要同步的文件夹  # sync-assets-start
                val syncFolder = matcher.group(1)
                tempLine = SyncLines()
                tempLine.lineStart = i + 1
                tempLine.syncFolder = syncFolder
            } else {
                matcher = patternEnd.matcher(line)
                if (matcher.find()) {
                    //查询同步文件夹结束 匹配文件夹
                    val syncFolder = matcher.group(1)
                    if (null != tempLine && tempLine.syncFolder == syncFolder) {
                        tempLine.lineEnd = i + 1
                        syncLines.add(tempLine)
                    }
                    tempLine = null
                    syncNodeAfter.clear()
                }
            }
        }
        findWriteSyncFolderFiles(basePath, pubYamlFile, syncNodeBefore, syncNodeAfter, syncLines)
    }

    //解析查询到的配置
    @Throws(IOException::class)
    private fun findWriteSyncFolderFiles(
        basePath: String,
        pubYamlFile: File,
        syncNodeBefore: ArrayList<String>,
        syncNodeAfter: ArrayList<String>,
        syncLines: ArrayList<SyncLines>
    ) {
        //yaml 配置节点
        val nodeLines = ArrayList<String>()
        //R 文件配置
        val classRField = ArrayList<String>()
        for (lines in syncLines) {
            val syncFolderFiles = getSyncFolderFiles(basePath, lines.syncFolder)
            if (syncFolderFiles.isNotEmpty()) {
                nodeLines.add("    # sync-" + lines.syncFolder + "-start")
                syncFolderFiles.forEach { (syncFolder: String, files: List<String>) ->
                    nodeLines.add("    # $syncFolder/*")
                    files.forEach { file: String ->
                        if (!file.startsWith(".")) {
                            classRField.add("$syncFolder/$file")
                            nodeLines.add("    - $syncFolder/$file")
                        }
                    }
                }
                nodeLines.add("    # sync-" + lines.syncFolder + "-end")
            }
        }
        val newYamlLines = ArrayList<String>()
        newYamlLines.addAll(syncNodeBefore)
        newYamlLines.addAll(nodeLines)
        newYamlLines.addAll(syncNodeAfter)
        write2Yaml(pubYamlFile, newYamlLines)
        write2RClass(basePath, classRField)
    }

    @Throws(IOException::class)
    private fun write2Yaml(pubYamlFile: File, newYaml: ArrayList<String>) {
        val filSuffix = SimpleDateFormat("YYYY-MM-dd HH:mm:ss").format(Date())
        val parentPath = pubYamlFile.parentFile.path
        val backFile = File(parentPath, "pubspec.yaml.back-$filSuffix")
        backFile.deleteOnExit()
        backFile.createNewFile()
        FileUtil.copy(pubYamlFile, backFile)
        val sb = StringBuilder()
        for (line in newYaml) {
            sb.append(line).append("\n")
        }
        FileUtil.writeToFile(pubYamlFile, sb.toString())
    }

    @Throws(IOException::class)
    private fun write2RClass(basePath: String?, classR: List<String>) {
        val rFile = File(basePath, "lib/r.dart")
        if (FileUtil.exists(rFile.path)) {
            FileUtil.delete(rFile)
        }
        if (FileUtil.createIfDoesntExist(rFile)) {
            val rClass = StringBuilder()
            rClass.append("class R {").append("\n")
            for (s in classR) {
                val substring = s.substring(0, s.indexOf("."))
                val split = substring.replace("_", "/").split("/").toTypedArray()
                val name = StringBuilder()
                for (i in split.indices) {
                    val word = split[i]
                    if (i == 0) {
                        name.append(word)
                    } else {
                        name.append(word.substring(0, 1).toUpperCase())
                            .append(word.substring(1))
                    }
                }
                rClass.append("  static final String ").append(name).append(" = ").append("\"")
                    .append(s).append("\";").append("\n")
            }
            rClass.append("}")
            FileUtil.writeToFile(rFile, rClass.toString())
        }
    }

    //需要同步的 文件夹  文件
    private fun getSyncFolderFiles(basePath: String, folder: String): Map<String, List<String>> {
        //parent fileName
        val assetsList: MutableMap<String, List<String>> = HashMap()
        val folderFile = File(basePath, folder)
        val list = ArrayList<String>()
        if (folderFile.exists()) {
            folderFile.listFiles()?.forEach { childFile ->
                if (childFile.isFile) {
                    list.add(childFile.name)
                }
                if (childFile.isDirectory) {
                    val childFolder = childFile.path.replace(basePath + File.separator, "")
                    assetsList.putAll(getSyncFolderFiles(basePath, childFolder))
                }
            }
        }
        assetsList[folder.replace("\\", "/")] = list
        return assetsList
    }

    companion object {
        private const val syncRegStart = "\\s*#\\s*sync-(\\w+/?\\w+)-start"
        private const val syncRegEnd = "\\s*#\\s*sync-(\\w+/?\\w+)-end"
    }
}