package com.mocaris.plugin.flutter.tools.core

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.messages.Topic
import java.util.Locale

enum class PluginLanguage {
    SYSTEM, CHINESE, ENGLISH;
    fun locale(ide: Locale = DynamicBundle.getLocale()): Locale = when (this) {
        SYSTEM -> if (ide.language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        ENGLISH -> Locale.ENGLISH
    }
}

fun interface LanguageListener {
    fun languageChanged()
    companion object { val TOPIC = Topic.create("FlutterTools language", LanguageListener::class.java) }
}

@Service(Service.Level.APP)
@State(name = "FlutterToolsLanguage", storages = [Storage("FlutterTools.xml")])
class FlutterToolsLanguageSettings : PersistentStateComponent<FlutterToolsLanguageSettings.Options> {
    class Options { @Volatile var language: PluginLanguage = PluginLanguage.SYSTEM }
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }
    fun setLanguage(language: PluginLanguage) {
        if (options.language == language) return
        options.language = language
        val application = ApplicationManager.getApplication()
        val notify = Runnable { application.messageBus.syncPublisher(LanguageListener.TOPIC).languageChanged() }
        if (application.isDispatchThread) notify.run() else application.invokeLater(notify)
    }
}
