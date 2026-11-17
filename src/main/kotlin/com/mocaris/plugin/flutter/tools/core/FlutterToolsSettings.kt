package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.components.*

@Service(Service.Level.PROJECT)
@State(name = "FlutterToolsSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlutterToolsSettings : PersistentStateComponent<FlutterToolsSettings.Options> {
    data class Options(
        var automaticChecks: Boolean = true,
        var cacheMinutes: Int = 720,
        var includePrerelease: Boolean = false,
        var selectedPackagePath: String = "",
        var assetImagePreviews: Boolean = true,
        var resourceAutoCompletion: Boolean = true,
        var resourceCompletionDelayMillis: Int = 100,
        var dependencyVersionHints: Boolean = true,
        var dependencyCheckDelayMillis: Int = 1000,
        // Null identifies workspaces saved before global defaults were introduced.
        var useGlobalSettings: Boolean? = null,
    ) {
        fun normalized() = copy(
            cacheMinutes = cacheMinutes.coerceIn(1, 1440),
            resourceCompletionDelayMillis = resourceCompletionDelayMillis.coerceIn(0, 5000),
            dependencyCheckDelayMillis = dependencyCheckDelayMillis.coerceIn(0, 10000),
        )
    }
    private var options = Options(useGlobalSettings = true)
    override fun getState() = options
    override fun loadState(state: Options) {
        options = state.normalized().let {
            it.copy(useGlobalSettings = it.useGlobalSettings ?:
                (it.copy(selectedPackagePath = "", useGlobalSettings = null) == Options()))
        }
    }

    fun effective(): Options = if (options.useGlobalSettings == true)
        effective(service<FlutterToolsGlobalSettings>().state) else options.normalized()

    fun effective(global: Options): Options =
        if (options.useGlobalSettings == true)
            global.copy(selectedPackagePath = options.selectedPackagePath, useGlobalSettings = true).normalized()
        else options.normalized()
}
