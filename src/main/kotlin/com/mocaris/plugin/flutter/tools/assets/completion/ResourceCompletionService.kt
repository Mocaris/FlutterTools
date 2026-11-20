package com.mocaris.plugin.flutter.tools.assets.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.VfsEventPaths
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class ResourceCompletionService(private val project: Project) : Disposable {
    private data class Entry(val root: VirtualFile, var snapshot: ResourcePathIndex.Snapshot? = null, var job: Job? = null,
                             var watched: List<PubspecAssets.Resolved> = emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readers = Semaphore(2)
    private var popupJob: Job? = null
    private val entries = LinkedHashMap<String, Entry>(4, .75f, true)

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = events.flatMap(VfsEventPaths::paths).distinct()
                synchronized(this@ResourceCompletionService) {
                    entries.values.filter { entry -> paths.any { path ->
                        DartPackageContext.metadataAffected(Path.of(entry.root.path), Path.of(path)) ||
                            entry.root.path.startsWith("$path/") || entry.snapshot == null && path.startsWith("${entry.root.path}/") || entry.watched.any { resource ->
                                resource.affectedBy(path) || resource.declaration.directory && Path.of(path).startsWith(resource.file)
                            }
                    } }.forEach { invalidate(it) }
                }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.name !in setOf("pubspec.yaml", "pubspec_overrides.yaml")) return
                synchronized(this@ResourceCompletionService) {
                    entries.values.filter { DartPackageContext.metadataAffected(Path.of(it.root.path), Path.of(file.path)) }.forEach { invalidate(it) }
                }
            }
        }, this)
    }

    @Synchronized internal fun snapshot(root: VirtualFile): ResourcePathIndex.Snapshot? {
        if (!root.isValid || project.isDisposed) return null
        val entry = entries.getOrPut(root.path) {
            if (entries.size >= 4) entries.remove(entries.keys.first())?.job?.cancel()
            Entry(root)
        }
        if (entry.snapshot == null && entry.job == null) load(entry)
        return entry.snapshot
    }

    private fun invalidate(entry: Entry) {
        entry.job?.cancel(); entry.job = null; entry.snapshot = null
        load(entry)
    }

    private fun load(entry: Entry) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(100.milliseconds)
            val snapshot = readers.withPermit {
                runInterruptible {
                    try {
                        val cached = ReadAction.compute<String?, RuntimeException> {
                            entry.root.findChild("pubspec.yaml")?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text }
                        }
                        val path = Path.of(entry.root.path, "pubspec.yaml")
                        val text = cached ?: if (Files.isRegularFile(path) && Files.size(path) <= 1024 * 1024) Files.readString(path) else ""
                        if (text.length > 1024 * 1024) ResourcePathIndex.Snapshot(emptyMap(), emptyList())
                        else ResourcePathIndex.collect(Path.of(entry.root.path), text)
                    } catch (e: InterruptedException) { throw e }
                    catch (_: Exception) { ResourcePathIndex.Snapshot(emptyMap(), emptyList()) }
                }
            }
            ensureActive()
            synchronized(this@ResourceCompletionService) {
                if (entries[entry.root.path] !== entry || entry.job !== coroutineContext[Job]) return@launch
                entry.snapshot = snapshot; entry.watched = snapshot.watched; entry.job = null
            }
            refresh(entry.root.path)
        }
        entry.job = job
        job.start()
    }

    @Synchronized internal fun typed(editor: com.intellij.openapi.editor.Editor, offset: Int) {
        popupJob?.cancel()
        val options = project.service<FlutterToolsSettings>().effective().normalized()
        if (!options.resourceAutoCompletion) return
        val stamp = editor.document.modificationStamp
        popupJob = scope.launch {
            delay(options.resourceCompletionDelayMillis.milliseconds)
            val pending = coroutineContext[Job]!!
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !editor.isDisposed && !pending.isCancelled && popupJob === pending &&
                    project.service<FlutterToolsSettings>().effective().resourceAutoCompletion &&
                    editor.document.modificationStamp == stamp && editor.caretModel.offset == offset)
                    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
    }

    @Synchronized fun preferencesChanged() { popupJob?.cancel(); popupJob = null }

    private fun refresh(root: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !scope.isActive) return@invokeLater
            EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }.forEach { editor ->
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
                if (file.extension != "dart" || !file.path.startsWith("$root/")) return@forEach
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
                if (editor.contentComponent.isFocusOwner) typed(editor, editor.caretModel.offset)
            }
        }
    }

    override fun dispose() { scope.cancel(); synchronized(this) { entries.clear() } }
}
