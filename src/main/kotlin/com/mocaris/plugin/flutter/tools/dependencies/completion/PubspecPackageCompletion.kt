package com.mocaris.plugin.flutter.tools.dependencies.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.message
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

class PubspecPackageCompletion : CompletionContributor(), DumbAware {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.file.name != "pubspec.yaml" || context.file !is YAMLFile) return
        val key = DependencyCompletionContext.find(context.editor.document.charsSequence, context.editor.caretModel.offset) ?: return
        context.offsetMap.addOffset(CompletionInitializationContext.START_OFFSET, key.start)
        context.offsetMap.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, key.end)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? YAMLFile ?: return
        if (file.name != "pubspec.yaml" || parameters.completionType != CompletionType.BASIC) return
        val editor = parameters.editor
        val key = DependencyCompletionContext.find(editor.document.charsSequence, editor.caretModel.offset) ?: return
        val snapshot = file.project.service<PackageCompletionService>().suggestions(editor, key)
        val top = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping
        val declared = listOf("dependencies", "dev_dependencies").flatMap { section ->
            (top?.getKeyValueByKey(section)?.value as? YAMLMapping)?.keyValues.orEmpty()
        }.filter { it.key?.textRange?.startOffset != key.start }.map { it.keyText }.toSet()
        val matches = result.withPrefixMatcher(PackageNameMatcher(key.prefix))
        matches.restartCompletionOnAnyPrefixChange()
        snapshot?.items?.filter { it.name !in declared }?.forEach { candidate ->
            matches.addElement(PackageCompletionPresentation.builder(candidate)
                .withInsertHandler { context, _ ->
                    val document = context.document
                    val identifierEnd = context.offsetMap.getOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET)
                    if (identifierEnd > context.tailOffset) document.deleteString(context.tailOffset, identifierEnd)
                    val end = document.getLineEndOffset(document.getLineNumber(context.tailOffset))
                    val suffix = document.charsSequence.subSequence(context.tailOffset, end).toString()
                    DependencyCompletionContext.versionEdit(suffix, candidate.version)?.let { (offset, value) ->
                        val position = context.tailOffset + offset
                        document.insertString(position, value)
                        context.tailOffset = position + value.trimEnd().length
                        context.editor.caretModel.moveToOffset(context.tailOffset)
                    }
                    if (context.completionChar == ':') context.setAddCompletionChar(false)
                }.withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE))
        }
        matches.addLookupAdvertisement(message(if (snapshot?.failed == true) "completion.failed" else "completion.source"))
        // Only package candidates belong at a pubspec dependency key.
        result.stopHere()
    }
}

internal class PackageNameMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String) = name.contains(prefix, ignoreCase = true)
    override fun cloneWithPrefix(prefix: String) = PackageNameMatcher(prefix)
}

class PubspecPackageTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.name != "pubspec.yaml" || file !is YAMLFile) return Result.CONTINUE
        val key = DependencyCompletionContext.find(editor.document.charsSequence, editor.caretModel.offset)
        if (key != null) {
            project.service<PackageCompletionService>().suggestions(editor, key)
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        } else project.service<PackageCompletionService>().cancel(editor)
        return Result.CONTINUE
    }
}

class PubspecPackageCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int) =
        if (psiFile is YAMLFile && psiFile.name == "pubspec.yaml" && DependencyCompletionContext.find(psiFile.viewProvider.document?.charsSequence ?: psiFile.text, offset) != null)
            ThreeState.NO else ThreeState.UNSURE
}
