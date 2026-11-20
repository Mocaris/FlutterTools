package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.assets.config.DEFAULT_OUT_CLASS
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import java.nio.file.Files
import java.nio.file.Path

/** Default entry names depend on the target package, while explicit out_class values stay authoritative. */
object AssetsClassNaming {
    fun defaultClass(root: Path, projectRoot: Path?, pubspec: String): String {
        fun identity(path: Path) = if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
        if (projectRoot == null || identity(root) == identity(projectRoot)) return DEFAULT_OUT_CLASS
        val name = (YamlSupport.load(pubspec) as? Map<*, *>)?.get("name")
        requireLocalized(name is String && Regex("[a-z_][a-z0-9_]*").matches(name), "error.packageName")
        val prefix = name.split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return "${prefix.ifEmpty { "Package" }}R"
    }
}
