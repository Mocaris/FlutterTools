package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceCompletionService
import com.mocaris.plugin.flutter.tools.assets.completion.ResourcePathIndex
import com.mocaris.plugin.flutter.tools.assets.completion.ResourceString
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.FlutterToolsIcons
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings

/** Optional Dart extension using platform PSI only, with no Dart implementation API dependency. */
class AssetLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {
    override fun getName() = message("preview.name")
    override fun getIcon() = FlutterToolsIcons.assetImage
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        data class Context(val root: VirtualFile, val generated: Boolean, val references: Map<Int, GeneratedAssetReferences.Reference>, val resources: Lazy<ResourcePathIndex.Snapshot?>)
        val contexts = mutableMapOf<PsiFile, Context?>()
        for (element in elements) {
            if (element.firstChild != null) continue
            val file = element.containingFile ?: continue
            if (file.language.id != "Dart") continue
            if (element.node.elementType.toString() !in stringStarts) continue
            val context = if (contexts.containsKey(file)) contexts[file] else {
                if (file.textLength > GeneratedAssetReferences.MAX_FILE_CHARS ||
                    !file.project.service<FlutterToolsSettings>().effective().assetImagePreviews) {
                    contexts[file] = null
                    continue
                }
                val generated = file.text.startsWith(AssetsGenerator.GENERATED_MARKER)
                val references = if (generated) CachedValuesManager.getCachedValue(file) {
                    CachedValueProvider.Result.create(GeneratedAssetReferences.parse(file.text), file)
                } else emptyMap()
                val root = FlutterPackageLocator.containing(file.virtualFile)
                root?.let { Context(it, generated, references, lazy {
                    if (generated) null else file.project.service<ResourceCompletionService>().snapshot(it)
                }) }.also { contexts[file] = it }
            } ?: continue
            val request = if (context.generated) {
                val reference = context.references[element.textOffset] ?: continue
                ImageRequest(context.root.path, reference.packageName, reference.path)
            } else {
                val literal = ResourceString.literal(element)?.takeIf { it.textOffset == element.textOffset } ?: continue
                val string = ResourceString.parse(literal.text)?.takeIf { '/' in it.value } ?: continue
                context.resources.value?.items?.get(string.value)?.takeUnless { it.directory }?.request ?: continue
            }
            val service = file.project.service<AssetPreviewService>()
            result.add(LineMarkerInfo(element, element.textRange, ThumbnailIcon(service, request),
                { "<html>" + StringUtil.escapeXmlEntities(service.description(request, service.cached(request)?.info)).replace("\n", "<br/>") + "</html>" },
                { event, _ -> service.show(request, event) }, GutterIconRenderer.Alignment.LEFT,
                { message("preview.accessible", request.path) }))
        }
    }

    companion object {
        private val stringStarts = setOf("OPEN_QUOTE", "RAW_SINGLE_QUOTED_STRING", "RAW_TRIPLE_QUOTED_STRING")
    }
}
