package com.mocaris.plugin.flutter.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mocaris.plugin.flutter.tools.sync.AssetsFileWatcherService

class FlutterToolsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(AssetsFileWatcherService::class.java)
    }
}