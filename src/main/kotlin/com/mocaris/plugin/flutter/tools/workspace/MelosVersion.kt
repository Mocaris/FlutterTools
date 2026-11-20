package com.mocaris.plugin.flutter.tools.workspace

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.WorkspaceLayout
import com.mocaris.plugin.flutter.tools.core.localization.errorLocalized
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageRelease
import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageSearchService
import com.mocaris.plugin.flutter.tools.dependencies.sdk.ProjectSdk
import com.mocaris.plugin.flutter.tools.dependencies.sdk.ProjectSdkReader
import com.mocaris.plugin.flutter.tools.dependencies.sdk.SdkRecommendation
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubVersion
import java.nio.file.Path

internal object MelosVersion {
    data class Context(val sdk: ProjectSdk?, val config: Map<*, *>, val version: String) {
        val workspaceGlobs get() = PubVersion.parse(version)?.let { it >= PubVersion(3, 11, 0) } == true
    }

    fun context(root: VirtualFile): Context {
        val (sdk, config) = ReadAction.compute<Pair<ProjectSdk?, Map<*, *>>, RuntimeException> {
            ProjectSdkReader.read(root) to WorkspaceLayout.rules(root)?.melos.orEmpty()
        }
        return Context(sdk, config, actualVersion(root, sdk, config))
    }

    fun latest(releases: List<PackageRelease>, sdk: ProjectSdk?): String =
        SdkRecommendation.recommend(releases.filter { (PubVersion.parse(it.version)?.major ?: 0) >= 7 }, sdk).version
            ?: errorLocalized("melos.noCompatibleVersion", sdk?.dartVersion ?: sdk?.dart ?: "?")

    suspend fun resolve(project: Project, root: VirtualFile, discoverNestedWorkspaces: Boolean = false,
                        context: Context = context(root),
                        fetch: suspend () -> List<PackageRelease> = { project.service<PackageSearchService>().info("melos").versions }): String {
        val (sdk, config, actual) = context
        if (discoverNestedWorkspaces) com.mocaris.plugin.flutter.tools.core.localization.requireLocalized(context.workspaceGlobs, "melos.globSdk", actual)
        val target = (sdk ?: ProjectSdk(root.name, null, null, null, null)).copy(
            dart = WorkspaceConversion.sdkConstraint(sdk?.dart,
                if (discoverNestedWorkspaces) PubVersion(3, 11, 0) else PubVersion(3, 6, 0)), dartVersion = actual)
        val releases = fetch()
        val unchanged = ReadAction.compute<Boolean, RuntimeException> {
            ProjectSdkReader.read(root) == sdk && WorkspaceLayout.rules(root)?.melos.orEmpty() == config
        }
        com.mocaris.plugin.flutter.tools.core.localization.requireLocalized(unchanged, "melos.conflict")
        return latest(releases, target)
    }

    private fun actualVersion(root: VirtualFile, sdk: ProjectSdk?, config: Map<*, *>): String {
        return runCatching {
            val dart = MelosCommands.dartExecutable(Path.of(root.path), config)
            val arguments = if (com.intellij.openapi.util.SystemInfo.isWindows && dart.endsWith(".bat", true))
                listOf("cmd.exe", "/c", dart, "--version") else listOf(dart, "--version")
            val output = CapturingProcessHandler(GeneralCommandLine(arguments)
                .withWorkDirectory(root.path).withCharset(Charsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)).runProcess(5000)
            if (output.isTimeout || output.exitCode != 0) null else
                Regex("Dart SDK version: ([^ ]+)").find(output.stdout + output.stderr)?.groupValues?.get(1)?.trim()
                    ?.takeIf { PubVersion.parse(it) != null }
        }.getOrNull() ?: sdk?.dartVersion ?: errorLocalized("melos.unknownSdk")
    }

}
