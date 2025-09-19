package com.mocaris.plugin.flutter.tools.sync

import com.intellij.openapi.actionSystem.*
import com.mocaris.plugin.flutter.tools.utils.*
import kotlinx.coroutines.*
import java.io.*


class AssetsAction : AnAction() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun actionPerformed(e: AnActionEvent) {
        scope.launch {
            try {
                val project = e.project
                val projectPath = project?.basePath ?: throw IOException("Not a Flutter Project")
                val toolsYamlFile = File(projectPath, TOOLS_FILE_NAME)
                val pubYamlFile = File(projectPath, PUBSPEC_FILE_NAME)
                if (!toolsYamlFile.exists() && !pubYamlFile.exists()) {
                    throw IllegalStateException("The $TOOLS_FILE_NAME and $PUBSPEC_FILE_NAME does not exist. Please make sure this is a Flutter project")
                }
                val syncConfig = AssetsClassGenHelper.parseYaml(toolsYamlFile, pubYamlFile)
                AssetsClassGenHelper.startSyncGen(projectPath, syncConfig)
                withContext(Dispatchers.Main) {
                    Util.showOkDialog(
                        "Flutter Tools",
                        "Assets Sync Tools Run Successful",
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Util.showErrorDialog(
                        "Flutter Tools",
                        "Assets Sync Tools Run Error\n${e.message ?: ""}",
                    )
                }
            }
        }
    }

}



