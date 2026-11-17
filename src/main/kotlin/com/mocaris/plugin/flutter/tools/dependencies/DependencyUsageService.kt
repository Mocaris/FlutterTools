package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.core.VfsEventPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Scan once per opened package, then read only changed Dart files after a short quiet period. */
@Service(Service.Level.PROJECT)
class DependencyUsageService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readers = Semaphore(2)
    private val roots = ConcurrentHashMap<String, VirtualFile>()
    private val states = ConcurrentHashMap<String, DependencyUsageSnapshot>()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private val dirty = mutableMapOf<String, MutableSet<Path>>()
    private val full = mutableSetOf<String>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension == "dart") affected(file.path).forEach { schedule(it, Path.of(file.path)) }
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                roots.entries.filter { !it.value.isValid || it.key != it.value.path }.forEach { forget(it.key) }
                events.forEach { event -> VfsEventPaths.paths(event).forEach { name ->
                    affected(name).forEach { root ->
                        if (name == "${root.path}/pubspec.yaml" && event is VFileDeleteEvent) forget(root.path)
                        else if (name.endsWith("/pubspec.yaml") || event !is VFileContentChangeEvent &&
                            (event is VFileCreateEvent && event.isDirectory || event.file?.isDirectory == true)) schedule(root)
                        else if (name.endsWith(".dart")) schedule(root, Path.of(name))
                    }
                } }
            }
        })
    }

    private fun affected(name: String): List<VirtualFile> {
        val path = Path.of(name)
        return roots.values.filter { DependencyUsageScan.relevant(Path.of(it.path), path) }
    }
    @Synchronized internal fun ensure(root: VirtualFile) {
        if (!root.isInLocalFileSystem) return
        roots[root.path] = root
        if (states[root.path] == null && jobs[root.path]?.isActive != true) schedule(root)
    }
    internal fun unused(root: VirtualFile, dependency: PackageDependency): Boolean? = states[root.path]?.unused(dependency)

    @Synchronized private fun forget(key: String) {
        roots.remove(key); states.remove(key); dirty.remove(key); full.remove(key)
        revisions[key] = (revisions[key] ?: 0) + 1
        jobs.remove(key)?.cancel()
    }
    @Synchronized private fun schedule(root: VirtualFile, path: Path? = null) {
        val key = root.path
        if (path == null) full.add(key) else dirty.getOrPut(key) { mutableSetOf() }.add(path)
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            delay(500)
            val context = currentCoroutineContext()
            val changed = synchronized(this@DependencyUsageService) { dirty[key]?.toSet().orEmpty() }
            val previous = states[key]
            val snapshot = try { readers.withPermit {
                if (previous == null || synchronized(this@DependencyUsageService) { key in full })
                    DependencyUsageScan.scan(Path.of(key), ::read) { context.ensureActive() }
                else DependencyUsageScan.update(Path.of(key), previous, changed, ::read) { context.ensureActive() }
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.debug("Dependency usage scan incomplete", e)
                DependencyUsageSnapshot(previous?.files.orEmpty(), false)
            }
            context.ensureActive()
            synchronized(this@DependencyUsageService) {
                if (revisions[key] != revision) return@launch
                states[key] = snapshot
                dirty.remove(key); full.remove(key); jobs.remove(key)
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !root.isValid || synchronized(this@DependencyUsageService) { revisions[key] != revision }) return@invokeLater
                val file = root.findChild("pubspec.yaml") ?: return@invokeLater
                FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
                    EditorFactory.getInstance().getEditors(document, project).filterNot { it.isDisposed }.forEach(PubspecInlayHints::invalidate)
                }
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }
        }
    }

    private fun read(path: Path): String? {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        val saved = ReadAction.compute<Pair<Boolean, String?>, RuntimeException> {
            val document = file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            (document != null) to document?.takeIf { it.textLength <= DependencyUsageScan.MAX_FILE_BYTES }?.text
        }
        if (saved.first) return saved.second
        return try {
            Files.newInputStream(path).use { stream ->
                val bytes = stream.readNBytes(DependencyUsageScan.MAX_FILE_BYTES.toInt() + 1)
                if (bytes.size > DependencyUsageScan.MAX_FILE_BYTES) null else String(bytes, file?.charset ?: Charsets.UTF_8)
            }
        } catch (_: java.io.IOException) { null }
    }
    override fun dispose() { scope.cancel(); synchronized(this) { states.clear(); roots.clear(); dirty.clear(); full.clear(); jobs.clear() } }
    companion object { private val log = Logger.getInstance(DependencyUsageService::class.java) }
}
