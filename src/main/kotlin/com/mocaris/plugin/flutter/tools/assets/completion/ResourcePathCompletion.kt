package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.codeInsight.completion.*
import com.intellij.icons.AllIcons
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import com.mocaris.plugin.flutter.tools.assets.preview.AssetPreviewService
import com.mocaris.plugin.flutter.tools.assets.preview.ThumbnailIcon
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings

class ResourcePathCompletion : CompletionContributor(), DumbAware {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.invocationCount == 0 && !context.project.service<FlutterToolsSettings>().effective().resourceAutoCompletion) return
        val string = ResourceString.find(context.file, context.editor.caretModel.offset) ?: return
        val prefix = string.prefix(context.editor.caretModel.offset) ?: return
        if ('/' !in prefix) return
        val root = FlutterPackageLocator.containing(context.file.virtualFile) ?: return
        val snapshot = context.project.service<ResourceCompletionService>().snapshot(root) ?: return
        if (snapshot.matches(prefix).none()) return
        context.offsetMap.addOffset(CompletionInitializationContext.START_OFFSET, string.start)
        context.offsetMap.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, string.end)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        if (parameters.isAutoPopup && !parameters.originalFile.project.service<FlutterToolsSettings>().effective().resourceAutoCompletion) return
        val file = parameters.originalFile
        val string = ResourceString.find(file, parameters.editor.caretModel.offset) ?: return
        val prefix = string.prefix(parameters.editor.caretModel.offset)?.takeIf { '/' in it } ?: return
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return
        val snapshot = file.project.service<ResourceCompletionService>().snapshot(root) ?: return
        if (snapshot.matches(prefix).none()) return
        val previews = file.project.service<FlutterToolsSettings>().effective().assetImagePreviews
        val matches = result.withPrefixMatcher(ResourcePathMatcher(prefix))
        matches.restartCompletionOnAnyPrefixChange()
        snapshot.matches(prefix).take(100).forEach { candidate ->
            val encoded = string.encode(candidate.request.path) ?: return@forEach
            var item = LookupElementBuilder.create(encoded).withPresentableText(candidate.request.path)
                .withBoldness(true).withTypeText(candidate.bytes?.let { StringUtil.formatFileSize(it) }, true)
                .withInsertHandler { context, _ ->
                    val end = context.offsetMap.getOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET)
                    if (end > context.tailOffset) context.document.deleteString(context.tailOffset, end)
                    context.editor.caretModel.moveToOffset(context.tailOffset)
                    if (context.completionChar == '/' || context.completionChar == string.quote) context.setAddCompletionChar(false)
                    if (candidate.directory) context.project.service<ResourceCompletionService>().typed(context.editor, context.tailOffset)
                }
            if (previews) item = item.withIcon(if (candidate.directory) AllIcons.Nodes.Folder
                else ThumbnailIcon(file.project.service<AssetPreviewService>(), candidate.request))
            matches.addElement(item.withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE))
        }
        result.stopHere()
    }
}

internal class ResourcePathMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String) = name.startsWith(prefix)
    override fun cloneWithPrefix(prefix: String) = ResourcePathMatcher(prefix)
}

class ResourcePathTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language.id != "Dart") return Result.CONTINUE
        val string = ResourceString.find(file, editor.caretModel.offset) ?: return Result.CONTINUE
        val prefix = string.prefix(editor.caretModel.offset) ?: return Result.CONTINUE
        // Our post-insertion handler owns the delay for resource paths.
        return if ('/' in prefix || c == '/') Result.STOP else Result.CONTINUE
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language.id != "Dart") return Result.CONTINUE
        if (!project.service<FlutterToolsSettings>().effective().resourceAutoCompletion) return Result.CONTINUE
        val offset = editor.caretModel.offset
        // PSI may still precede the inserted slash. Let completion commit/validate it after throttling.
        if (c != '/') {
            val string = ResourceString.find(file, offset) ?: ResourceString.find(file, offset - 1) ?: return Result.CONTINUE
            if (string.prefix((offset - 1).coerceAtLeast(string.start))?.contains('/') != true) return Result.CONTINUE
        }
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return Result.CONTINUE
        project.service<ResourceCompletionService>().apply { snapshot(root); typed(editor, editor.caretModel.offset) }
        return Result.CONTINUE
    }
}

class ResourcePathCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int) =
        if (psiFile.project.service<FlutterToolsSettings>().effective().resourceAutoCompletion &&
            ResourceString.find(psiFile, offset)?.prefix(offset)?.contains('/') == true) ThreeState.NO else ThreeState.UNSURE
}
