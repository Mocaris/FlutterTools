package com.mocaris.plugin.flutter.sync

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.IOException
import java.util.*
import java.util.regex.Pattern

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
        try {
            val basePath = File(e.project!!.basePath).path
            val pubYamlFile = File(basePath, "pubspec.yaml")
            if (!pubYamlFile.exists()) {
                Utils.notificationSticky(
                    "Not a Flutter Project",
                    "The 'pubspec.yaml' does not exist.Please make sure this is a Flutter project",
                    NotificationType.ERROR
                )
                return
            }
            val b = readPubspec(basePath, pubYamlFile)
            if (b) {
                Utils.notificationBalloon("Success", "Assets Sync Tools Run Successful", NotificationType.INFORMATION)
            }
        } catch (ioException: IOException) {
            Utils.notificationSticky("Assets Sync Tools Run Error", ioException.message ?: "", NotificationType.ERROR)
        }
    }


    /*
     * 读取 yaml 内容
     */
    @Throws(IOException::class)
    private fun readPubspec(basePath: String, pubYamlFile: File): Boolean {
        val patternStart = Pattern.compile(syncRegStart)
        val patternEnd = Pattern.compile(syncRegEnd)
        var beforeEnd = false
        val syncNodeBefore = ArrayList<String>()
        val syncLines = HashSet<String>()
        val syncNodeAfter = ArrayList<String>()
        val pubLines = ArrayList(FileUtil.loadLines(pubYamlFile))
        //查询配置 在哪一行
        var tempLine: String? = null
        for (i in pubLines.indices) {
            val line = pubLines[i]
            if (beforeEnd) {
                syncNodeAfter.add(line)
            } else {
                syncNodeBefore.add(line)
            }
            if (!beforeEnd && line.trim { it <= ' ' }.startsWith("assets:")) {
                beforeEnd = true
            }
            var matcher = patternStart.matcher(line)
            if (matcher.find()) {
                //查询同步文件夹开始 提取需要同步的文件夹  # sync-assets-start
                tempLine = matcher.group(1)
            } else {
                matcher = patternEnd.matcher(line)
                if (matcher.find()) {
                    //查询同步文件夹结束 匹配文件夹
                    val syncFolder = matcher.group(1)
                    if (null != tempLine && tempLine == syncFolder) {
                        syncLines.add(tempLine)
                    }
                    tempLine = null
                    syncNodeAfter.clear()
                }
            }
        }
        if (syncLines.isEmpty()) {
            Utils.notificationSticky(
                "Assets Sync Tools Run Error",
                "Please put the configuration items under the ‘assets’ node",
                NotificationType.ERROR
            )
            /*  Messages.showMessageDialog(
                  "Please put the configuration items under the ‘assets’ node",
                  "Configuration not found",
                  Messages.getErrorIcon()
              )*/
            return false
        }
        findWriteSyncFolderFiles(basePath, pubYamlFile, syncNodeBefore, syncNodeAfter, syncLines.sorted())
        return true
    }

    //解析查询到的配置
    @Throws(IOException::class)
    private fun findWriteSyncFolderFiles(
        basePath: String,
        pubYamlFile: File,
        syncNodeBefore: ArrayList<String>,
        syncNodeAfter: ArrayList<String>,
        syncFolders: List<String>
    ) {
        //yaml 配置节点
        val nodeLines = LinkedList<String>()
        //R 文件配置
        val classRField = LinkedList<String>()
        val regex = Regex(mutFolderReg)
        for (syncFolder in syncFolders) {
            val syncFolderFiles = getSyncFolderFiles(basePath, syncFolder).toMutableMap()
            //去重
            syncFolderFiles.forEach { s ->
                if (s.key.contains(regex)) {
                    val parent = s.key.replace(regex, "")
                    syncFolderFiles[parent] = (syncFolderFiles[parent] ?: setOf<String>()).plus(s.value)
                    syncFolderFiles.remove(s.key)
                }
            }
            if (syncFolderFiles.isNotEmpty()) {
                nodeLines.add("    # sync-$syncFolder-start")
                syncFolderFiles.toSortedMap().forEach { (syncFolder: String, files: Set<String>) ->
                    nodeLines.add("    # $syncFolder/*")
                    files.forEach { file: String ->
                        if (!file.startsWith(".")) {
                            classRField.add("$syncFolder/$file")
                            nodeLines.add("    - $syncFolder/$file")
                        }
                    }
                }
                nodeLines.add("    # sync-$syncFolder-end")
            }
        }
        val newYamlLines = LinkedList<String>()
        newYamlLines.addAll(syncNodeBefore)
        newYamlLines.addAll(nodeLines)
        newYamlLines.addAll(syncNodeAfter)
        write2Yaml(pubYamlFile, newYamlLines)
        write2RClass(basePath, classRField)
    }

    @Throws(IOException::class)
    private fun write2Yaml(pubYamlFile: File, newYaml: LinkedList<String>) {
        val parentPath = pubYamlFile.parentFile.path
        val backFile = File(parentPath, "pubspec.yaml.back")
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
        val rFile = File(basePath, "lib${File.separator}r.dart")
        if (FileUtil.createIfDoesntExist(rFile)) {
            val rClass = StringBuilder()
            rClass.append("class R {").append("\n")
            for (s in classR) {
                try {
                    if (s.contains(" ")) {
                        rClass.append("// TODO The file name '$s' contains empty characters,please check it again\n");
                    }
                    val split = s.replace(Regex("\\W"), "-")
                        .replace("_", "-")
                        .replace(" ", "")
                        .split("-").toMutableList()
                    val name = StringBuilder()
                    if (split.size > 1) {
                        split.removeLast();
                    }
                    if (split.isNotEmpty()) {
                        name.append(split.first());
                        split.removeFirst();
                    }
                    for (word in split) {
                        if (word.isNotEmpty()) {
                            if (word.length > 1) {
                                name.append(word.substring(0, 1).toUpperCase())
                                name.append(word.substring(1));
                            } else {
                                name.append(word.toUpperCase())
                            }
                        }
                    }
                    rClass.append("  static const String ").append(name).append(" = ").append("\"")
                        .append(s).append("\";").append("\n")
                } catch (e: Exception) {
                    Utils.notificationSticky("Synchronization error With file", s, NotificationType.ERROR)
                }
            }
            rClass.append("}")
            FileUtil.writeToFile(rFile, rClass.toString())
        }
    }

    //需要同步的 文件夹  文件
    private fun getSyncFolderFiles(basePath: String, folder: String): Map<String, Set<String>> {
        //parent fileName
        val assetsList: MutableMap<String, Set<String>> = HashMap()
        val folderFile = File(basePath, folder)
        val fileList = HashSet<String>()
        if (folderFile.exists()) {
            try {
                folderFile.listFiles()?.forEach { childFile ->
                    if (childFile.isFile) {
                        fileList.add(childFile.name)
                    }
                    if (childFile.isDirectory) {
                        val childFolder = childFile.path.replace(basePath + File.separator, "")
                        assetsList.putAll(getSyncFolderFiles(basePath, childFolder))
                    }
                }
            } catch (e: Exception) {
                Utils.notificationSticky("Synchronization error With file folder ", folder, NotificationType.ERROR)
            }
        }
        assetsList[folder.replace(File.separator, "/")] = fileList.toSortedSet()
        return assetsList
    }

    companion object {
        private const val mutFolderReg = "/[2-9](.\\d)?x"
        private const val syncRegStart = "\\s*#\\s*sync-(\\w+/?\\w+)-start\\s*\\w*"
        private const val syncRegEnd = "\\s*#\\s*sync-(\\w+/?\\w+)-end\\s*\\w*"
    }
}