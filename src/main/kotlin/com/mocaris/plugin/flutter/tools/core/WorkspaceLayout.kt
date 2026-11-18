package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.FileSystems
import java.nio.file.Path

/** Native Pub members take precedence; Melos adds its legacy package globs and ignore rules. */
internal object WorkspaceLayout {
    val configurationNames = setOf("pubspec.yaml", "pubspec_overrides.yaml", "melos.yaml")
    private val knownOwners = LinkedHashMap<String, Boolean>(16, .75f, true)
    data class Rules(val members: List<String>?, val ignored: List<String>, val melos: Map<*, *>?) {
        private val memberMatchers = members?.flatMap(::matchers)
        private val ignoreMatchers = ignored.flatMap(::matchers)
        fun excludes(relative: String) = relative.isNotEmpty() && ignoreMatchers.any { it.matches(Path.of(relative)) }
        fun includes(relative: String) = relative.isEmpty() ||
            (memberMatchers == null || memberMatchers.any { it.matches(Path.of(relative)) }) && !excludes(relative)
    }

    fun read(file: VirtualFile?): String? = file?.takeIf { it.length <= 1024 * 1024 }?.let {
        (com.intellij.openapi.application.ApplicationManager.getApplication()?.getService(FileDocumentManager::class.java)
            ?.getCachedDocument(it)?.text ?: String(it.contentsToByteArray(), it.charset))
            .takeIf { text -> text.length <= 1024 * 1024 }
    }

    fun parse(spec: String?, overrides: String?, melos: String?): Rules? {
        fun mapping(text: String?) = text?.let { YamlSupport.load(it) as? Map<*, *> }
        val pub = mapping(spec).orEmpty()
        val external = mapping(overrides).orEmpty()
        val native = if (external.containsKey("workspace")) external["workspace"] else pub["workspace"]
        val modern = pub["melos"] as? Map<*, *>
        val config = if (native is List<*> && modern != null) modern else mapping(melos) ?: modern
        if (native !is List<*> && config == null) return null
        fun paths(value: Any?) = (value as? List<*>)?.filterIsInstance<String>()
        return Rules(paths(native) ?: paths(config?.get("packages")), paths(config?.get("ignore")).orEmpty(), config)
    }

    fun rules(directory: VirtualFile): Rules? = runCatching {
        parse(read(directory.findChild("pubspec.yaml")), read(directory.findChild("pubspec_overrides.yaml")), read(directory.findChild("melos.yaml")))
    }.getOrNull()?.also {
        synchronized(knownOwners) {
            knownOwners[directory.path] = true
            if (knownOwners.size > 128) knownOwners.remove(knownOwners.keys.first())
        }
    }

    fun configurationContent(file: VirtualFile): Boolean = file.name == "melos.yaml" ||
        file.name in configurationNames && (synchronized(knownOwners) { file.parent?.path in knownOwners } ||
            read(file)?.let { Regex("(?m)^(workspace|melos):").containsMatchIn(it) } == true)

    fun owner(directory: VirtualFile, melosOnly: Boolean = false): Pair<VirtualFile, Rules>? {
        var current: VirtualFile? = directory
        while (current != null) {
            val rules = rules(current)
            if (rules != null && (!melosOnly || rules.melos != null)) return current to rules
            current = current.parent
        }
        return null
    }

    private fun matchers(pattern: String): List<java.nio.file.PathMatcher> {
        // NIO supports braces and character classes; also allow **/ to match zero directories.
        val alternatives = linkedSetOf(pattern.removePrefix("./").replace('\\', '/').trimEnd('/'))
        if (alternatives.first().endsWith("/**")) alternatives.add(alternatives.first().removeSuffix("/**"))
        repeat(8) {
            alternatives.toList().forEach { candidate ->
                val index = candidate.indexOf("**/")
                if (index >= 0) alternatives.add(candidate.removeRange(index, index + 3))
            }
        }
        return alternatives.mapNotNull { glob -> runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull() }
    }

    fun configurationChanged(event: DocumentEvent): Boolean {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return false
        if (file.name == "melos.yaml") return true
        if (file.name !in configurationNames || event.document.textLength > 1024 * 1024) return false
        val keys = Regex("(?m)^(workspace|melos):")
        val current = event.document.text
        return keys.containsMatchIn(current) || keys.containsMatchIn(current.replaceRange(event.offset, event.offset + event.newLength, event.oldFragment.toString()))
    }
}
