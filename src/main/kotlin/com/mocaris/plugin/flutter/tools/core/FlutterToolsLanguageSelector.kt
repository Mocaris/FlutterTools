package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.localizedTooltip
import java.awt.Component
import java.awt.Dimension
import java.util.Locale
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList

/** Both settings entrances share the same choices and application preference. */
class FlutterToolsLanguageSelector(owner: Disposable) : ComboBox<PluginLanguage>(PluginLanguage.values()) {
    init {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                                     selected: Boolean, focused: Boolean): Component {
                val label = super.getListCellRendererComponent(list, value, index, selected, focused) as JLabel
                label.text = message("language.${(value as? PluginLanguage ?: PluginLanguage.SYSTEM).name.lowercase(Locale.ROOT)}")
                return label
            }
        }
        selectedItem = service<FlutterToolsLanguageSettings>().state.language
        localizedTooltip("language.followIdeTip")
        preferredSize = Dimension(JBUI.scale(150), preferredSize.height)
        minimumSize = preferredSize
        maximumSize = preferredSize
        ApplicationManager.getApplication().messageBus.connect(owner).subscribe(LanguageListener.TOPIC, LanguageListener {
            selectedItem = service<FlutterToolsLanguageSettings>().state.language
            localizedTooltip("language.followIdeTip")
            repaint()
        })
    }
}
