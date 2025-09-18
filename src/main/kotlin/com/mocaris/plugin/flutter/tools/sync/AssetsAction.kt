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
                val pubYamlFile = File(projectPath, "pubspec.yaml")
                if (!pubYamlFile.exists()) {
                    throw IllegalStateException("The 'pubspec.yaml' does not exist. Please make sure this is a Flutter project")
                }
                val syncConfig = AssetsClassGenHelper.parseYaml(pubYamlFile)
                AssetsClassGenHelper.startSyncGen(projectPath, syncConfig)
                withContext(Dispatchers.Main) {
                    Util.showOkDialog(
                        "AssetsSync Success",
                        "Assets Sync Tools Run Successful",
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Util.showErrorDialog(
                        "FlutterTools AssetsSync Generate Error",
                        e.message ?: "",
                    )
                }
            }
        }
    }

}



