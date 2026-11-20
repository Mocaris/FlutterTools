package com.mocaris.plugin.flutter.tools.core.localization

import com.intellij.DynamicBundle
import com.intellij.util.xmlb.XmlSerializer
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localizedTooltip
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.nio.file.Files
import java.util.Locale
import java.util.ResourceBundle
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalizationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun <T> withLocale(locale: Locale, action: () -> T): T {
        val previous = Locale.getDefault()
        try { Locale.setDefault(locale); return action() }
        finally { Locale.setDefault(previous) }
    }

    private fun <T> withIdeLocale(locale: Locale, action: () -> T): T {
        val previous = DynamicBundle.getLocale()
        fun apply(value: Locale) = DynamicBundle.loadLocale(DynamicBundle.LanguageBundleEP().apply {
            this.locale = value.toLanguageTag()
        })
        try { apply(locale); return action() }
        finally { apply(previous) }
    }

    @Test fun ideLanguageSelectsChineseAndFallsBackToEnglish() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, PluginLanguage.SYSTEM.locale(Locale.TRADITIONAL_CHINESE))
        assertEquals(Locale.ENGLISH, PluginLanguage.SYSTEM.locale(Locale.FRENCH))
        assertEquals(Locale.ENGLISH, PluginLanguage.SYSTEM.locale(Locale.ROOT))
        assertEquals(Locale.ENGLISH, PluginLanguage.ENGLISH.locale(Locale.CHINESE))
        assertEquals(Locale.SIMPLIFIED_CHINESE, PluginLanguage.CHINESE.locale(Locale.ENGLISH))
        assertEquals(PluginLanguage.SYSTEM, FlutterToolsLanguageSettings().state.language)
    }

    @Test fun followSystemUsesIdeLanguageRegardlessOfOperatingSystemLocale() {
        withLocale(Locale.CHINESE) {
            withIdeLocale(Locale.ENGLISH) {
                assertEquals(Locale.ENGLISH, PluginLanguage.SYSTEM.locale())
                assertEquals("Save configuration", FlutterToolsBundle.message("assets.save"))
            }
        }
        withLocale(Locale.ENGLISH) {
            withIdeLocale(Locale.SIMPLIFIED_CHINESE) {
                assertEquals(Locale.SIMPLIFIED_CHINESE, PluginLanguage.SYSTEM.locale())
                assertEquals("保存配置", FlutterToolsBundle.message("assets.save"))
            }
        }
    }

    @Test fun explicitEnglishDoesNotFallBackToTheChineseSystemLocale() = withLocale(Locale.CHINESE) {
        assertEquals("Save configuration", FlutterToolsBundle.format("assets.save", Locale.ENGLISH))
        assertEquals("保存配置", FlutterToolsBundle.format("assets.save", Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun languagePreferenceSurvivesSerialization() {
        val options = FlutterToolsLanguageSettings.Options().apply { language = PluginLanguage.CHINESE }
        val saved = XmlSerializer.serialize(options)
        val restored = XmlSerializer.deserialize(saved, FlutterToolsLanguageSettings.Options::class.java)
        val settings = FlutterToolsLanguageSettings()
        settings.loadState(restored)
        assertEquals(PluginLanguage.CHINESE, settings.state.language)
    }

    @Test fun assetImagePreviewsDefaultOnAndPersistDisabledProjectPreferences() {
        assertTrue(FlutterToolsSettings().state.assetImagePreviews)
        val saved = XmlSerializer.serialize(FlutterToolsSettings.Options(assetImagePreviews = false))
        val restored = XmlSerializer.deserialize(saved, FlutterToolsSettings.Options::class.java)
        val settings = FlutterToolsSettings()
        settings.loadState(restored)
        assertFalse(settings.state.assetImagePreviews)
    }

    @Test fun editorPreferencesPersistAndOldWorkspacesReceiveDefaultValues() {
        val old = XmlSerializer.deserialize(org.jdom.Element("Options"), FlutterToolsSettings.Options::class.java)
        assertTrue(old.resourceAutoCompletion)
        assertTrue(old.dependencyVersionHints)
        assertEquals(100, old.resourceCompletionDelayMillis)
        assertEquals(1000, old.dependencyCheckDelayMillis)
        val options = old.copy(resourceAutoCompletion = false, resourceCompletionDelayMillis = 450,
            dependencyVersionHints = false, dependencyCheckDelayMillis = 1500)
        assertEquals(options, XmlSerializer.deserialize(XmlSerializer.serialize(options), FlutterToolsSettings.Options::class.java))
    }

    @Test fun loadingSettingsBoundsInvalidTimingValues() {
        val settings = FlutterToolsSettings()
        settings.loadState(FlutterToolsSettings.Options(cacheMinutes = 0,
            resourceCompletionDelayMillis = -100, dependencyCheckDelayMillis = Int.MAX_VALUE))
        assertEquals(1, settings.state.cacheMinutes)
        assertEquals(0, settings.state.resourceCompletionDelayMillis)
        assertEquals(10000, settings.state.dependencyCheckDelayMillis)
    }

    @Test fun cachedStatusesAndErrorsRenderInTheCurrentLanguage() {
        val status = FlutterToolsBundle.text("packages.override", FlutterToolsBundle.text("packages.changeConstraint"))
        val error = LocalizedException("error.assetMissing", "assets/logo.png")
        val failure = FlutterToolsBundle.text("assets.failed", error)
        assertEquals("Override: New version requires changing the constraint", status.render(Locale.ENGLISH))
        assertEquals("覆盖配置：升级新版本需要修改版本约束", status.render(Locale.CHINESE))
        assertEquals("生成失败：声明的资源不存在：assets/logo.png", failure.render(Locale.CHINESE))
    }

    @Test fun formattingPreservesQuotedPathsAndLiteralArguments() {
        val path = "assets/it's-{logo}.png"
        assertEquals("Resource directory declarations must end with '/': $path",
            FlutterToolsBundle.format("error.directorySlash", Locale.ENGLISH, path))
    }

    @Test fun updatingLabelsPreservesUnsavedTextAndSelection() = withIdeLocale(Locale.ENGLISH) {
        val label = JLabel().localized("assets.save")
        val check = JCheckBox().localized("assets.watch").apply { isSelected = true }
        val text = JTextArea("unsaved custom configuration").localizedTooltip("assets.exclusionsTip")
        val panel = JPanel().apply { add(label); add(check); add(text) }
        withIdeLocale(Locale.CHINESE) {
            FlutterToolsBundle.update(panel)
            assertEquals("保存配置", label.text)
            assertEquals("资源变动时自动生成", check.text)
            assertTrue(check.isSelected)
            assertEquals("unsaved custom configuration", text.text)
            assertEquals("每行填写一个通配符、目录或文件路径", text.toolTipText)
        }
    }

    @Test fun translationBundlesHaveMatchingKeysAndParameters() {
        val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        val english = ResourceBundle.getBundle("messages.FlutterToolsBundle", Locale.ROOT, javaClass.classLoader, control)
        val chinese = ResourceBundle.getBundle("messages.FlutterToolsBundle", Locale.CHINESE, javaClass.classLoader, control)
        assertEquals(english.keySet(), chinese.keySet())
        val parameters = Regex("\\{([0-9]+)\\}")
        english.keySet().forEach { key ->
            assertEquals(key, parameters.findAll(english.getString(key)).map { it.value }.toSet(),
                parameters.findAll(chinese.getString(key)).map { it.value }.toSet())
        }
    }

    @Test fun uiLanguageDoesNotChangeGeneratedDart() {
        val root = folder.root.toPath()
        Files.createDirectories(root.resolve("assets"))
        Files.writeString(root.resolve("assets/logo.png"), "resource")
        val yaml = "name: sample\nflutter:\n  assets: [assets/logo.png]\n"
        val english = withIdeLocale(Locale.ENGLISH) { AssetsGenerator.generate(root.toString(), AssetsSyncConfig(), yaml) }
        val chinese = withIdeLocale(Locale.CHINESE) { AssetsGenerator.generate(root.toString(), AssetsSyncConfig(), yaml) }
        assertEquals(english, chinese)
    }
}
