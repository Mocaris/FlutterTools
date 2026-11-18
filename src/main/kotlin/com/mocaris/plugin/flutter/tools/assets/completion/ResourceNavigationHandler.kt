package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.mocaris.plugin.flutter.tools.assets.AssetsGenerator
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.assets.preview.AssetPreviewService
import com.mocaris.plugin.flutter.tools.assets.preview.GeneratedAssetReferences
import com.mocaris.plugin.flutter.tools.assets.preview.ImageRequest
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import java.nio.file.Path

/** Ctrl/Cmd navigation is independent of the preview preference and never decodes an image. */
class ResourceNavigationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (file.language.id != "Dart") return null
        val literal = ResourceString.literal(sourceElement) ?: return null
        val string = ResourceString.parse(literal.text, literal.textOffset) ?: return null
        if (offset !in string.start until string.end || '/' !in string.value) return null
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return null
        try {
            val generated = if (file.text.startsWith(AssetsGenerator.GENERATED_MARKER)) {
                CachedValuesManager.getCachedValue(file) {
                    CachedValueProvider.Result.create(GeneratedAssetReferences.parse(file.text), file)
                }[literal.textOffset]
            } else null
            val candidate = file.project.service<ResourceCompletionService>().snapshot(root)?.items?.get(string.value)
            if (candidate?.directory == true) return null
            val request = generated?.let { ImageRequest(root.path, it.packageName, it.path) } ?: candidate?.request ?: run {
                // A first click must work even when no editor preview has warmed the resource index.
                val spec = root.findChild("pubspec.yaml")?.takeIf { it.length <= 1024 * 1024 } ?: return null
                val text = FileDocumentManager.getInstance().getCachedDocument(spec)?.text ?: String(spec.contentsToByteArray(), spec.charset)
                if (text.length > 1024 * 1024) return null
                val declaration = PubspecAssets.parse(text).firstOrNull {
                    if (it.directory) {
                        val prefix = Path.of(it.path).normalize().toString().replace('\\', '/').trimEnd('/')
                        string.value.startsWith(if (prefix == "." || prefix.isEmpty()) "" else "$prefix/")
                    } else it.path == string.value
                } ?: return null
                ImageRequest(root.path, declaration.packageName, string.value)
            }
            val target = file.project.service<AssetPreviewService>().location(request).file ?: return null
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) ?: return null
            return PsiManager.getInstance(file.project).findFile(virtual)?.let { arrayOf(it) }
        } catch (e: ProcessCanceledException) { throw e }
        catch (_: Exception) { return null }
    }
}
