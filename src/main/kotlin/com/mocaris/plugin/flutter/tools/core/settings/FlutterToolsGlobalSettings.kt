package com.mocaris.plugin.flutter.tools.core.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceCompletionService
import com.mocaris.plugin.flutter.tools.assets.preview.AssetPreviewService
import com.mocaris.plugin.flutter.tools.dependencies.versions.PackageVersionService

@Service(Service.Level.APP)
@State(name = "FlutterToolsGlobalSettings", storages = [Storage("FlutterToolsGlobalSettings.xml")])
class FlutterToolsGlobalSettings : PersistentStateComponent<FlutterToolsSettings.Options> {
    private var options = FlutterToolsSettings.Options()
    override fun getState() = options
    override fun loadState(state: FlutterToolsSettings.Options) {
        options = state.normalized().copy(selectedPackagePath = "", useGlobalSettings = null)
    }

    fun apply(state: FlutterToolsSettings.Options) {
        loadState(state)
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed &&
            it.service<FlutterToolsSettings>().state.useGlobalSettings == true
        }.forEach(FlutterToolsPreferences::changed)
    }
}

fun interface PreferencesListener {
    fun preferencesChanged()
    companion object { val TOPIC = Topic.create("FlutterTools preferences", PreferencesListener::class.java) }
}

object FlutterToolsPreferences {
    /** Refresh existing services; unopened feature pages pick up effective preferences on creation. */
    fun changed(project: Project) {
        if (project.isDisposed) return
        project.getServiceIfCreated(ResourceCompletionService::class.java)?.preferencesChanged()
        project.getServiceIfCreated(AssetPreviewService::class.java)
            ?.preferencesChanged(project.service<FlutterToolsSettings>().effective().assetImagePreviews)
        project.getServiceIfCreated(PackageVersionService::class.java)?.preferencesChanged()
        project.messageBus.syncPublisher(PreferencesListener.TOPIC).preferencesChanged()
    }
}
