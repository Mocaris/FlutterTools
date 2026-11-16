package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.LocalizedText

data class PackageDependency(
    val name: String,
    val packageName: String = name,
    val section: String,
    val constraint: String,
    val source: String,
    val overridden: Boolean = false,
    val keyOffset: Int = 0,
    val versionOffset: Int = keyOffset
) {
    val isPubDev get() = source == "pub.dev"
    fun samePackage(other: PackageDependency) = name == other.name && packageName == other.packageName &&
        section == other.section && source == other.source && overridden == other.overridden
    fun signature() = listOf(name, packageName, section, constraint, source, overridden.toString())
}

/** Version edits invalidate only the corresponding hosted package's metadata. */
internal object DependencyRefreshPlan {
    fun changedPackages(dependencies: List<PackageDependency>, previous: List<List<String>>?): Set<String> =
        dependencies.filter { it.isPubDev && it.signature() !in previous.orEmpty() }.map { it.packageName }.toSet()
}

data class DependencyResult(val dependency: PackageDependency, val installed: String?, val latest: String?, private val statusText: LocalizedText, val error: Boolean = false) {
    val status get() = statusText.toString()
}
