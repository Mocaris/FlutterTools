package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.application.ApplicationManager
import java.awt.Component
import java.awt.Container
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

object FlutterToolsBundle {
    private const val TEXT = "fluttertools.text"
    private const val TOOLTIP = "fluttertools.tooltip"
    private val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
    val locale: Locale get() = (ApplicationManager.getApplication()?.getService(FlutterToolsLanguageSettings::class.java)
        ?.state?.language ?: PluginLanguage.SYSTEM).locale()

    fun format(key: String, locale: Locale, vararg args: Any?): String {
        val bundle = ResourceBundle.getBundle("messages.FlutterToolsBundle", locale, javaClass.classLoader, control)
        return MessageFormat(bundle.getString(key), locale).format(args.map {
            when (it) {
                is LocalizedText -> it.render(locale)
                is LocalizedException -> it.text.render(locale)
                is Throwable -> it.localizedMessage ?: format("error.unknown", locale)
                else -> it
            }
        }.toTypedArray())
    }
    fun message(key: String, vararg args: Any?) = format(key, locale, *args)
    fun text(key: String, vararg args: Any?) = LocalizedText(key, args.toList())
    fun <T : JComponent> T.localized(key: String, vararg args: Any?): T = localized(text(key, *args))
    fun <T : JComponent> T.localized(text: LocalizedText): T = apply {
        putClientProperty(TEXT, text)
        applyText(this)
    }
    fun <T : JComponent> T.localizedTooltip(key: String, vararg args: Any?): T = apply {
        putClientProperty(TOOLTIP, text(key, *args))
        toolTipText = message(key, *args)
    }
    private fun applyText(component: JComponent) {
        (component.getClientProperty(TEXT) as? LocalizedText)?.let {
            when (component) {
                is javax.swing.text.JTextComponent -> {
                    val value = it.toString()
                    if (component.text != value) {
                        component.text = value
                        component.caretPosition = 0
                    }
                }
                is JLabel -> component.text = it.toString()
                is AbstractButton -> component.text = it.toString()
            }
        }
        (component.getClientProperty(TOOLTIP) as? LocalizedText)?.let { component.toolTipText = it.toString() }
    }
    fun update(component: Component) {
        if (component is JComponent) applyText(component)
        if (component is Container) component.components.forEach(::update)
    }
}

data class LocalizedText(val key: String, val args: List<Any?> = emptyList()) {
    fun render(locale: Locale) = FlutterToolsBundle.format(key, locale, *args.toTypedArray())
    override fun toString() = render(FlutterToolsBundle.locale)
}

class LocalizedException(key: String, vararg args: Any?) : IllegalArgumentException() {
    val text = FlutterToolsBundle.text(key, *args)
    override val message get() = text.toString()
    override fun getLocalizedMessage() = message
}

@OptIn(ExperimentalContracts::class)
fun requireLocalized(condition: Boolean, key: String, vararg args: Any?) {
    contract { returns() implies condition }
    if (!condition) throw LocalizedException(key, *args)
}
fun errorLocalized(key: String, vararg args: Any?): Nothing = throw LocalizedException(key, *args)
