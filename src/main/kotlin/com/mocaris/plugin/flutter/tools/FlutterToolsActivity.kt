package com.mocaris.plugin.flutter.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mocaris.plugin.flutter.tools.assets.AssetsSyncService
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService

class FlutterToolsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(AssetsSyncService::class.java)
        project.getService(PackageVersionService::class.java)
    }
}
