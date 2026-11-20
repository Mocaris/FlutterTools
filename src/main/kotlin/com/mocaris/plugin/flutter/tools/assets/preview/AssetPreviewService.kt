package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.*
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.FlutterToolsIcons
import com.mocaris.plugin.flutter.tools.core.VfsEventPaths
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.localized
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.localization.LanguageListener
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** No image I/O in marker collection or painting. Only visible icons enqueue work; queues and caches are bounded. */
@Service(Service.Level.PROJECT)
class AssetPreviewService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var enabled = project.service<FlutterToolsSettings>().effective().assetImagePreviews
    private val thumbnailJobs = ConcurrentHashMap<ImageRequest, Job>()
    private val readers = Semaphore(2)
    private val thumbnails = ThumbnailStore<ImageRequest, AssetImageLoader.Loaded>()
    private val resolvers = LinkedHashMap<String, (PubspecAssets.Declaration) -> PubspecAssets.Resolved>(4, .75f, true)
    private val repaintRoots = mutableSetOf<String>()
    private val repaintPending = AtomicBoolean()
    private var popup: JBPopup? = null
    private var popupJob: Job? = null
    private var popupEpoch = 0L

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = events.flatMap(VfsEventPaths::paths).distinct()
                if (paths.isEmpty()) return
                val configuration = paths.any { it.substringAfterLast('/') in setOf(".dart_tool", "package_config.json", "pubspec.yaml", "pubspec_overrides.yaml") }
                if (configuration) synchronized(resolvers) { resolvers.clear() }
                val relevant = configuration || events.any { it.file?.isDirectory == true } || paths.any(GeneratedAssetReferences::isImage)
                val changed = paths.map { Path.of(it).normalize() }
                val affected = thumbnails.invalidate({ request, loaded ->
                    changed.any { ImageInvalidation.affected(request, loaded.info, it) }
                }, { relevant })
                affected.map { it.root }.distinct().forEach(::repaint)
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LanguageListener.TOPIC, LanguageListener {
            ApplicationManager.getApplication().invokeLater {
                popup?.takeUnless { it.isDisposed }?.content?.let(FlutterToolsBundle::update)
            }
        })
    }

    private fun resolver(request: ImageRequest): (PubspecAssets.Declaration) -> PubspecAssets.Resolved {
        val root = Path.of(request.root)
        // Previewing should not retain arbitrarily large package metadata. Generation has no such preview limit.
        val config = DartPackageContext.packageConfig(root)
        if (request.path.startsWith("packages/") && Files.isRegularFile(config) && Files.size(config) > 1024 * 1024) {
            throw java.io.IOException("Package configuration exceeds the preview budget")
        }
        return synchronized(resolvers) {
            resolvers.getOrPut(request.root) {
                if (resolvers.size >= 4) resolvers.remove(resolvers.keys.first())
                PubspecAssets.resolver(root)
            }
        }
    }

    private fun load(request: ImageRequest, width: Int, height: Int): AssetImageLoader.Loaded {
        var location: AssetImageLoader.Location? = null
        return try {
            location = location(request)
            val file = location.file
            if (file == null) AssetImageLoader.Loaded(AssetImageLoader.Info(null, status = AssetImageLoader.Status.MISSING, logicalFile = location.logical))
            else AssetImageLoader.read(file, width, height).let { it.copy(info = it.info.copy(logicalFile = location.logical)) }
        } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) {
            AssetImageLoader.Loaded(AssetImageLoader.Info(location?.file, status = AssetImageLoader.Status.FAILED, logicalFile = location?.logical))
        }
    }

    internal fun location(request: ImageRequest) = AssetImageLoader.resolve(request, resolver(request))

    internal fun isEnabled() = enabled
    internal fun fileIcon(request: ImageRequest) = FileTypeManager.getInstance().getFileTypeByFileName(request.path.substringAfterLast('/')).icon ?: FlutterToolsIcons.assetImage
    internal fun pendingRequests() = thumbnails.pendingCount()
    internal fun cached(request: ImageRequest) = if (enabled) thumbnails.get(request) else null

    internal fun thumbnail(request: ImageRequest): BufferedImage? {
        if (!enabled || !scope.isActive) return null
        thumbnails.get(request)?.let { return it.image }
        val id = thumbnails.begin(request) ?: return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val value = readers.withPermit { runInterruptible { load(request, 32, 32) } }
            ensureActive()
            if (!enabled) return@launch
            thumbnails.complete(request, id, value)
        }
        thumbnailJobs[request] = job
        job.invokeOnCompletion {
            thumbnailJobs.remove(request, job); thumbnails.abandon(request, id)
            if (enabled && scope.isActive) repaint(request.root)
        }
        job.start()
        return null
    }

    /** Batch completed thumbnails into a gutter repaint, never a project-wide daemon restart. */
    private fun repaint(root: String) {
        synchronized(repaintRoots) { repaintRoots.add(root) }
        if (!repaintPending.compareAndSet(false, true)) return
        scope.launch {
            delay(50)
            ApplicationManager.getApplication().invokeLater {
                val roots = synchronized(repaintRoots) { repaintRoots.toSet().also { repaintRoots.clear(); repaintPending.set(false) } }
                if (!project.isDisposed && scope.isActive) {
                    EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }.forEach { editor ->
                        val file = FileDocumentManager.getInstance().getFile(editor.document)
                        if (file?.extension == "dart" && roots.any { file.path.startsWith("$it/") }) {
                            (editor.gutter as? Component)?.repaint()
                            LookupManager.getActiveLookup(editor)?.component?.repaint()
                        }
                    }
                }
            }
        }
    }

    internal fun description(request: ImageRequest, info: AssetImageLoader.Info?) = buildString {
        append(request.path)
        if (info?.width != null) append("\n" + message("preview.dimensions", info.width, info.height))
        if (info?.bytes != null) append("\n" + message("preview.bytes", info.bytes))
        if (GeneratedAssetReferences.isImage(request.path) && (info == null || info.status != AssetImageLoader.Status.READY)) append("\n" + message("preview.${info?.status?.name?.lowercase() ?: "loading"}"))
    }

    internal fun show(request: ImageRequest, event: MouseEvent) {
        if (!enabled || project.isDisposed) return
        val epoch = ++popupEpoch
        popup?.cancel(); popupJob?.cancel()
        if (!GeneratedAssetReferences.isImage(request.path)) {
            popupJob = scope.launch {
                val value = readers.withPermit { runInterruptible { load(request, 32, 32) } }
                ensureActive()
                ApplicationManager.getApplication().invokeLater {
                    if (!enabled || project.isDisposed || !scope.isActive || popupEpoch != epoch) return@invokeLater
                    value.info.file?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                        ?.let { OpenFileDescriptor(project, it).navigate(true) }
                }
            }
            return
        }
        val image = JBLabel().localized("preview.loading").apply {
            horizontalAlignment = SwingConstants.CENTER; preferredSize = Dimension(JBUI.scale(480), JBUI.scale(360))
        }
        val details = JBLabel(request.path)
        val open = JButton().localized("preview.open").apply { isEnabled = false }
        val content = JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(image, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply { add(details, BorderLayout.CENTER); add(open, BorderLayout.EAST) }, BorderLayout.SOUTH)
        }
        lateinit var current: JBPopup
        var job: Job? = null
        current = JBPopupFactory.getInstance().createComponentPopupBuilder(content, open)
            .setTitle(message("preview.title")).setProject(project).setFocusable(true).setRequestFocus(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    job?.cancel(); image.icon = null
                    if (popup === current) { popup = null; popupJob = null }
                }
            }).createPopup()
        popup = current
        current.show(RelativePoint(event))
        job = scope.launch {
            val value = readers.withPermit { runInterruptible { load(request, 960, 720) } }
            ensureActive()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !enabled || !scope.isActive || current.isDisposed || popup !== current) return@invokeLater
                image.icon = value.image?.let { PreviewIcon(it) }
                image.localized(if (value.image == null) "preview.${value.info.status.name.lowercase()}" else "preview.ready")
                details.localized("preview.info", request.path, value.info.width ?: "—", value.info.height ?: "—", value.info.bytes ?: "—")
                open.isEnabled = value.info.file != null
                val original = value.info.file
                open.addActionListener {
                    original?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                        ?.let { OpenFileDescriptor(project, it).navigate(true) }
                    current.cancel()
                }
            }
        }
        popupJob = job
    }

    /** Apply editor preferences immediately, independently of generator YAML edits. */
    fun preferencesChanged(value: Boolean) {
        enabled = value
        if (!value) {
            popupEpoch++
            thumbnailJobs.values.forEach { it.cancel() }
            popupJob?.cancel(); popup?.cancel()
            thumbnails.clear(); synchronized(resolvers) { resolvers.clear() }
        }
        if (!project.isDisposed) {
            EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }
                .onEach { editor ->
                    LookupManager.getActiveLookup(editor)?.component?.repaint()
                    if (editor.contentComponent.isFocusOwner) com.intellij.codeInsight.AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                }
                .mapNotNull { FileDocumentManager.getInstance().getFile(it.document) }
                .filter { it.extension == "dart" }.distinct().forEach { file ->
                    PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
                }
        }
    }

    override fun dispose() {
        scope.cancel(); thumbnails.clear(); synchronized(resolvers) { resolvers.clear() }
        synchronized(repaintRoots) { repaintRoots.clear() }
        val active = popup
        popup = null
        if (active != null) ApplicationManager.getApplication().invokeLater { active.cancel() }
    }
}

internal class ThumbnailIcon(private val service: AssetPreviewService, private val request: ImageRequest) : Icon {
    override fun getIconWidth() = JBUI.scale(16)
    override fun getIconHeight() = JBUI.scale(16)
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (!service.isEnabled()) return
        val image = if (GeneratedAssetReferences.isImage(request.path)) service.thumbnail(request) else null
        if (image == null) service.fileIcon(request).paintIcon(component, graphics, x, y)
        else {
            val ratio = minOf(iconWidth.toDouble() / image.width, iconHeight.toDouble() / image.height)
            val width = maxOf(1, (image.width * ratio).toInt()); val height = maxOf(1, (image.height * ratio).toInt())
            graphics.drawImage(image, x + (iconWidth - width) / 2, y + (iconHeight - height) / 2, width, height, null)
        }
    }
    override fun equals(other: Any?) = other is ThumbnailIcon && service === other.service && request == other.request
    override fun hashCode() = request.hashCode()
}

private class PreviewIcon(private val image: BufferedImage) : Icon {
    private val ratio = minOf(JBUI.scale(480).toDouble() / image.width, JBUI.scale(360).toDouble() / image.height, 1.0)
    override fun getIconWidth() = maxOf(1, (image.width * ratio).toInt())
    override fun getIconHeight() = maxOf(1, (image.height * ratio).toInt())
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) { graphics.drawImage(image, x, y, iconWidth, iconHeight, null) }
}
