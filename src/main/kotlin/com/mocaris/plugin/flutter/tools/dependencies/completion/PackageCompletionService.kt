package com.mocaris.plugin.flutter.tools.dependencies.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.mocaris.plugin.flutter.tools.core.settings.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageSearchService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PackageCandidate(val name: String, val version: String, val published: String? = null, val likes: Int? = null)

@Service(Service.Level.PROJECT)
class PackageCompletionService(private val project: Project) : Disposable {
    internal data class Snapshot(val items: List<PackageCandidate>, val failed: Boolean = false, val time: Long = System.currentTimeMillis())
    private data class Request(val editor: Editor, val offset: Int, val stamp: Long, val key: String)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = LatestSearch(scope)
    private val details = Semaphore(2)
    private val cache = linkedMapOf<String, Snapshot>()
    private var pending: Request? = null

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) { cancel(event.editor) }
        }, this)
    }

    @Synchronized internal fun suggestions(editor: Editor, context: DependencyCompletionContext): Snapshot? {
        val options = project.service<FlutterToolsSettings>().effective()
        val prerelease = options.includePrerelease
        val key = "${context.prefix}:$prerelease"
        cache[key]?.takeIf { System.currentTimeMillis() - it.time < if (it.failed) 30_000 else options.cacheMinutes.coerceIn(1, 1440) * 60_000L }
            ?.let { return it }
        val request = Request(editor, editor.caretModel.offset, editor.document.modificationStamp, key)
        if (pending == request) return null
        pending = request
        throttle.submit {
            val snapshot = try {
                val search = project.service<PackageSearchService>()
                val names = search.search(context.prefix, 1).names.filter { it.contains(context.prefix) }.take(10)
                val failed = AtomicBoolean()
                val items = coroutineScope {
                    names.map { name -> async {
                        details.withPermit {
                            try {
                                val metadata = search.info(name).metadata
                                val version = (if (prerelease) metadata.newest else metadata.stable) ?: return@withPermit null
                                val likes = try { search.score(name).likes }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { failed.set(true); log.debug("Package completion likes unavailable", e); null }
                                PackageCandidate(name, version, metadata.releases.firstOrNull { it.version == version }?.published, likes)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failed.set(true); log.debug("Package completion details unavailable", e); null }
                        }
                    } }.awaitAll().filterNotNull()
                }
                Snapshot(items, failed.get())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.debug("Package completion search unavailable", e); Snapshot(emptyList(), true) }
            currentCoroutineContext().ensureActive()
            synchronized(this@PackageCompletionService) {
                if (pending != request) return@submit
                if (cache.size >= 32) cache.remove(cache.keys.first())
                cache[key] = snapshot
                pending = null
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || editor.isDisposed || !editor.contentComponent.isFocusOwner ||
                    editor.document.modificationStamp != request.stamp || editor.caretModel.offset != request.offset) return@invokeLater
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
        return null
    }

    @Synchronized internal fun cancel(editor: Editor) {
        if (pending?.editor == editor) { pending = null; throttle.cancel() }
    }
    override fun dispose() { scope.cancel(); synchronized(this) { pending = null; cache.clear() } }
    companion object { private val log = Logger.getInstance(PackageCompletionService::class.java) }
}
