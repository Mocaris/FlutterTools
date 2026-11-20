package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubConstraint
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubVersion
import java.nio.file.Files
import java.nio.file.Path

/** Convert configuration in place. No package/source move and no automatic dependency command. */
internal object WorkspaceConversion {
    data class Options(val useRootAsPackage: Boolean, val discoverNestedWorkspaces: Boolean)
    data class Plan(val root: VirtualFile, val before: Map<String, String?>, val after: Map<String, String>, val members: Int)
    private val excluded = setOf(".git", ".idea", ".gradle", ".dart_tool", ".fvm", "build", "node_modules", ".pub-cache")
    private val minimum = PubVersion(3, 6, 0)

    internal fun sdkConstraint(value: String?, required: PubVersion = minimum): String {
        val floor = "${required.major}.${required.minor}.${required.patch}"
        val constraint = value?.trim().orEmpty()
        if (constraint.isEmpty() || constraint == "any") return ">=$floor <4.0.0"
        requireLocalized(PubConstraint.overlaps(constraint, ">=$floor") == true, "melos.sdkIncompatible", constraint, floor)
        if (constraint.startsWith('^')) {
            val lower = PubVersion.parse(constraint.drop(1)) ?: errorLocalized("melos.sdkIncompatible", constraint, floor)
            return if (lower < required) "^$floor" else constraint
        }
        if (PubVersion.parse(constraint) != null) return constraint
        val bounds = Regex("(>=|>|<=|<)\\s*([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)")
            .findAll(constraint).toList()
        val sufficientFloor = bounds.filter { it.groupValues[1].startsWith('>') }.any { PubVersion.parse(it.groupValues[2])!! >= required }
        if (sufficientFloor) return constraint
        return (listOf(">=$floor") + bounds.filter { it.groupValues[1].startsWith('<') }.map { it.value }).joinToString(" ")
    }

    fun prepare(project: Project, root: VirtualFile, melosVersion: String?, options: Options? = null,
                workspaceGlobs: Boolean = true): Plan {
        val before = linkedMapOf<String, String?>()
        var chars = 0
        fun read(path: String): String? = before.getOrPut(path) {
            val file = root.findFileByRelativePath(path) ?: return@getOrPut null
            val value = WorkspaceLayout.read(file) ?: errorLocalized("melos.tooLarge")
            chars += value.length
            requireLocalized(chars <= 8 * 1024 * 1024, "melos.tooLarge")
            value
        }
        fun mapping(text: String?) = text?.let { YamlSupport.load(it) as? Map<*, *> ?: errorLocalized("melos.invalidYaml") }.orEmpty()
        val rootText = read("pubspec.yaml")
        val pub = mapping(rootText)
        val overrides = mapping(read("pubspec_overrides.yaml"))
        val legacy = mapping(read("melos.yaml"))
        val rules = WorkspaceLayout.parse(rootText, before["pubspec_overrides.yaml"], before["melos.yaml"])
        val native = if (overrides.containsKey("workspace")) overrides["workspace"] else pub["workspace"]
        requireLocalized(native == null || native is List<*> && native.all { it is String && it.isNotBlank() }, "melos.invalidYaml")
        if (native is List<*>) native.filterIsInstance<String>().filter { path -> path.none { it in "*?{[" } }.forEach { relative ->
            requireLocalized(relative != "." && !relative.startsWith('/') && relative.split('/').none { it == ".." } &&
                root.findFileByRelativePath("$relative/pubspec.yaml") != null, "melos.missingMember", relative)
        }
        val membership = if (native is List<*>) WorkspaceLayout.Rules(native.filterIsInstance<String>(), emptyList(), null) else rules
        val specs = mutableListOf<String>()
        var visited = 0
        VfsUtilCore.iterateChildrenRecursively(root, { !it.isDirectory || it.name !in excluded && !Files.isSymbolicLink(Path.of(it.path)) }) { file ->
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            requireLocalized(++visited <= 250_000, "melos.tooLarge")
            if (!file.isDirectory && file.name == "pubspec.yaml" && file.parent != root) {
                val relative = VfsUtilCore.getRelativePath(file.parent, root)!!
                if (options?.discoverNestedWorkspaces == true && (relative == "packages" || relative.startsWith("packages/")) ||
                    membership == null || membership.includes(relative)) specs.add("$relative/pubspec.yaml")
                requireLocalized(specs.size <= 1000, "melos.tooLarge")
            }
            true
        }
        requireLocalized(rootText != null || specs.isNotEmpty(), "melos.noPackages")
        val names = mutableSetOf<String>()
        fun name(value: Any?, path: String): String {
            requireLocalized(value is String && value.matches(Regex("[a-z_][a-z0-9_]*")), "melos.invalidPackage", path)
            requireLocalized(names.add(value), "melos.duplicatePackage", value)
            return value
        }
        var source = rootText ?: "name: " + root.name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
            .let { if (it.firstOrNull()?.isDigit() == true) "workspace_$it" else it.ifEmpty { "workspace" } } + "\n"
        name(mapping(source)["name"], "pubspec.yaml")
        val after = linkedMapOf<String, String>()
        fun put(path: List<String>, value: Any) { source = WorkspaceYaml.put(project, source, path, value) }
        if (rootText == null) put(listOf("publish_to"), "none")
        val paths = specs.map { it.removeSuffix("/pubspec.yaml") }.sorted()
        val useGlobs = options?.discoverNestedWorkspaces == true && workspaceGlobs
        val workspace = when (options?.discoverNestedWorkspaces) {
            true -> if (useGlobs) listOf("packages/**") + paths.filter { it != "packages" && !it.startsWith("packages/") } else paths
            false -> paths
            null -> native ?: paths
        }
        put(listOf("environment", "sdk"), sdkConstraint((pub["environment"] as? Map<*, *>)?.get("sdk") as? String,
            if (useGlobs) PubVersion(3, 11, 0) else minimum))
        put(listOf("workspace"), workspace)
        val dependencies = pub["dependencies"] as? Map<*, *>
        val section = if (dependencies?.containsKey("melos") == true) "dependencies" else "dev_dependencies"
        val tool = (pub[section] as? Map<*, *>)?.get("melos")
        if (melosVersion != null && (tool == null || tool is String)) put(listOf(section, "melos"), "^$melosVersion")
        else if (melosVersion != null && tool is Map<*, *> && !tool.containsKey("git") && !tool.containsKey("path"))
            put(listOf(section, "melos", "version"), "^$melosVersion")
        val config = pub["melos"] as? Map<*, *> ?: legacy.filterKeys { it !in setOf("name", "packages") }
        if (pub["melos"] == null) put(listOf("melos"), config)
        source = WorkspaceYaml.block(project, source, "melos")
        if (options != null) {
            put(listOf("melos", "useRootAsPackage"), options.useRootAsPackage)
            put(listOf("melos", "discoverNestedWorkspaces"), options.discoverNestedWorkspaces)
        } else if (rootText != null && !config.containsKey("useRootAsPackage")) put(listOf("melos", "useRootAsPackage"), true)
        if (melosVersion != null && (config["scripts"] as? Map<*, *>)?.containsKey("analyze") != true)
            put(listOf("melos", "scripts", "analyze"), "melos exec -- dart analyze")
        after["pubspec.yaml"] = source
        for (path in specs.sorted()) {
            val text = read(path)!!
            val member = mapping(text)
            name(member["name"], path)
            var updated = WorkspaceYaml.put(project, text, listOf("environment", "sdk"), sdkConstraint((member["environment"] as? Map<*, *>)?.get("sdk") as? String))
            updated = WorkspaceYaml.put(project, updated, listOf("resolution"), "workspace")
            after[path] = updated
            val externalPath = path.removeSuffix("pubspec.yaml") + "pubspec_overrides.yaml"
            val external = read(externalPath)
            if (external != null && mapping(external).containsKey("resolution"))
                after[externalPath] = WorkspaceYaml.put(project, external, listOf("resolution"), "workspace")
        }
        source = WorkspaceYaml.block(project, source, "workspace")
        source = WorkspaceYaml.block(project, source, "melos")
        after["pubspec.yaml"] = source
        before["pubspec_overrides.yaml"]?.takeIf { overrides.containsKey("workspace") }?.let {
            after["pubspec_overrides.yaml"] = WorkspaceYaml.block(project,
                if (options != null) WorkspaceYaml.put(project, it, listOf("workspace"), workspace) else it, "workspace")
        }
        return Plan(root, before, after.filter { (path, text) -> before[path] != text }, specs.size)
    }

    fun apply(project: Project, plan: Plan) {
        fun checkUnchanged() {
            requireLocalized(plan.root.isValid, "melos.conflict")
            plan.before.forEach { (path, expected) -> requireLocalized(WorkspaceLayout.read(plan.root.findFileByRelativePath(path)) == expected, "melos.conflict") }
        }
        checkUnchanged()
        if (plan.after.isEmpty()) return
        val files = plan.after.keys.mapNotNull { plan.root.findFileByRelativePath(it) }
        requireLocalized(!ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files).hasReadonlyFiles(), "melos.readOnly")
        requireLocalized(plan.root.isWritable, "melos.readOnly")
        val manager = FileDocumentManager.getInstance()
        WriteCommandAction.writeCommandAction(project).withName(message("melos.convertCommand")).withGlobalUndo().run<RuntimeException> {
            checkUnchanged()
            val created = mutableListOf<VirtualFile>()
            val edited = mutableListOf<Pair<com.intellij.openapi.editor.Document, String>>()
            try {
                plan.after.forEach { (path, text) ->
                    val file = plan.root.findFileByRelativePath(path) ?: plan.root.createChildData(this, path).also(created::add)
                    val document = manager.getDocument(file) ?: errorLocalized("melos.invalidYaml")
                    edited.add(document to document.text); document.setText(text)
                }
            } catch (e: Exception) {
                edited.asReversed().forEach { (document, text) -> document.setText(text) }
                created.asReversed().forEach { if (it.isValid) it.delete(this) }
                throw e
            }
        }
        plan.after.keys.mapNotNull { plan.root.findFileByRelativePath(it)?.let(manager::getCachedDocument) }.forEach(manager::saveDocument)
    }

    fun convert(project: Project, root: VirtualFile, options: Options? = null, finished: (String) -> Unit) =
        update(project, root, options, true, finished)

    fun saveOptions(project: Project, root: VirtualFile, options: Options, finished: (String) -> Unit) =
        update(project, root, options, false, finished)

    private fun update(project: Project, root: VirtualFile, options: Options?, conversion: Boolean, finished: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val sdk = if (conversion || options?.discoverNestedWorkspaces == true) MelosVersion.context(root) else null
                val workspaceGlobs = options?.discoverNestedWorkspaces == true && sdk?.workspaceGlobs == true
                val version = if (conversion) kotlinx.coroutines.runBlocking {
                    MelosVersion.resolve(project, root, workspaceGlobs, sdk!!)
                } else null
                ReadAction.compute<Plan, RuntimeException> { prepare(project, root, version, options, workspaceGlobs) }
            }
            ApplicationManager.getApplication().invokeLater(Runnable {
                if (project.isDisposed) return@Runnable
                WriteIntentReadAction.run(Runnable {
                    try {
                        val plan = result.getOrThrow(); apply(project, plan)
                        finished(if (conversion) message("melos.converted", plan.members) else message("melos.optionsSaved"))
                    } catch (e: Exception) { finished(e.localizedMessage ?: message("error.unknown")) }
                })
            }, ModalityState.NON_MODAL)
        }
    }
}
