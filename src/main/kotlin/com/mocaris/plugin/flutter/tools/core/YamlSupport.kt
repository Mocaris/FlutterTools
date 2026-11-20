package com.mocaris.plugin.flutter.tools.core

import com.mocaris.plugin.flutter.tools.core.localization.LocalizedException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

object YamlSupport {
    fun load(text: String): Any? = try {
        Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false })).load<Any?>(text)
    } catch (e: YAMLException) { throw LocalizedException("error.yaml", e.message.orEmpty()) }
}
