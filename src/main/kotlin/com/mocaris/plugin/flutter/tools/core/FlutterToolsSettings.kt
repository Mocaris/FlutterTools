package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.components.*

@Service(Service.Level.PROJECT)
@State(name = "FlutterToolsSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlutterToolsSettings : PersistentStateComponent<FlutterToolsSettings.Options> {
    data class Options(var automaticChecks: Boolean = true, var cacheMinutes: Int = 30, var includePrerelease: Boolean = false, var selectedPackagePath: String = "")
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }
}
