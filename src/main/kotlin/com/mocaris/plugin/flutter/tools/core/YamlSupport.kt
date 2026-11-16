package com.mocaris.plugin.flutter.tools.core

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.constructor.SafeConstructor

object YamlSupport {
    fun load(text: String): Any? = try {
        Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false })).load<Any?>(text)
    } catch (e: YAMLException) { throw LocalizedException("error.yaml", e.message.orEmpty()) }
}
