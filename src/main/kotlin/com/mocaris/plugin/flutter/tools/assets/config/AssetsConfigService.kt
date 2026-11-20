package com.mocaris.plugin.flutter.tools.assets.config

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsClassNaming
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import java.nio.file.Path
import org.jetbrains.yaml.psi.YAMLFile

@Service(Service.Level.PROJECT)
class AssetsConfigService(private val project: Project) {
    data class Snapshot(val path: String, val text: String, val config: AssetsSyncConfig?, val exists: Boolean,
                        val defaultOutClass: String = DEFAULT_OUT_CLASS) {
        val effectiveConfig: AssetsSyncConfig get() = config ?: AssetsSyncConfig(outClass = defaultOutClass)
    }
    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    fun read(root: VirtualFile): Snapshot = ReadAction.compute<Snapshot, RuntimeException> {
        val file = root.findChild(TOOLS_FILE_NAME) ?: root.findChild(PUBSPEC_FILE_NAME)
        val text = file?.let { FileDocumentManager.getInstance().getDocument(it)?.text ?: String(it.contentsToByteArray(), it.charset) } ?: ""
        val path = file?.path ?: "${root.path}/$TOOLS_FILE_NAME"
        val defaultOutClass = AssetsClassNaming.defaultClass(Path.of(root.path), project.basePath?.let { Path.of(it) }, readPubspec(root))
        snapshots[root.path]?.takeIf { it.path == path && it.text == text && it.exists == (file != null) && it.defaultOutClass == defaultOutClass }?.let { return@compute it }
        val mapping = YamlSupport.load(text) as? Map<*, *>
        val configured = mapping?.containsKey("flutter_tools") == true
        // An existing independent config must never silently fall back to pubspec.
        val config = if (configured || file?.name == TOOLS_FILE_NAME) AssetsGenerator.parseText(text, defaultOutClass) else null
        Snapshot(path, text, config, file != null, defaultOutClass).also { snapshots[root.path] = it }
    }

    fun readPubspec(root: VirtualFile): String = ReadAction.compute<String, RuntimeException> {
        val file = root.findChild(PUBSPEC_FILE_NAME) ?: errorLocalized("error.pubspecMissing")
        FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
    }

    fun save(root: VirtualFile, snapshot: Snapshot, config: AssetsSyncConfig) {
        // Validate before touching the user's configuration.
        AssetsGenerator.outputPaths(java.nio.file.Path.of(root.path), config)
        AssetsGenerator.validateClassName(config.outClass)
        WriteCommandAction.runWriteCommandAction(project, message("command.saveConfig"), null, Runnable {
            val currentSource = root.findChild(TOOLS_FILE_NAME) ?: root.findChild(PUBSPEC_FILE_NAME)
            requireLocalized((currentSource?.path ?: "${root.path}/$TOOLS_FILE_NAME") == snapshot.path, "error.configSource")
            val source = if (snapshot.config == null && currentSource?.name != TOOLS_FILE_NAME) {
                // Preserve the source snapshot for conflict detection even when creating a separate file.
                requireLocalized(currentSource?.let { FileDocumentManager.getInstance().getDocument(it)?.text }.orEmpty() == snapshot.text, "error.configChanged")
                root.createChildData(this, TOOLS_FILE_NAME)
            } else currentSource ?: root.createChildData(this, TOOLS_FILE_NAME)
            val document = FileDocumentManager.getInstance().getDocument(source) ?: errorLocalized("error.configDocument")
            if (source.path == snapshot.path) requireLocalized(document.text == snapshot.text, "error.configChanged")
            val manager = PsiDocumentManager.getInstance(project)
            manager.commitDocument(document)
            val yaml = PsiManager.getInstance(project).findFile(source) as? YAMLFile ?: errorLocalized("error.yamlSupport")
            val updated = AssetsConfigEditor.update(yaml, config, snapshot.config)
            if (document.text != updated) document.setText(updated)
            manager.commitDocument(document)
            manager.doPostponedOperationsAndUnblockDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }
}
