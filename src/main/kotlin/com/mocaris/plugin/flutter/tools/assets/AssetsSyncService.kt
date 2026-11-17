package com.mocaris.plugin.flutter.tools.assets

import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.text
import com.mocaris.plugin.flutter.tools.core.LocalizedText
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.mocaris.plugin.flutter.tools.core.VfsEventPaths
import com.mocaris.plugin.flutter.tools.core.FlutterToolsNotifications
import com.intellij.notification.NotificationType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class AssetsSyncService(private val project: Project) : BulkFileListener, Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Request(val paths: Set<String> = emptySet(), val manualRoot: VirtualFile? = null, val initial: Boolean = false, val rediscover: Boolean = false, val editing: Boolean = false)
    @Volatile var roots: List<VirtualFile> = emptyList()
        private set
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val watchedResources = ConcurrentHashMap<String, List<PubspecAssets.Resolved>>()
    private val generatedOutputs = ConcurrentHashMap<String, Set<Path>>()
    private val configNames = setOf(PUBSPEC_FILE_NAME, TOOLS_FILE_NAME, "package_config.json", "pubspec_overrides.yaml")
    private val statuses = ConcurrentHashMap<String, LocalizedText>()
    fun status(root: VirtualFile): String = (statuses[root.path] ?: text("ready")).toString()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, this)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.name in setOf(PUBSPEC_FILE_NAME, TOOLS_FILE_NAME, "pubspec_overrides.yaml")) requests.trySend(Request(setOf(file.path), editing = true))
            }
        }, this)
        project.messageBus.connect(this).subscribe(com.intellij.ProjectTopics.PROJECT_ROOTS, object : com.intellij.openapi.roots.ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                requests.trySend(Request(initial = true, rediscover = true))
            }
        })
        scope.launch {
            for (first in requests) {
                val batch = mutableListOf(first)
                delay(500.milliseconds)
                while (true) batch.add(requests.tryReceive().getOrNull() ?: break)
                val previousRoots = roots.map { it.path }.toSet()
                if (batch.any { it.initial || it.rediscover }) {
                    roots = com.intellij.openapi.application.ReadAction.compute<List<VirtualFile>, RuntimeException> { FlutterPackageLocator.findPackages(project) }
                }
                val roots = (this@AssetsSyncService.roots + batch.mapNotNull { it.manualRoot }).filter { it.isValid }.distinctBy { it.path }
                val paths = batch.flatMap { it.paths }.toSet()
                watchedResources.keys.retainAll(roots.map { it.path }.toSet())
                generatedOutputs.keys.retainAll(roots.map { it.path }.toSet())
                roots.forEach { root ->
                    val manual = batch.any { it.manualRoot?.path == root.path }
                    val configChanged = paths.any { it == "${root.path}/$TOOLS_FILE_NAME" || DartPackageContext.metadataAffected(Path.of(root.path), Path.of(it)) }
                    val rootChanged = paths.any { it == root.path || root.path.startsWith("$it/") }
                    val resourcePaths = paths.filterNot { AssetsOutputWriter.isOutputEvent(it, generatedOutputs[root.path].orEmpty()) }
                    if (!manual && root.path in previousRoots && batch.none { it.initial } && !configChanged && !rootChanged &&
                        watchedResources[root.path].orEmpty().none { resource -> resourcePaths.any(resource::affectedBy) }) return@forEach
                    try {
                        val configService = project.service<AssetsConfigService>()
                        val snapshot = configService.read(root)
                        val config = snapshot.effectiveConfig
                        if (!manual && !config.watcher) return@forEach
                        val pubspec = configService.readPubspec(root)
                        val resources = PubspecAssets.resolve(Path.of(root.path), PubspecAssets.parse(pubspec))
                        watchedResources[root.path] = resources
                        statuses[root.path] = text("assets.generating")
                        val contents = AssetsGenerator.generate(Path.of(root.path), config, resources)
                        generatedOutputs[root.path] = contents.keys + AssetsGenerator.legacyOutputPaths(Path.of(root.path), config)
                        ensureActive()
                        publish(root, snapshot, pubspec, config, contents, manual)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { report(root, e, manual || batch.any { !it.editing }) }
                }
            }
        }
        requests.trySend(Request(initial = true))
    }

    private suspend fun publish(
        root: VirtualFile, snapshot: AssetsConfigService.Snapshot, pubspec: String,
        config: AssetsSyncConfig, contents: Map<Path, String>, manual: Boolean,
    ) {
        val prepared = AssetsOutputWriter.prepare(contents)
        try {
            currentCoroutineContext().ensureActive()
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed || !root.isValid) return@invokeAndWait
                val configs = project.service<AssetsConfigService>()
                if (configs.read(root) != snapshot || configs.readPubspec(root) != pubspec) {
                    requests.trySend(Request(setOf("${root.path}/$PUBSPEC_FILE_NAME"), if (manual) root else null))
                    return@invokeAndWait
                }
                ApplicationManager.getApplication().runWriteAction {
                    // Revalidate immediately before replacement, including paths changed during scanning.
                    val expected = AssetsGenerator.outputPaths(Path.of(root.path), config).values.toSet() +
                        setOf(AssetsGenerator.validateOutput(Path.of(root.path), config.outPath))
                    requireLocalized(expected == contents.keys, "error.outputChanged")
                    val obsolete = AssetsGenerator.legacyOutputPaths(Path.of(root.path), config)
                    val documents = FileDocumentManager.getInstance()
                    (contents.keys + obsolete).forEach { output ->
                        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(output.toFile())
                        val document = file?.let { documents.getCachedDocument(it) }
                        requireLocalized(document == null || !documents.isDocumentUnsaved(document), "error.outputUnsaved", output)
                    }
                    try {
                        AssetsOutputWriter.publish(contents, prepared, obsolete)
                    } finally {
                        (contents.keys + obsolete).forEach { output ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(output.toFile())?.refresh(false, false)
                        }
                    }
                }
                statuses[root.path] = text("assets.generated", config.outPath)
                if (manual) FlutterToolsNotifications.notify(project, statuses[root.path]!!.toString(), NotificationType.INFORMATION)
            }
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    fun generate(root: VirtualFile) { requests.trySend(Request(manualRoot = root)) }
    override fun after(events: List<VFileEvent>) {
        val paths = events.filter { it !is VFileContentChangeEvent }.flatMap { VfsEventPaths.paths(it) }.toMutableSet()
        events.filterIsInstance<VFileContentChangeEvent>().filter { it.file.name in configNames }.forEach { paths.add(it.path) }
        val rediscover = events.any(VfsEventPaths::affectsPackageDiscovery)
        if (paths.isNotEmpty()) requests.trySend(Request(paths, rediscover = rediscover))
    }
    private fun report(root: VirtualFile, error: Exception, notify: Boolean) {
        val message = text("assets.failed", error)
        if (statuses.put(root.path, message)?.toString() != message.toString() && notify) FlutterToolsNotifications.notify(project, text("assets.packageFailed", root.name, message).toString(), NotificationType.ERROR)
    }
    override fun dispose() { requests.close(); scope.cancel() }

}
