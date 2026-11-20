package com.mocaris.plugin.flutter.tools.assets.inspection

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.mocaris.plugin.flutter.tools.assets.config.AssetsConfigService
import com.mocaris.plugin.flutter.tools.assets.config.PUBSPEC_FILE_NAME
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import java.nio.file.Path
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.*

class ResourceAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? YAMLFile ?: return
        if (file.name != PUBSPEC_FILE_NAME) return
        val root = FlutterPackageLocator.containing(file.virtualFile) ?: return
        try {
            val issues = CachedValuesManager.getCachedValue(file) {
                val config = file.project.service<AssetsConfigService>().read(root).effectiveConfig
                CachedValueProvider.Result.create(ResourceDiagnostics.inspect(file, Path.of(root.path), config),
                    file, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            }
            issues.forEach { issue ->
                val annotation = holder.newAnnotation(HighlightSeverity.WARNING, issue.message.toString()).range(issue.element.textRange)
                if (issue.replacement != null || issue.remove || issue.selectResource) annotation.withFix(ResourceQuickFix(issue))
                annotation.create()
            }
        } catch (e: ProcessCanceledException) { throw e }
        catch (_: Exception) { /* Incomplete YAML/config is handled by YAML support and the configuration page. */ }
    }
}

private class ResourceQuickFix(issue: ResourceDiagnostics.Issue) : IntentionAction {
    private val pointer = SmartPointerManager.createPointer(issue.element)
    private val original = issue.element.text
    private val replacement = issue.replacement
    private val remove = issue.remove
    private val selectResource = issue.selectResource
    override fun getText() = message(when { selectResource -> "inspection.chooseResource"; remove -> "inspection.removeDuplicate"; else -> "inspection.replace" }, replacement)
    override fun getFamilyName() = message("inspection.family")
    override fun startInWriteAction() = !selectResource
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element?.let { it.isValid && it.isWritable && it.text == original } == true
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val element = pointer.element?.takeIf { it.text == original } ?: return
        if (selectResource) {
            val root = FlutterPackageLocator.containing(element.containingFile.virtualFile) ?: return
            val directory = (element as? YAMLScalar)?.textValue?.endsWith('/') == true
            val descriptor = FileChooserDescriptor(!directory, directory, false, false, false, false).withRoots(root)
            descriptor.title = message("inspection.chooseResource")
            val chosen = FileChooser.chooseFile(descriptor, project, root) ?: return
            val base = Path.of(root.path)
            val path = Path.of(chosen.path)
            if (!path.startsWith(base)) return
            val relative = base.relativize(path).toString().replace('\\', '/').ifEmpty { "." } + if (directory) "/" else ""
            WriteCommandAction.runWriteCommandAction(project, familyName, null, Runnable {
                apply(project, "'${relative.replace("'", "''")}'")
            })
        } else apply(project, replacement)
    }

    private fun apply(project: Project, value: String?) {
        val element = pointer.element?.takeIf { it.isValid && it.text == original } ?: return
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(element.containingFile) ?: return
        val updated = if (remove) ResourceFix.removeDuplicate(element as YAMLSequenceItem)
            else document.text.replaceRange(element.textRange.startOffset, element.textRange.endOffset, value!!)
        document.setText(updated)
        manager.commitDocument(document)
    }
}

/** Minimal edits retain comments even when removing a flow or multi-line block declaration. */
internal object ResourceFix {
    fun removeDuplicate(item: YAMLSequenceItem): String {
        val file = item.containingFile as YAMLFile
        val source = file.text
        val sequence = item.parent as YAMLSequence
        val items = sequence.items
        val index = items.indexOf(item)
        var start = item.textRange.startOffset
        var end = item.textRange.endOffset
        val flow = sequence.text.trimStart().startsWith('[')
        if (flow) {
            if (index < items.lastIndex) end = items[index + 1].textRange.startOffset
            else if (index > 0) start = items[index - 1].textRange.endOffset
        } else {
            start = source.lastIndexOf('\n', start - 1) + 1
            end = source.indexOf('\n', end).let { if (it < 0) source.length else it + 1 }
        }
        val lexer = YAMLParserDefinition().createLexer(file.project)
        lexer.start(source, start, end)
        val comments = mutableListOf<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType == YAMLTokenTypes.COMMENT) comments.add(source.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        val retained = if (flow) comments.joinToString("\n", postfix = if (comments.isEmpty()) "" else "\n")
            else comments.joinToString("\n", postfix = if (comments.isEmpty()) "" else "\n") { " ".repeat(item.textOffset - start) + it }
        return source.replaceRange(start, end, retained)
    }
}
