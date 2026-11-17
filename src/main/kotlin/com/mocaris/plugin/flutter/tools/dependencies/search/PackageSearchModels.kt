package com.mocaris.plugin.flutter.tools.dependencies.search

import com.mocaris.plugin.flutter.tools.dependencies.PubDevClient

data class PackageSearchPage(val names: List<String>, val hasMore: Boolean)
data class PackageRelease(val version: String, val description: String, val dartSdk: String?, val flutterSdk: String?, val published: String? = null)
data class PackageInfo(
    val name: String,
    val metadata: PubDevClient.Metadata,
    val version: String?,
    val description: String,
    val dartSdk: String?,
    val flutterSdk: String?,
    val repository: String?,
    val documentation: String?,
    val homepage: String?,
    val versions: List<PackageRelease> = emptyList(),
    val discontinued: Boolean = false,
    val replacedBy: String? = null,
) {
    fun forVersion(version: String?): PackageInfo {
        val release = versions.firstOrNull { it.version == version } ?: return this
        return copy(version = release.version, description = release.description,
            dartSdk = release.dartSdk, flutterSdk = release.flutterSdk)
    }
}
data class PackageScore(val likes: Int?, val points: Int?, val maxPoints: Int?, val publisher: String?)
data class PackageDocument(val html: String, val url: String)
