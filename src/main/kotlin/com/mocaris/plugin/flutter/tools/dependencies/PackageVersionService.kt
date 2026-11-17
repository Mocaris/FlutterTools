package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import com.mocaris.plugin.flutter.tools.core.errorLocalized
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle.text
import com.mocaris.plugin.flutter.tools.core.LanguageListener
import com.mocaris.plugin.flutter.tools.core.LocalizedText
import com.mocaris.plugin.flutter.tools.core.FlutterPackageLocator
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.mocaris.plugin.flutter.tools.core.FlutterToolsSettings
import com.mocaris.plugin.flutter.tools.core.VfsEventPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import com.mocaris.plugin.flutter.tools.dependencies.search.ProjectSdk
import com.mocaris.plugin.flutter.tools.dependencies.search.ProjectSdkReader
import com.mocaris.plugin.flutter.tools.dependencies.search.SdkRecommendation
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class PackageVersionService(private val project: Project) : Disposable {
    data class PackageState(val root: VirtualFile, val results: List<DependencyResult> = emptyList(), private val messageText: LocalizedText = text("ready"), val valid: Boolean = true) {
        val message get() = messageText.toString()
        fun resultFor(dependency: PackageDependency): DependencyResult? = if (!dependency.isPubDev) null else
            results.firstOrNull { it.dependency.samePackage(dependency) }?.copy(dependency = dependency)
    }
    private data class Cached(val metadata: PubDevClient.Metadata?, val error: Throwable?, val time: Long) {
        private val recommendations = ConcurrentHashMap<Pair<ProjectSdk?, Boolean>, SdkRecommendation.Result>()
        fun recommendation(sdk: ProjectSdk?, prerelease: Boolean): SdkRecommendation.Result =
            recommendations.getOrPut(sdk to prerelease) { SdkRecommendation.recommend(metadata?.releases.orEmpty(), sdk, prerelease) }

        fun fresh(minutes: Int, now: Long = System.currentTimeMillis()): Boolean {
            val ttl = if (error != null) 60_000L else minutes.coerceIn(1, 1440) * 60_000L
            return time <= now && now - time < ttl
        }
    }
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, PackageState>()
    private val sdks = ConcurrentHashMap<String, ProjectSdk>()
    private val checkedSignatures = ConcurrentHashMap<String, List<List<String>>>()
    private val revisions = ConcurrentHashMap<String, AtomicLong>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pendingOpens = ConcurrentHashMap.newKeySet<String>()
    private val cache = ConcurrentHashMap<String, Cached>()
    private val localCache = PubLocalCache.forProject(project)
    private val packageLocks = ConcurrentHashMap<String, Mutex>()
    private val network = Semaphore(4)
    @Volatile var roots: List<VirtualFile> = emptyList()
        private set

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.name in watchedNames) {
                    val targets = roots.filter { DartPackageContext.metadataAffected(java.nio.file.Path.of(it.path), java.nio.file.Path.of(file.path)) } +
                        listOfNotNull(containingOwned(file))
                    targets.distinctBy { it.path }.forEach { schedule(it) }
                }
            }
        }, this)
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = events.flatMap { VfsEventPaths.paths(it) }
                roots.filter { root -> paths.any { DartPackageContext.sdkAffected(java.nio.file.Path.of(root.path), java.nio.file.Path.of(it)) } }.forEach { schedule(it) }
                if (events.any(VfsEventPaths::affectsPackageDiscovery)) discover()
                paths.filter { it.substringAfterLast('/') in watchedNames }.forEach { path ->
                    val root = roots.firstOrNull { path.startsWith("${it.path}/") && path.substringAfter("${it.path}/").indexOf('/') == -1 }
                    if (root != null) schedule(root)
                    else if (path.endsWith("/pubspec.lock")) roots.filter { it.path.startsWith(path.substringBeforeLast('/') + "/") }.forEach { schedule(it) }
                }
            }
        })
        connection.subscribe(com.intellij.ProjectTopics.PROJECT_ROOTS, object : com.intellij.openapi.roots.ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) { discover() }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (file.name == "pubspec.yaml") containingOwned(file)?.let { schedule(it, checkCache = true, opened = true) }
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LanguageListener.TOPIC, LanguageListener {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    EditorFactory.getInstance().allEditors.filter { it.project == project }
                        .forEach(PubspecInlayHints::invalidate)
                    DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }
        })
        discover()
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (project.isDisposed) break
                val options = project.service<FlutterToolsSettings>().effective().copy()
                if (options.automaticChecks) roots.forEach { root ->
                    val due = state(root).results.any { result -> result.dependency.isPubDev &&
                        cache[result.dependency.packageName]?.fresh(options.cacheMinutes) != true }
                    if (due && jobs[root.path]?.isActive != true) schedule(root, checkCache = true)
                }
            }
        }
    }

    private fun containingOwned(file: VirtualFile): VirtualFile? = FlutterPackageLocator.containing(file)?.takeIf { root ->
        roots.any { it.path == root.path } || project.basePath?.let { root.path == it || root.path.startsWith("$it/") } == true
    }
    fun state(root: VirtualFile): PackageState = states[root.path] ?: PackageState(root)
    fun resultFor(root: VirtualFile, dependency: PackageDependency): DependencyResult? {
        if (!dependency.isPubDev) return null
        val previous = state(root).resultFor(dependency)
        val cached = cache[dependency.packageName] ?: return previous
        return result(dependency, previous?.installed, cached, project.service<FlutterToolsSettings>().effective().includePrerelease, sdks[root.path])
    }
    fun refresh(root: VirtualFile) { schedule(root, force = true) }
    fun preferencesChanged() {
        EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }
            .forEach(PubspecInlayHints::invalidate)
        DaemonCodeAnalyzer.getInstance(project).restart()
        roots.forEach { schedule(it, checkCache = true) }
    }
    @Synchronized
    fun discover() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val found = ReadAction.compute<List<VirtualFile>, RuntimeException> { FlutterPackageLocator.findPackages(project) }
            ensureActive()
            val previous = roots.map { it.path }.toSet()
            roots = found
            states.keys.filter { path -> found.none { it.path == path } }.forEach { path ->
                revisions.getOrPut(path) { AtomicLong() }.incrementAndGet()
                jobs.remove(path)?.cancel(); states.remove(path)
                pendingOpens.remove(path); checkedSignatures.remove(path); sdks.remove(path)
            }
            val opened = ReadAction.compute<Set<String>, RuntimeException> {
                FileEditorManager.getInstance(project).openFiles.filter { it.name == "pubspec.yaml" }
                    .mapNotNull { FlutterPackageLocator.containing(it)?.path }.toSet()
            }
            found.filter { it.path !in previous || it.path in opened }
                .forEach { schedule(it, checkCache = true, opened = it.path in opened) }
        }
    }

    @Synchronized
    private fun schedule(root: VirtualFile, force: Boolean = false, checkCache: Boolean = false, opened: Boolean = false) {
        if (opened) pendingOpens.add(root.path)
        val revision = revisions.getOrPut(root.path) { AtomicLong() }.incrementAndGet()
        jobs.remove(root.path)?.cancel()
        jobs[root.path] = scope.launch {
            if (!force) delay(project.service<FlutterToolsSettings>().effective().normalized().dependencyCheckDelayMillis.milliseconds)
            val old = states[root.path]
            try {
                val dependencies = ReadAction.compute<List<PackageDependency>, RuntimeException> {
                    val file = root.findChild("pubspec.yaml") ?: errorLocalized("error.pubspecRemoved")
                    val text = readText(file)
                    val yaml = PsiFileFactory.getInstance(project).createFileFromText("pubspec.yaml", YAMLFileType.YML, text) as YAMLFile
                    val overrides = root.findChild("pubspec_overrides.yaml")?.let { overrideFile ->
                        PsiFileFactory.getInstance(project).createFileFromText("pubspec_overrides.yaml", YAMLFileType.YML, readText(overrideFile)) as YAMLFile
                    }
                    PubspecDependencyParser.forVersionChecks(PubspecDependencyParser.parse(yaml, overrides))
                }
                val sdk = ReadAction.compute<ProjectSdk?, RuntimeException> { ProjectSdkReader.read(root) }
                if (sdk == null) sdks.remove(root.path) else sdks[root.path] = sdk
                val installed = if (dependencies.isEmpty()) emptyMap() else installedVersions(root)
                val previousSignatures = checkedSignatures[root.path]
                val changedPackages = DependencyRefreshPlan.changedPackages(dependencies, previousSignatures)
                val options = project.service<FlutterToolsSettings>().effective().copy()
                val startedAt = System.currentTimeMillis()
                dependencies.forEach { dep ->
                    if (cache[dep.packageName] == null) localCache.read("metadata:${dep.packageName}", PubDevClient.Metadata::class.java)?.let {
                        cache.putIfAbsent(dep.packageName, Cached(it.value, null, it.time))
                    }
                }
                val initial = dependencies.map { dep -> result(dep, installed[dep.name], cache[dep.packageName], options.includePrerelease, sdk) }
                val missing = initial.filter { it.latest == null || cache[it.dependency.packageName]?.metadata?.releases.isNullOrEmpty() }.map { it.dependency.packageName }.toSet()
                val fillMissing = root.path in pendingOpens
                val requests = DependencyRefreshPlan.requests(dependencies, changedPackages, missing,
                    force, options.automaticChecks, checkCache, fillMissing)
                val shouldFetch = requests.isNotEmpty()
                publish(root, revision, PackageState(root, initial, if (shouldFetch) text("packages.checking") else if (!options.automaticChecks) text("packages.disabled") else text("packages.unchanged")))
                val results = coroutineScope {
                    dependencies.map { dep -> async {
                        val edited = previousSignatures != null && dep.packageName in changedPackages
                        val previous = cache[dep.packageName]
                        val missingHistory = previous?.metadata != null && previous.metadata.releases.isNullOrEmpty() && previous.error == null
                        val bypassCache = force || missingHistory || options.automaticChecks && edited ||
                            fillMissing && dep.packageName in missing && cache[dep.packageName]?.error == null
                        val cached = if (dep.packageName in requests) metadata(dep.packageName, bypassCache, options.cacheMinutes, startedAt) else cache[dep.packageName]
                        ensureActive()
                        result(dep, installed[dep.name], cached, options.includePrerelease, sdk)
                    } }.awaitAll()
                }
                synchronized(this@PackageVersionService) {
                    if (revisions[root.path]?.get() == revision) {
                        if (shouldFetch) checkedSignatures[root.path] = dependencies.map { it.signature() }
                        pendingOpens.remove(root.path)
                    }
                }
                publish(root, revision, PackageState(root, results, if (results.any { it.error }) text("packages.failed") else if (!options.automaticChecks && !force && !shouldFetch) text("packages.disabled") else text("packages.complete")))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                publish(root, revision, PackageState(root, old?.results ?: emptyList(), text("error.details", e), false))
            }
        }
    }

    private suspend fun metadata(name: String, force: Boolean, minutes: Int, startedAt: Long): Cached = packageLocks.getOrPut(name) { Mutex() }.withLock {
        val previous = cache[name]
        if (previous != null && (previous.time in startedAt..System.currentTimeMillis() || !force && previous.fresh(minutes))) return@withLock previous
        network.withPermit {
            try {
                val value = PubDevClient.fetch(name)
                currentCoroutineContext().ensureActive()
                Cached(value, null, System.currentTimeMillis()).also {
                    cache[name] = it
                    localCache.write("metadata:$name", value, it.time)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Cached(previous?.metadata, e, System.currentTimeMillis()).also { cache[name] = it } }
        }
    }

    private fun result(dep: PackageDependency, installed: String?, cached: Cached?, prerelease: Boolean, sdk: ProjectSdk?): DependencyResult {
        fun result(status: LocalizedText, latest: String? = null, error: Boolean = false) =
            DependencyResult(dep, installed, latest, if (dep.overridden) text("packages.override", status) else status, error,
                cached?.recommendation(sdk, prerelease) ?: SdkRecommendation.Result(null, SdkRecommendation.Status.UNKNOWN))
        if (!dep.isPubDev) {
            val source = when (dep.source) { "custom hosted" -> "custom"; else -> dep.source }
            return result(text("packages.skipped", text("packages.source.$source")))
        }
        val latest = if (prerelease) cached?.metadata?.newest else cached?.metadata?.stable
        if (cached?.error != null) return result(text("packages.checkFailed", cached.error), latest, true)
        if (latest == null) return result(text("packages.notChecked"))
        val version = PubVersion.parse(latest) ?: return result(text("packages.unknownVersion"), latest, true)
        val current = installed?.let { PubVersion.parse(it) }
        val key = when {
            current != null && current >= version -> "packages.upToDate"
            PubConstraint.allows(dep.constraint, version) == null -> "packages.unsupported"
            PubConstraint.allows(dep.constraint, version) == false -> "packages.changeConstraint"
            current == null -> "packages.noResolved"
            else -> "packages.allowed"
        }
        return result(text(key), latest)
    }

    private fun installedVersions(root: VirtualFile): Map<String, String> = ReadAction.compute<Map<String, String>, RuntimeException> {
        var lock = root.findChild("pubspec.lock")
        if (lock == null) {
            var parent = root.parent
            while (parent != null) {
                val spec = parent.findChild("pubspec.yaml")
                if (spec != null) {
                    val map = YamlSupport.load(readText(spec)) as? Map<*, *>
                    if (map?.containsKey("workspace") == true) { lock = parent.findChild("pubspec.lock"); break }
                }
                parent = parent.parent
            }
        }
        if (lock == null) emptyMap() else {
            val data = YamlSupport.load(readText(lock)) as? Map<*, *>
            val packages = data?.get("packages") as? Map<*, *> ?: return@compute emptyMap()
            packages.mapNotNull { (name, value) -> (value as? Map<*, *>)?.get("version")?.toString()?.let { name.toString() to it } }.toMap()
        }
    }

    private fun readText(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: String(file.contentsToByteArray(), file.charset)

    private fun publish(root: VirtualFile, revision: Long, state: PackageState) {
        if (revisions[root.path]?.get() != revision || project.isDisposed) return
        states[root.path] = state
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && revisions[root.path]?.get() == revision) {
                val file = root.findChild("pubspec.yaml") ?: return@invokeLater
                FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
                    EditorFactory.getInstance().getEditors(document, project).filterNot { it.isDisposed }
                        .forEach(PubspecInlayHints::invalidate)
                }
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }
        }
    }
    override fun dispose() { scope.cancel() }
    companion object { private val watchedNames = setOf("pubspec.yaml", "pubspec.lock", "pubspec_overrides.yaml") }
}
