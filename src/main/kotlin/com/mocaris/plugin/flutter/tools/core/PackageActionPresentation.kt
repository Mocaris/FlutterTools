package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.localization.FlutterToolsBundle.message

/** Keep package targets and disabled reasons consistent across execution menus. */
internal object PackageActionPresentation {
    fun update(event: AnActionEvent, actionId: String, roots: List<VirtualFile>, running: (VirtualFile) -> Boolean) {
        val project = event.project?.takeUnless { it.isDisposed }
        val candidates = roots.filter { it.isValid && it.findChild("pubspec.yaml") != null }
        val root = project?.let { FlutterPackageLocator.choosePackage(it, candidates, event.getData(CommonDataKeys.VIRTUAL_FILE)) }
        val busy = root != null && running(root)
        val label = message("action.$actionId.text")
        val description = message("action.$actionId.description")
        event.presentation.isEnabled = root != null && !busy
        event.presentation.text = if (root == null) label else message("action.packageTarget", label, FlutterPackageLocator.displayName(project!!, root))
        event.presentation.description = when {
            project == null -> description
            root == null -> message(if (candidates.isEmpty()) "package.missing" else "package.select")
            busy -> message("action.packageRunning", root.path)
            else -> message("action.packageDescription", description, root.path)
        }
    }
}
